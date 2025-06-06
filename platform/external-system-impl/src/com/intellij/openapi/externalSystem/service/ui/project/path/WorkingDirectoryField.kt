// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.externalSystem.service.ui.project.path

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.externalSystem.service.ui.completion.TextCompletionField
import com.intellij.openapi.externalSystem.service.ui.completion.TextCompletionInfo
import com.intellij.openapi.externalSystem.service.ui.completion.TextCompletionInfoRenderer
import com.intellij.openapi.externalSystem.service.ui.completion.collector.TextCompletionCollector
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemBundle
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.observable.util.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.*
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.RecursionGuard
import com.intellij.openapi.util.RecursionManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.isFile
import com.intellij.openapi.vfs.refreshAndFindVirtualFileOrDirectory
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import javax.swing.plaf.basic.BasicTextUI
import javax.swing.text.DefaultHighlighter.DefaultHighlightPainter
import javax.swing.text.Highlighter

class WorkingDirectoryField(
  project: Project,
  workingDirectoryInfo: WorkingDirectoryInfo,
  parentDisposable: Disposable
) : TextCompletionField<TextCompletionInfo>(project) {

  private val propertyGraph = PropertyGraph(isBlockPropagation = false)
  private val modeProperty = propertyGraph.property(Mode.NAME)
  private val workingDirectoryProperty = propertyGraph.property("")
  private val projectNameProperty = propertyGraph.property("")

  var mode by modeProperty
  var workingDirectory by workingDirectoryProperty
  var projectName by projectNameProperty

  private var highlightTag: Any? = null
  private val highlightRecursionGuard: RecursionGuard<WorkingDirectoryField> =
    RecursionManager.createGuard(WorkingDirectoryField::class.java.name)

  private val externalProjectModificationTracker: ModificationTracker =
    workingDirectoryInfo.externalProjectModificationTracker +
    modeProperty.createPropertyModificationTracker(parentDisposable)

  private val externalProjectCollector = AsyncExternalProjectCollector.create(externalProjectModificationTracker) {
    workingDirectoryInfo.collectExternalProjects()
  }

  private fun getExternalProjects(): List<ExternalProject> {
    val owner = ModalTaskOwner.component(this)
    val title = ExternalSystemBundle.message("working.directory.filed.projects.collecting")
    return runWithModalProgressBlocking(owner, title) {
      externalProjectCollector.getOrCollectExternalProjects()
    }
  }

  override val completionCollector = TextCompletionCollector.async(externalProjectModificationTracker, parentDisposable) {
    val externalProjects = externalProjectCollector.getOrCollectExternalProjects()
    when (mode) {
      Mode.NAME -> {
        externalProjects
          .map { it.name }
          .map { TextCompletionInfo(it) }
      }
      Mode.PATH -> {
        val textToComplete = getTextToComplete()
        val pathToComplete = getCanonicalPath(textToComplete, removeLastSlash = false)
        externalProjects
          .filter { it.path.startsWith(pathToComplete) }
          .map { it.path.substring(pathToComplete.length) }
          .map { textToComplete + FileUtil.toSystemDependentName(it) }
          .map { TextCompletionInfo(it) }
      }
    }
  }

  fun getWorkingDirectoryVirtualFile(): VirtualFile? {
    val directoryPath = workingDirectory.toNioPathOrNull() ?: return null
    val directoryOrFile = directoryPath.refreshAndFindVirtualFileOrDirectory() ?: return null
    return if (directoryOrFile.isFile) null else directoryOrFile
  }

  init {
    renderer = TextCompletionInfoRenderer()
    completionType = CompletionType.REPLACE_TEXT
  }

  init {
    val textProperty = propertyGraph.property("")
    val text by textProperty.trim()
    workingDirectoryProperty.dependsOn(textProperty) {
      when (mode) {
        Mode.PATH -> getCanonicalPath(text)
        Mode.NAME -> resolveProjectPathByName(text) ?: text
      }
    }
    projectNameProperty.dependsOn(textProperty) {
      when (mode) {
        Mode.PATH -> resolveProjectNameByPath(getCanonicalPath(text)) ?: text
        Mode.NAME -> text
      }
    }
    textProperty.dependsOn(modeProperty) {
      when (mode) {
        Mode.PATH -> getPresentablePath(workingDirectory)
        Mode.NAME -> projectName
      }
    }
    textProperty.dependsOn(workingDirectoryProperty) {
      when (mode) {
        Mode.PATH -> getPresentablePath(workingDirectory)
        Mode.NAME -> resolveProjectNameByPath(workingDirectory) ?: getPresentablePath(workingDirectory)
      }
    }
    textProperty.dependsOn(projectNameProperty) {
      when (mode) {
        Mode.PATH -> resolveProjectPathByName(projectName) ?: projectName
        Mode.NAME -> projectName
      }
    }
    modeProperty.dependsOn(workingDirectoryProperty) {
      when {
        workingDirectory.isEmpty() -> Mode.NAME
        resolveProjectNameByPath(workingDirectory) != null -> mode
        else -> Mode.PATH
      }
    }
    modeProperty.dependsOn(projectNameProperty) {
      when {
        projectName.isEmpty() -> Mode.NAME
        resolveProjectPathByName(projectName) != null -> mode
        else -> Mode.PATH
      }
    }
    bind(textProperty)
  }

  private fun resolveProjectPathByName(projectName: String): String? {
    return resolveValueByKey(projectName, getExternalProjects(), { name }, { path })
  }

  private fun resolveProjectNameByPath(workingDirectory: String): String? {
    return resolveValueByKey(workingDirectory, getExternalProjects(), { path }, { name })
  }

  private fun <E> resolveValueByKey(
    key: String,
    entries: List<E>,
    getKey: E.() -> String,
    getValue: E.() -> String
  ): String? {
    if (key.isNotEmpty()) {
      val entry = entries.asSequence()
        .filter { it.getKey().startsWith(key) }
        .sortedBy { it.getKey().length }
        .firstOrNull()
      if (entry != null) {
        val suffix = entry.getKey().removePrefix(key)
        if (entry.getValue().endsWith(suffix)) {
          return entry.getValue().removeSuffix(suffix)
        }
      }
      val parentEntry = entries.asSequence()
        .filter { key.startsWith(it.getKey()) }
        .sortedByDescending { it.getKey().length }
        .firstOrNull()
      if (parentEntry != null) {
        val suffix = key.removePrefix(parentEntry.getKey())
        return parentEntry.getValue() + suffix
      }
    }
    return null
  }

  init {
    whenMousePressed {
      if (isTextUnderMouse(it)) {
        mode = Mode.PATH
      }
    }
    addKeyboardAction(getKeyStrokes("CollapseRegion", "CollapseRegionRecursively", "CollapseAllRegions")) {
      mode = Mode.NAME
    }
    addKeyboardAction(getKeyStrokes("ExpandRegion", "ExpandRegionRecursively", "ExpandAllRegions")) {
      mode = Mode.PATH
    }
  }

  init {
    addHighlighterListener {
      updateHighlight()
    }
    whenTextChanged {
      updateHighlight()
    }
    modeProperty.afterChange {
      updateHighlight()
    }
    updateHighlight()
  }

  private fun updateHighlight() {
    highlightRecursionGuard.doPreventingRecursion(this, false) {
      if (highlightTag != null) {
        highlighter.removeHighlight(highlightTag)
        foreground = null
      }
      if (mode == Mode.NAME) {
        val textAttributes = EditorColors.FOLDED_TEXT_ATTRIBUTES.defaultAttributes
        val painter = DefaultHighlightPainter(textAttributes.backgroundColor)
        highlightTag = highlighter.addHighlight(0, text.length, painter)
        foreground = textAttributes.foregroundColor
      }
    }
  }

  private fun addHighlighterListener(listener: () -> Unit) {
    highlighter = object : BasicTextUI.BasicHighlighter() {
      override fun changeHighlight(tag: Any, p0: Int, p1: Int) =
        super.changeHighlight(tag, p0, p1)
          .also { listener() }

      override fun removeHighlight(tag: Any) =
        super.removeHighlight(tag)
          .also { listener() }

      override fun removeAllHighlights() =
        super.removeAllHighlights()
          .also { listener() }

      override fun addHighlight(p0: Int, p1: Int, p: Highlighter.HighlightPainter) =
        super.addHighlight(p0, p1, p)
          .also { listener() }
    }
  }

  init {
    val fileBrowseAccessor = object : TextComponentAccessor<WorkingDirectoryField> {
      override fun getText(component: WorkingDirectoryField) = workingDirectory
      override fun setText(component: WorkingDirectoryField, text: String) {
        workingDirectory = text
      }
    }
    val browseFolderRunnable = object : BrowseFolderRunnable<WorkingDirectoryField>(
      project,
      workingDirectoryInfo.fileChooserDescriptor,
      this,
      fileBrowseAccessor
    ) {
      override fun chosenFileToResultingText(chosenFile: VirtualFile): String {
        return ExternalSystemApiUtil.getLocalFileSystemPath(chosenFile)
      }
    }
    addBrowseExtension(browseFolderRunnable, null)
  }

  init {
    modeProperty.afterChange {
      updatePopup(UpdatePopupType.SHOW_IF_HAS_VARIANCES)
    }
  }

  enum class Mode { PATH, NAME }
}
