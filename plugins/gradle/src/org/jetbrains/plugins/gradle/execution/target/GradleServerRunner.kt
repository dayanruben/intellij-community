// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.execution.target

import com.intellij.execution.Platform
import com.intellij.execution.process.*
import com.intellij.execution.target.HostPort
import com.intellij.execution.target.TargetPlatform
import com.intellij.execution.target.TargetProgressIndicator
import com.intellij.execution.target.TargetedCommandLine
import com.intellij.execution.target.value.getTargetUploadPath
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.externalSystem.service.remote.MultiLoaderObjectInputStream
import com.intellij.openapi.externalSystem.util.wsl.connectRetrying
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.NlsSafe
import com.intellij.util.io.BaseOutputReader
import com.intellij.util.text.nullize
import org.gradle.initialization.BuildEventConsumer
import org.gradle.internal.remote.internal.RemoteConnection
import org.gradle.internal.remote.internal.inet.SocketInetAddress
import org.gradle.internal.remote.internal.inet.TcpOutgoingConnector
import org.gradle.internal.serialize.Serializers
import org.gradle.launcher.daemon.protocol.*
import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.ResultHandler
import org.gradle.tooling.internal.consumer.parameters.ConsumerOperationParameters
import org.gradle.tooling.internal.provider.action.BuildActionSerializer
import org.jetbrains.plugins.gradle.service.execution.GradleServerConfigurationProvider
import org.jetbrains.plugins.gradle.tooling.proxy.TargetBuildParameters
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.concurrent.Future

internal class GradleServerRunner(private val connection: TargetProjectConnection,
                                  private val consumerOperationParameters: ConsumerOperationParameters,
                                  private val prepareTaskState: Boolean) {

  fun run(
    classloaderHolder: GradleToolingProxyClassloaderHolder,
    targetBuildParametersBuilder: TargetBuildParameters.Builder<*>,
    resultHandler: ResultHandler<Any?>,
  ) {
    val project: Project = connection.taskId?.findProject() ?: return
    val progressIndicator = GradleServerProgressIndicator(connection.taskId, connection.taskListener)
    consumerOperationParameters.cancellationToken.addCallback(progressIndicator::cancel)
    val serverEnvironmentSetup = GradleServerEnvironmentSetupImpl(project, connection, prepareTaskState)
    val commandLine = serverEnvironmentSetup.prepareEnvironment(targetBuildParametersBuilder, consumerOperationParameters, progressIndicator)
    runTargetProcess(commandLine, serverEnvironmentSetup, progressIndicator, resultHandler, classloaderHolder)
  }

  private fun runTargetProcess(
    targetedCommandLine: TargetedCommandLine,
    serverEnvironmentSetup: GradleServerEnvironmentSetup,
    targetProgressIndicator: GradleServerProgressIndicator,
    resultHandler: ResultHandler<Any?>,
    classloaderHolder: GradleToolingProxyClassloaderHolder,
  ) {
    targetProgressIndicator.checkCanceled()
    val remoteEnvironment = serverEnvironmentSetup.getTargetEnvironment()
    val process = remoteEnvironment.createProcess(targetedCommandLine, EmptyProgressIndicator())
    val processHandler: CapturingProcessHandler = object :
      CapturingProcessHandler(process, targetedCommandLine.charset, targetedCommandLine.getCommandPresentation(remoteEnvironment)) {
      override fun readerOptions(): BaseOutputReader.Options {
        return BaseOutputReader.Options.forMostlySilentProcess()
      }
    }
    val projectUploadRoot = serverEnvironmentSetup.getProjectUploadRoot()
    val targetProjectBasePath = projectUploadRoot.getTargetUploadPath().apply(remoteEnvironment)
    val localProjectBasePath = projectUploadRoot.localRootPath.toString()
    val targetPlatform = remoteEnvironment.targetPlatform

    val serverConfigurationProvider = connection.environmentConfigurationProvider as? GradleServerConfigurationProvider
    val connectionAddressResolver: (HostPort) -> HostPort = {
      val serverBindingPort = serverEnvironmentSetup.getServerBindingPort()
      val localPort = serverBindingPort?.localValue?.blockingGet(0)
      val targetPort = serverBindingPort?.targetValue?.blockingGet(0)
      val hostPort = if (targetPort == it.port && localPort != null) HostPort(it.host, localPort) else it
      serverConfigurationProvider?.getClientCommunicationAddress(serverEnvironmentSetup.getEnvironmentConfiguration(), hostPort) ?: hostPort
    }
    val gradleServerEventsListener = GradleServerEventsListener(serverEnvironmentSetup, connectionAddressResolver, classloaderHolder) {
      when (it) {
        is String -> {
          consumerOperationParameters.progressListener.run {
            onOperationStart(it)
            onOperationEnd()
          }
        }
        is org.jetbrains.plugins.gradle.tooling.proxy.StandardError -> {
          targetProgressIndicator.addText(
            resolveFistPath(it.text.useLocalLineSeparators(targetPlatform), targetProjectBasePath, localProjectBasePath, targetPlatform),
            ProcessOutputType.STDERR)
        }
        is org.jetbrains.plugins.gradle.tooling.proxy.StandardOutput -> {
          targetProgressIndicator.addText(
            resolveFistPath(it.text.useLocalLineSeparators(targetPlatform), targetProjectBasePath, localProjectBasePath, targetPlatform),
            ProcessOutputType.STDOUT)
        }
        else -> {
          consumerOperationParameters.buildProgressListener.onEvent(it)
        }
      }
    }
    val listener = GradleServerProcessListener(targetProgressIndicator, resultHandler, gradleServerEventsListener)
    processHandler.addProcessListener(listener)
    processHandler.runProcessWithProgressIndicator(targetProgressIndicator.progressIndicator, -1, true)
  }

  private fun String.useLocalLineSeparators(targetPlatform: TargetPlatform) =
    if (targetPlatform.platform == Platform.current()) this
    else replace(targetPlatform.platform.lineSeparator, Platform.current().lineSeparator)

  private fun String.useLocalFileSeparators(platform: Platform, uriMode: Boolean): String {
    val separator = if (uriMode)
        '/'
      else
        Platform.current().fileSeparator

    return if (platform.fileSeparator == separator) this
    else replace(platform.fileSeparator, separator)
  }

  @NlsSafe
  private fun resolveFistPath(@NlsSafe text: String,
                              targetProjectBasePath: String,
                              localProjectBasePath: String,
                              targetPlatform: TargetPlatform): String {
    val pathIndexStart = text.indexOf(targetProjectBasePath)
    if (pathIndexStart == -1) return text

    val delimiter = if (pathIndexStart == 0) ' '
    else {
      val char = text[pathIndexStart - 1]
      if (char != '\'' && char != '\"') ' ' else char
    }
    var pathIndexEnd = text.indexOf(delimiter, pathIndexStart)
    if (pathIndexEnd == -1) {
      pathIndexEnd = text.indexOf('\n', pathIndexStart)
    }

    if (pathIndexEnd == -1) pathIndexEnd = text.length

    val isUri = text.substring(maxOf(0, pathIndexStart - 7), maxOf(0,pathIndexStart - 1)).endsWith("file:/")
    val path = text.substring(pathIndexStart + targetProjectBasePath.length, pathIndexEnd).useLocalFileSeparators(targetPlatform.platform, isUri)

    val buf = StringBuilder()
    buf.append(text.subSequence(0, pathIndexStart))
    buf.append(localProjectBasePath.useLocalFileSeparators(Platform.current(), isUri))
    buf.append(path)
    buf.append(text.substring(pathIndexEnd))
    return buf.toString()
  }

  private class GradleServerEventsListener(
    private val serverEnvironmentSetup: GradleServerEnvironmentSetup,
    private val connectionAddressResolver: (HostPort) -> HostPort,
    private val classloaderHolder: GradleToolingProxyClassloaderHolder,
    private val buildEventConsumer: BuildEventConsumer,
  ) {

    private lateinit var listenerTask: Future<*>

    fun start(hostName: String, port: Int, resultHandler: ResultHandler<Any?>) {
      check(!::listenerTask.isInitialized) { "Gradle server events listener has already been started" }
      listenerTask = ApplicationManager.getApplication().executeOnPooledThread {
        try {
          doRun(hostName, port, resultHandler)
        }
        catch (t: Throwable) {
          resultHandler.onFailure(GradleConnectionException(t.message, t))
        }
      }
    }

    private fun createConnection(hostName: String, port: Int): RemoteConnection<Message> {
      val hostPort = connectionAddressResolver.invoke(HostPort(hostName, port))
      val inetAddress = InetAddress.getByName(hostPort.host)
      val connectCompletion = connectRetrying(5000) { TcpOutgoingConnector().connect(SocketInetAddress(inetAddress, hostPort.port)) }
      val serializer = DaemonMessageSerializer.create(BuildActionSerializer.create())
      return connectCompletion.create(Serializers.stateful(serializer))
    }

    private fun doRun(hostName: String, port: Int, resultHandler: ResultHandler<Any?>) {

      val connection = createConnection(hostName, port)

      connection.dispatch(BuildEvent(serverEnvironmentSetup.getTargetBuildParameters()))
      connection.flush()

      try {
        loop@ while (true) {
          val message = connection.receive() ?: break
          when (message) {
            is Success -> {
              val value = deserializeIfNeeded(message.value)
              resultHandler.onComplete(value)
              break@loop
            }
            is Failure -> {
              resultHandler.onFailure(message.value as? GradleConnectionException ?: GradleConnectionException(message.value.message))
              break@loop
            }
            is BuildEvent -> {
              buildEventConsumer.dispatch(message.payload)
            }
            is org.jetbrains.plugins.gradle.tooling.proxy.Output -> {
              buildEventConsumer.dispatch(message)
            }
            is org.jetbrains.plugins.gradle.tooling.proxy.IntermediateResult -> {
              val value = deserializeIfNeeded(message.value)
              serverEnvironmentSetup.getTargetIntermediateResultHandler().onResult(message.type, value)
            }
            else -> {
              break@loop
            }
          }
        }
      }
      finally {
        connection.sendResultAck()
      }
    }

    private fun deserializeIfNeeded(value: Any?): Any? {
      val bytes = value as? ByteArray ?: return value
      val deserialized = MultiLoaderObjectInputStream(ByteArrayInputStream(bytes), classloaderHolder.getClassloaders()).use {
        it.readObject()
      }
      return deserialized
    }

    private fun RemoteConnection<Message>.sendResultAck() {
      dispatch(BuildEvent("ack"))
      flush()
      stop()
    }

    fun stop() {
      if (::listenerTask.isInitialized && !listenerTask.isDone) {
        listenerTask.cancel(true)
      }
    }

    fun waitForResult(handler: () -> Boolean) {
      val startTime = System.currentTimeMillis()
      while (!handler.invoke() &&
             (::listenerTask.isInitialized.not() || !listenerTask.isDone) &&
             System.currentTimeMillis() - startTime < 10000) {
        val lock = Object()
        synchronized(lock) {
          try {
            lock.wait(100)
          }
          catch (_: InterruptedException) {
          }
        }
      }
    }
  }

  private class GradleServerProcessListener(
    private val targetProgressIndicator: TargetProgressIndicator,
    private val resultHandler: ResultHandler<Any?>,
    private val gradleServerEventsListener: GradleServerEventsListener,
  ) : ProcessListener {
    @Volatile
    private var connectionAddressReceived = false

    @Volatile
    var resultReceived = false

    val resultHandlerWrapper: ResultHandler<Any?> = object : ResultHandler<Any?> {
      override fun onComplete(result: Any?) {
        resultReceived = true
        resultHandler.onComplete(result)
      }

      override fun onFailure(gradleConnectionException: GradleConnectionException) {
        resultReceived = true
        resultHandler.onFailure(gradleConnectionException)
      }
    }

    override fun processTerminated(event: ProcessEvent) {
      if (!resultReceived) {
        gradleServerEventsListener.waitForResult { resultReceived || targetProgressIndicator.isCanceled }
      }
      if (!resultReceived) {
        val outputType = if (event.exitCode == 0) ProcessOutputType.STDOUT else ProcessOutputType.STDERR
        event.text?.also { targetProgressIndicator.addText(it, outputType) }
        val gradleConnectionException = if (targetProgressIndicator.isCanceled) {
          BuildCancelledException("Build cancelled.")
        }
        else {
          GradleConnectionException("Operation result has not been received.")
        }
        resultHandler.onFailure(gradleConnectionException)
      }
      gradleServerEventsListener.stop()
    }

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
      log.traceIfNotEmpty(event.text)
      if (connectionAddressReceived) return
      if (outputType === ProcessOutputTypes.STDERR) {
        targetProgressIndicator.addText(event.text, outputType)
      }
      if (event.text.startsWith(connectionConfLinePrefix)) {
        connectionAddressReceived = true
        val hostName = event.text.substringAfter(connectionConfLinePrefix).substringBefore(" port: ")
        val port = event.text.substringAfter(" port: ").trim().toInt()
        gradleServerEventsListener.start(hostName, port, resultHandlerWrapper)
      }
    }

    companion object {
      private const val connectionConfLinePrefix = "Gradle target server hostAddress: "
    }
  }

  companion object {
    private val log = logger<GradleServerRunner>()
  }
}

private fun Logger.traceIfNotEmpty(text: @NlsSafe String?) {
  text.nullize(true)?.also { trace { it.trimEnd() } }
}
