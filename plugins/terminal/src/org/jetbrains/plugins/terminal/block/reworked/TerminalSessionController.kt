// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal.block.reworked

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import com.intellij.util.EventDispatcher
import com.intellij.util.containers.DisposableWrapperList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import org.jetbrains.plugins.terminal.block.reworked.session.output.*
import java.awt.Toolkit
import kotlin.coroutines.cancellation.CancellationException

internal class TerminalSessionController(
  private val sessionModel: TerminalSessionModel,
  private val outputModel: TerminalOutputModel,
  private val alternateBufferModel: TerminalOutputModel,
  private val blocksModel: TerminalBlocksModel,
  private val settings: JBTerminalSystemSettingsProviderBase,
  private val coroutineScope: CoroutineScope,
) {

  private val terminationListeners: DisposableWrapperList<Runnable> = DisposableWrapperList()
  private val shellIntegrationEventDispatcher: EventDispatcher<TerminalShellIntegrationEventsListener> =
    EventDispatcher.create(TerminalShellIntegrationEventsListener::class.java)

  fun handleEvents(channel: ReceiveChannel<List<TerminalOutputEvent>>) {
    coroutineScope.launch {
      doHandleEvents(channel)
    }.invokeOnCompletion {
      fireSessionTerminated()
    }
  }

  private suspend fun doHandleEvents(channel: ReceiveChannel<List<TerminalOutputEvent>>) {
    for (events in channel) {
      for (event in events) {
        try {
          handleEvent(event)
        }
        catch (e: CancellationException) {
          throw e
        }
        catch (t: Throwable) {
          thisLogger().error(t)
        }
      }
    }
  }

  private suspend fun handleEvent(event: TerminalOutputEvent) {
    when (event) {
      is TerminalContentUpdatedEvent -> {
        updateOutputModel { model ->
          model.updateContent(event.startLineLogicalIndex, event.text, event.styles)
        }
      }
      is TerminalCursorPositionChangedEvent -> {
        updateOutputModel { model ->
          model.updateCursorPosition(event.logicalLineIndex, event.columnIndex)
        }
      }
      is TerminalStateChangedEvent -> {
        val state = event.state.toTerminalState(settings.cursorShape)
        sessionModel.updateTerminalState(state)
      }
      is TerminalBeepEvent -> {
        if (settings.audibleBell()) {
          Toolkit.getDefaultToolkit().beep()
        }
      }
      TerminalShellIntegrationInitializedEvent -> {
        // TODO
        shellIntegrationEventDispatcher.multicaster.initialized()
      }
      TerminalPromptStartedEvent -> {
        withContext(Dispatchers.EDT) {
          blocksModel.promptStarted(outputModel.cursorOffsetState.value)
        }
        shellIntegrationEventDispatcher.multicaster.promptStarted()
      }
      TerminalPromptFinishedEvent -> {
        withContext(Dispatchers.EDT) {
          blocksModel.promptFinished(outputModel.cursorOffsetState.value)
        }
        shellIntegrationEventDispatcher.multicaster.promptFinished()
      }
      is TerminalCommandStartedEvent -> {
        withContext(Dispatchers.EDT) {
          blocksModel.commandStarted(outputModel.cursorOffsetState.value)
        }
        shellIntegrationEventDispatcher.multicaster.commandStarted(event.command)
      }
      is TerminalCommandFinishedEvent -> {
        withContext(Dispatchers.EDT) {
          blocksModel.commandFinished(event.exitCode)
        }
        shellIntegrationEventDispatcher.multicaster.commandFinished(event.command, event.exitCode)
      }
    }
  }

  private suspend fun updateOutputModel(block: (TerminalOutputModel) -> Unit) {
    withContext(Dispatchers.EDT) {
      block(getCurrentOutputModel())
    }
  }

  private fun getCurrentOutputModel(): TerminalOutputModel {
    return if (sessionModel.terminalState.value.isAlternateScreenBuffer) alternateBufferModel else outputModel
  }

  fun addTerminationCallback(onTerminated: Runnable, parentDisposable: Disposable) {
    terminationListeners.add(onTerminated, parentDisposable)
  }

  private fun fireSessionTerminated() {
    for (listener in terminationListeners) {
      try {
        listener.run()
      }
      catch (t: Throwable) {
        thisLogger().error("Unhandled exception in termination listener", t)
      }
    }
  }

  fun addShellIntegrationListener(parentDisposable: Disposable, listener: TerminalShellIntegrationEventsListener) {
    shellIntegrationEventDispatcher.addListener(listener, parentDisposable)
  }
}