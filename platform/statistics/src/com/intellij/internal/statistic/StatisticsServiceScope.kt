// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.internal.statistic

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.plus
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
object StatisticsServiceScope {
  fun getScope(project: Project): CoroutineScope = project.service<StatisticsServiceProjectScope>().scope
  fun getScope(): CoroutineScope = service<StatisticsServiceApplicationScope>().scope
}

@Service(Service.Level.APP)
internal class StatisticsServiceApplicationScope(providedScope: CoroutineScope) {
  val scope: CoroutineScope = providedScope + Dispatchers.IO
}

@Service(Service.Level.PROJECT)
internal class StatisticsServiceProjectScope(providedScope: CoroutineScope) {
  val scope: CoroutineScope = providedScope + Dispatchers.IO
}