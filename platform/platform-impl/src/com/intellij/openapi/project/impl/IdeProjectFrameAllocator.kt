// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.project.impl

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings
import com.intellij.concurrency.captureThreadContext
import com.intellij.conversion.CannotConvertException
import com.intellij.diagnostic.StartUpMeasurer
import com.intellij.diagnostic.StartUpPerformanceService
import com.intellij.diagnostic.dumpCoroutines
import com.intellij.featureStatistics.fusCollectors.FileEditorCollector.EmptyStateCause
import com.intellij.featureStatistics.fusCollectors.LifecycleUsageTriggerCollector
import com.intellij.ide.*
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.util.runOnceForProject
import com.intellij.idea.AppMode
import com.intellij.openapi.application.*
import com.intellij.openapi.components.ComponentManagerEx
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.*
import com.intellij.openapi.fileEditor.impl.text.AsyncEditorLoader
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ReadmeShownUsageCollector.README_OPENED_ON_START_TS
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.project.isNotificationSilentMode
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.registry.RegistryManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.wm.impl.*
import com.intellij.platform.diagnostic.telemetry.impl.getTraceActivity
import com.intellij.platform.diagnostic.telemetry.impl.rootTask
import com.intellij.platform.diagnostic.telemetry.impl.span
import com.intellij.platform.ide.bootstrap.hideSplash
import com.intellij.platform.ide.diagnostic.startUpPerformanceReporter.FUSProjectHotStartUpMeasurer
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.PsiManager
import com.intellij.toolWindow.computeToolWindowBeans
import com.intellij.ui.ScreenUtil
import com.intellij.util.PlatformUtils
import com.intellij.util.TimeoutUtil
import com.intellij.util.messages.SimpleMessageBusConnection
import kotlinx.coroutines.*
import org.jetbrains.annotations.ApiStatus
import java.awt.Dimension
import java.awt.Frame
import java.awt.Rectangle
import java.nio.file.Path
import java.time.Instant
import javax.swing.JFrame
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

internal class IdeProjectFrameAllocator(
  private val options: OpenProjectTask,
  private val projectStoreBaseDir: Path,
) : ProjectFrameAllocator {
  private val deferredProjectFrameHelper = CompletableDeferred<IdeProjectFrameHelper>()

  override suspend fun preInitProject(project: Project) {
    (project.serviceAsync<FileEditorManager>() as? FileEditorManagerImpl)?.initJob?.join()
  }

  override suspend fun runInBackground(projectInitObservable: ProjectInitObservable) {
    coroutineScope {
      val application = ApplicationManager.getApplication()
      if (application == null || application.isUnitTestMode || application.isInternal) {
        launch {
          delay(10.seconds)
          // logged only during development, let's not spam users
          logger<ProjectFrameAllocator>().warn("Cannot load project in 10 seconds: ${dumpCoroutines()}")
        }
      }

      val project = projectInitObservable.awaitProjectInit()
      val connection = project.messageBus.connect(this)
      hideSplashWhenEditorOrToolWindowShown(connection)
    }
  }

  override suspend fun run(projectInitObservable: ProjectInitObservable) {
    coroutineScope {
      val job = currentCoroutineContext().job

      launch(CoroutineName("project frame creating")) {
        val loadingState = MutableLoadingState(done = job)
        createFrameManager(loadingState)
      }.invokeOnCompletion { cause ->
        if (cause is CancellationException) {
          job.cancel(cause)
        }
      }

      launch {
        val project = projectInitObservable.awaitProjectPreInit()
        val frameHelper = deferredProjectFrameHelper.await()

        launch {
          val windowManager = serviceAsync<WindowManager>() as WindowManagerImpl
          withContext(Dispatchers.ui(CoroutineSupport.UiDispatcherKind.STRICT)) {
            windowManager.assignFrame(frameHelper, project)
            frameHelper.setRawProject(project)
          }
        }

        launch {
          val fileEditorManager = project.serviceAsync<FileEditorManager>() as FileEditorManagerImpl
          fileEditorManager.initJob.join()
          withContext(Dispatchers.UiWithModelAccess) {
            frameHelper.toolWindowPane.setDocumentComponent(fileEditorManager.mainSplitters)
          }
        }

        launch {
          span("project frame assigning") {
            frameHelper.setProject(project)
          }
        }
      }

      val reopeningEditorJob = launch {
        val project = projectInitObservable.awaitProjectInit()
        span("restoreEditors") {
          val fileEditorManager = project.serviceAsync<FileEditorManager>() as FileEditorManagerImpl
          restoreEditors(project = project, fileEditorManager = fileEditorManager)
        }

        val start = projectInitObservable.projectInitTimestamp
        if (start != -1L) {
          StartUpMeasurer.addCompletedActivity(start, "editor reopening and frame waiting", getTraceActivity())
        }
      }

      val toolWindowInitJob = launch {
        val project = projectInitObservable.awaitProjectInit()
        span("initFrame") {
          launch(CoroutineName("tool window pane creation")) {
            val deferredToolWindowManager = async { project.serviceAsync<ToolWindowManager>() as? ToolWindowManagerImpl }
            val taskListDeferred = async(CoroutineName("toolwindow init command creation")) {
              computeToolWindowBeans(project = project)
            }

            val toolWindowManager = deferredToolWindowManager.await() ?: return@launch
            val projectFrameHelper = deferredProjectFrameHelper.await()
            val toolWindowPane = withContext(Dispatchers.UI) {
              projectFrameHelper.toolWindowPane
            }
            toolWindowManager.init(pane = toolWindowPane, reopeningEditorJob = reopeningEditorJob, taskListDeferred = taskListDeferred)
          }
        }
      }

      launch {
        val project = projectInitObservable.awaitProjectInit()
        val startUpContextElementToPass = FUSProjectHotStartUpMeasurer.getStartUpContextElementToPass() ?: EmptyCoroutineContext

        val onNoEditorsLeft = captureThreadContext { FUSProjectHotStartUpMeasurer.reportNoMoreEditorsOnStartup(System.nanoTime()) }

        @Suppress("UsagesOfObsoleteApi")
        (project as ComponentManagerEx).getCoroutineScope().launch(startUpContextElementToPass + rootTask()) {
          val frameHelper = deferredProjectFrameHelper.await()
          launch {
            frameHelper.installDefaultProjectStatusBarWidgets(project)
            frameHelper.updateTitle(serviceAsync<FrameTitleBuilder>().getProjectTitle(project), project)
          }

          reopeningEditorJob.join()
          postOpenEditors(
            frameHelper = frameHelper,
            fileEditorManager = project.serviceAsync<FileEditorManager>() as FileEditorManagerImpl,
            toolWindowInitJob = toolWindowInitJob,
            project = project,
          )
        }.invokeOnCompletion { throwable ->
          if (throwable != null) {
            onNoEditorsLeft()
          }
        }
      }
    }
  }

  private suspend fun createFrameManager(loadingState: FrameLoadingState) {
    val frame = getFrame()
    val frameInfo = getFrameInfo()

    withContext(Dispatchers.ui(CoroutineSupport.UiDispatcherKind.STRICT)) {
      if (frame != null) {
        if (!frame.isVisible) {
          throw CancellationException("Pre-allocated frame was already closed")
        }
        val frameHelper = IdeProjectFrameHelper(frame = frame, loadingState = loadingState)
        completeFrameAndCloseOnCancel(frameHelper) {
          if (options.forceOpenInNewFrame) {
            frameHelper.updateFullScreenState(frameInfo.fullScreen)
          }
          span("ProjectFrameHelper.init") {
            frameHelper.init()
          }
          frameHelper.setInitBounds(frameInfo.bounds)
        }
      }
      else {
        val frameHelper = IdeProjectFrameHelper(createIdeFrame(frameInfo), loadingState = loadingState)
        // must be after preInit (frame decorator is required to set a full-screen mode)
        withContext(Dispatchers.UiWithModelAccess) {
          frameHelper.frame.isVisible = true
        }
        completeFrameAndCloseOnCancel(frameHelper) {
          frameHelper.updateFullScreenState(frameInfo.fullScreen)

          span("ProjectFrameHelper.init") {
            frameHelper.init()
          }
        }
      }
    }
  }

  private suspend inline fun completeFrameAndCloseOnCancel(
    frameHelper: IdeProjectFrameHelper,
    task: () -> Unit,
  ) {
    try {
      task()
      if (!deferredProjectFrameHelper.isCancelled) {
        deferredProjectFrameHelper.complete(frameHelper)
        return
      }
    }
    catch (@Suppress("IncorrectCancellationExceptionHandling") _: CancellationException) {
    }

    // make sure that in case of some error we close the frame for a not loaded project
    withContext(Dispatchers.ui(CoroutineSupport.UiDispatcherKind.STRICT) + NonCancellable) {
      (serviceAsync<WindowManager>() as WindowManagerImpl).releaseFrame(frameHelper)
    }
  }

  private fun getFrame(): IdeFrameImpl? {
    return options.frame
           ?: (serviceIfCreated<WindowManager>() as? WindowManagerImpl)?.removeAndGetRootFrame()
  }

  private suspend fun getFrameInfo(): FrameInfo {
    return options.frameInfo
           ?: (serviceAsync<RecentProjectsManager>() as RecentProjectsManagerBase).getProjectMetaInfo(projectStoreBaseDir)?.frame
           ?: FrameInfo()
  }

  override suspend fun projectNotLoaded(cannotConvertException: CannotConvertException?) {
    val frameHelper = if (deferredProjectFrameHelper.isCompleted) {
      deferredProjectFrameHelper.await()
    }
    else {
      deferredProjectFrameHelper.cancel("projectNotLoaded")
      null
    }

    withContext(Dispatchers.EDT) {
      if (cannotConvertException != null) {
        Messages.showErrorDialog(
          frameHelper?.frame,
          IdeBundle.message("error.cannot.convert.project", cannotConvertException.message),
          IdeBundle.message("title.cannot.convert.project")
        )
      }

      if (frameHelper != null) {
        // projectLoaded was called, but then due to some error, say cancellation, still projectNotLoaded is called
        (serviceAsync<WindowManager>() as WindowManagerImpl).releaseFrame(frameHelper)
      }
    }
  }
}

private suspend fun hideSplashWhenEditorOrToolWindowShown(connection: SimpleMessageBusConnection) {
  val splashHiddenDeferred = CompletableDeferred<Unit>()

  fun hideSplashAndComplete() {
    hideSplash()
    connection.disconnect()
    splashHiddenDeferred.complete(Unit)
  }

  connection.subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
    override fun toolWindowShown(toolWindow: ToolWindow) {
      hideSplashAndComplete()
    }
  })
  connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
      hideSplashAndComplete()
    }
  })
  splashHiddenDeferred.await()
}

private suspend fun restoreEditors(project: Project, fileEditorManager: FileEditorManagerImpl) {
  coroutineScope {
    // only after FileEditorManager.init - DaemonCodeAnalyzer uses FileEditorManager
    // DaemonCodeAnalyzer wants DaemonCodeAnalyzerSettings
    val daemonCodeAnalyzerSettingsJob = launch {
      serviceAsync<DaemonCodeAnalyzerSettings>()
    }
    launch {
      // WolfTheProblemSolver uses PsiManager
      project.serviceAsync<PsiManager>()
      project.serviceAsync<WolfTheProblemSolver>()
    }
    launch {
      daemonCodeAnalyzerSettingsJob.join()
      project.serviceAsync<DaemonCodeAnalyzer>()
    }

    val (editorComponent, editorState) = fileEditorManager.init()
    if (editorState == null) {
      serviceAsync<StartUpPerformanceService>().editorRestoringTillHighlighted()
      return@coroutineScope
    }

    span("editor restoring") {
      editorComponent.createEditors(state = editorState)
    }

    span("editor reopening post-processing", Dispatchers.EDT) {
      for (window in editorComponent.windows().toList()) {
        // clear empty splitters
        if (window.tabCount == 0) {
          window.removeFromSplitter()
          window.logEmptyStateIfMainSplitter(cause = EmptyStateCause.PROJECT_OPENED)
        }
      }

      focusSelectedEditor(editorComponent)
    }
  }
}

private suspend fun postOpenEditors(
  frameHelper: IdeProjectFrameHelper,
  fileEditorManager: FileEditorManagerImpl,
  project: Project,
  toolWindowInitJob: Job,
) {
  withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
    // read the state of dockable editors
    fileEditorManager.initDockableContentFactory()

    frameHelper.postInit()
  }

  project.getUserData(ProjectImpl.CREATION_TIME)?.let { startTime ->
    LifecycleUsageTriggerCollector.onProjectOpenFinished(project, TimeoutUtil.getDurationMillis(startTime), frameHelper.isTabbedWindow)
  }

  // check after `initDockableContentFactory` - editor in a docked window
  if (!fileEditorManager.hasOpenFiles()) {
    stopOpenFilesActivity(project)
    if (!isNotificationSilentMode(project)) {
      openProjectViewIfNeeded(project, toolWindowInitJob)
      findAndOpenReadmeIfNeeded(project)
    }
    FUSProjectHotStartUpMeasurer.reportNoMoreEditorsOnStartup(System.nanoTime())
  }
}

private suspend fun focusSelectedEditor(editorComponent: EditorsSplitters) {
  val composite = editorComponent.currentWindow?.selectedComposite ?: return
  // TODO: this check for JB Client is made to keep the same behaviour in monolith,
  //   but in 253 we may remove this check and see what may be broken with async editor focus
  if (!PlatformUtils.isJetBrainsClient()) {
    // let's focus the editor synchronously in local mode
    composite.waitForAvailable()
    focusSelectedEditorInComposite(composite)
  }
  else {
    // in Remote Dev we cannot wait for composite availability synchronously,
    // since editors come from the backend and this is a too long process
    composite.coroutineScope.launch(Dispatchers.EDT + FUSProjectHotStartUpMeasurer.getContextElementToPass()) {
      composite.waitForAvailable()
      focusSelectedEditorInComposite(composite)
    }
  }
}

private suspend fun focusSelectedEditorInComposite(composite: EditorComposite) {
  val textEditor = composite.selectedEditor as? TextEditor
  if (textEditor == null) {
    FUSProjectHotStartUpMeasurer.firstOpenedUnknownEditor(composite.file, System.nanoTime())
    composite.preferredFocusedComponent?.requestFocusInWindow()
  }
  else {
    AsyncEditorLoader.performWhenLoaded(textEditor.editor) {
      FUSProjectHotStartUpMeasurer.firstOpenedEditor(composite.file, composite.project)
      composite.preferredFocusedComponent?.requestFocusInWindow()
    }
  }
}

internal fun applyBoundsOrDefault(frame: JFrame, bounds: Rectangle?, restoreOnlyLocation: Boolean = false) {
  if (bounds == null) {
    setDefaultSize(frame)
    frame.setLocationRelativeTo(null)
  }
  else {
    if (restoreOnlyLocation) {
      frame.location = bounds.location
      // we need to guarantee that the size is smaller than this screen to be able to maximize the frame after this
      setDefaultSize(frame, ScreenUtil.getScreenRectangle(bounds.location))
    }
    else {
      frame.bounds = bounds
    }
  }
}

private fun setDefaultSize(frame: JFrame, screen: Rectangle = ScreenUtil.getMainScreenBounds()) {
  val size = screen.size
  size.width = min(1400, size.width - 20)
  size.height = min(1000, size.height - 40)
  frame.size = size
  frame.minimumSize = Dimension(340, frame.minimumSize.height)
}

@ApiStatus.Internal
fun createIdeFrame(frameInfo: FrameInfo): IdeFrameImpl {
  val deviceBounds = frameInfo.bounds
  if (deviceBounds == null) {
    val frame = IdeFrameImpl()
    setDefaultSize(frame)
    frame.setLocationRelativeTo(null)
    return frame
  }
  else {
    checkForNonsenseBounds("IdeProjectFrameAllocatorKt.createNewProjectFrameProducer.deviceBounds", deviceBounds)
    val bounds = FrameBoundsConverter.convertFromDeviceSpaceAndFitToScreen(deviceBounds)
    val state = frameInfo.extendedState
    val isMaximized = FrameInfoHelper.isMaximized(state)
    val frame = IdeFrameImpl()
    val restoreNormalBounds = isMaximized && frame.extendedState == Frame.NORMAL && bounds != null

    // On macOS, setExtendedState(maximized) may UN-maximize the frame if the restored bounds are too large
    // (so the OS will "autodetect" it as already maximized).
    // Therefore, we only restore the location and use the default size (which is always computed to be less than the screen).
    applyBoundsOrDefault(frame, bounds, restoreOnlyLocation = isMaximized && SystemInfo.isMac)
    frame.extendedState = state
    frame.minimumSize = Dimension(340, frame.minimumSize.height)

    // This has to be done after restoring the actual state, as otherwise setExtendedState() may overwrite the normal bounds.
    if (restoreNormalBounds) {
      frame.normalBounds = bounds
      frame.screenBounds = ScreenUtil.getScreenDevice(bounds)?.defaultConfiguration?.bounds
      if (IDE_FRAME_EVENT_LOG.isDebugEnabled) { // avoid unnecessary concatenation
        IDE_FRAME_EVENT_LOG.debug("Loaded saved normal bounds ${frame.normalBounds} for the screen ${frame.screenBounds}")
      }
    }
    return frame
  }
}

private suspend fun openProjectViewIfNeeded(project: Project, toolWindowInitJob: Job) {
  if (!serviceAsync<RegistryManager>().`is`("ide.open.project.view.on.startup")) {
    return
  }

  toolWindowInitJob.join()

  // todo should we use `runOnceForProject(project, "OpenProjectViewOnStart")` or not?
  val toolWindowManager = project.serviceAsync<ToolWindowManager>()
  withContext(Dispatchers.ui(CoroutineSupport.UiDispatcherKind.STRICT)) {
    if (toolWindowManager.activeToolWindowId == null) {
      val toolWindow = toolWindowManager.getToolWindow("Project")
      if (toolWindow != null) {
        // maybe readAction
        withContext(Dispatchers.UiWithModelAccess) {
          toolWindow.activate(null, !AppMode.isRemoteDevHost())
        }
      }
    }
  }
}

private suspend fun findAndOpenReadmeIfNeeded(project: Project) {
  if (!AdvancedSettings.getBoolean("ide.open.readme.md.on.startup")) {
    return
  }

  runOnceForProject(project = project, id = "ShowReadmeOnStart") {
    val projectDir = project.guessProjectDir() ?: return@runOnceForProject
    val files = mutableListOf(".github/README.md", "README.md", "docs/README.md")
    if (SystemInfoRt.isFileSystemCaseSensitive) {
      files.addAll(files.map { it.lowercase() })
    }
    val readme = files.firstNotNullOfOrNull(projectDir::findFileByRelativePath) ?: return@runOnceForProject
    if (!readme.isDirectory) {
      readme.putUserData(TextEditorWithPreview.DEFAULT_LAYOUT_FOR_FILE, TextEditorWithPreview.Layout.SHOW_PREVIEW)
      (project.serviceAsync<FileEditorManager>() as FileEditorManagerEx).openFile(readme, FileEditorOpenOptions(requestFocus = true))

      readme.putUserData(README_OPENED_ON_START_TS, Instant.now())
      FUSProjectHotStartUpMeasurer.openedReadme(readme, System.nanoTime())
    }
  }
}

private class MutableLoadingState(override val done: Job) : FrameLoadingState