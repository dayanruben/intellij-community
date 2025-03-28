// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.searchEverywhere.frontend.tabs.all

import com.intellij.ide.IdeBundle
import com.intellij.openapi.options.ObservableOptionEditor
import com.intellij.openapi.util.Disposer
import com.intellij.platform.searchEverywhere.SeFilterState
import com.intellij.platform.searchEverywhere.SeItemData
import com.intellij.platform.searchEverywhere.SeParams
import com.intellij.platform.searchEverywhere.SeResultEvent
import com.intellij.platform.searchEverywhere.frontend.SeTab
import com.intellij.platform.searchEverywhere.frontend.resultsProcessing.SeTabDelegate
import kotlinx.coroutines.flow.Flow
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
class SeAllTab(private val delegate: SeTabDelegate): SeTab {
  override val name: String
    get() = IdeBundle.message("searcheverywhere.allelements.tab.name")

  override val shortName: String
    get() = name

  override fun getItems(params: SeParams): Flow<SeResultEvent> = delegate.getItems(params)
  override fun getFilterEditor(): ObservableOptionEditor<SeFilterState>? = null

  override suspend fun itemSelected(item: SeItemData, modifiers: Int, searchText: String): Boolean {
    return delegate.itemSelected(item, modifiers, searchText)
  }

  override fun dispose() {
    Disposer.dispose(delegate)
  }
}

