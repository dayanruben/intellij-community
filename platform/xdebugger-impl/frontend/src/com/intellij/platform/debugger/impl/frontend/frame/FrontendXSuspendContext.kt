// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.debugger.impl.frontend.frame

import com.intellij.openapi.project.Project
import com.intellij.platform.debugger.impl.frontend.evaluate.quick.childCoroutineScope
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.impl.rpc.XDebugSessionApi
import com.intellij.xdebugger.impl.rpc.XExecutionStacksEvent
import com.intellij.xdebugger.impl.rpc.XSuspendContextDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class FrontendXSuspendContext(
  private val suspendContextDto: XSuspendContextDto,
  private val project: Project,
  private val cs: CoroutineScope,
) : XSuspendContext() {
  private val id = suspendContextDto.id

  val isStepping: Boolean
    get() = suspendContextDto.isStepping

  internal var activeExecutionStack: FrontendXExecutionStack? = null

  override fun getActiveExecutionStack(): XExecutionStack? {
    return activeExecutionStack
  }

  override fun computeExecutionStacks(container: XExecutionStackContainer) {
    container.childCoroutineScope(cs, "FrontendXSuspendContext#computeExecutionStacks").launch {
      val executionStacksCs = this
      XDebugSessionApi.getInstance().computeExecutionStacks(id).collect { executionStackEvent ->
        when (executionStackEvent) {
          is XExecutionStacksEvent.ErrorOccurred -> {
            container.errorOccurred(executionStackEvent.errorMessage)
          }
          is XExecutionStacksEvent.NewExecutionStacks -> {
            val feStacks = executionStackEvent.stacks.map { FrontendXExecutionStack(it, project, executionStacksCs) }
            container.addExecutionStack(feStacks, executionStackEvent.last)
          }
        }
      }
    }
  }
}