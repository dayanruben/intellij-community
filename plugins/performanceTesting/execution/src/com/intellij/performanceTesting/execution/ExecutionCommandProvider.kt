// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.performanceTesting.execution

import com.jetbrains.performancePlugin.CommandProvider
import com.jetbrains.performancePlugin.CreateCommand

internal class ExecutionCommandProvider : CommandProvider {
  override fun getCommands(): Map<String, CreateCommand> = mapOf(
    RunConfigurationCommand.PREFIX to CreateCommand(::RunConfigurationCommand),
  )

  override fun shouldDelegateToBackend(): Boolean {
    return true
  }
}
