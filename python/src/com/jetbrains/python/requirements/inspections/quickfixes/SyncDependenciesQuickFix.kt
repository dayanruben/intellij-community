// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.requirements.inspections.quickfixes

import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.jetbrains.python.PyBundle
import com.jetbrains.python.packaging.management.PythonPackageManager
import com.jetbrains.python.packaging.management.ui.PythonPackageManagerUI
import com.jetbrains.python.packaging.utils.PyPackageCoroutine
import com.jetbrains.python.requirements.getPythonSdk

/**
 * Quick-fix that runs the package manager's `sync` command — `poetry install --no-root`,
 * `uv sync`, … — instead of installing requirements one by
 * one. Used as the sole "fix" for `[project].dependencies` problems in `pyproject.toml`: when
 * the lock / manifest is the source of truth, the right action is to ask the manager to
 * reconcile the env with the manifest, not to feed individual package names through
 * `pip install`. The per-item `Install <pkg>` shortcut would otherwise call `poetry add` /
 * `uv add` / `pip install`, which on top of being slower can also rewrite the manifest with a
 * stricter version constraint than the user typed.
 *
 * The label text reuses [PyBundle]'s `QFIX.NAME.install.all.requirements` so users see the
 * same "Install all missing packages" they'd see for a `requirements.txt` file — the
 * underlying mechanism (sync vs. install-list) is an implementation detail of how the
 * specific package manager satisfies that intent.
 */
internal class SyncDependenciesQuickFix : LocalQuickFix, PriorityAction {
  override fun getFamilyName(): String = PyBundle.message("QFIX.NAME.install.all.requirements")

  override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.HIGH

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val sdk = getPythonSdk(descriptor.psiElement.containingFile) ?: return
    PyPackageCoroutine.launch(project) {
      // Route through PythonPackageManagerUI so the run gets the standard serialized background-progress
      // wrapper plus error-sink reporting; otherwise a sync failure (e.g. `poetry lock` is
      // out of sync) would surface only via logger.warn and the user would see no feedback.
      val pmUI = PythonPackageManagerUI.forSdk(project, sdk)
      pmUI.executeCommand(PyBundle.message("python.packaging.installing.packages")) {
        PythonPackageManager.forSdk(project, sdk).sync().mapSuccess { }
      }
    }
  }

  override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
    IntentionPreviewInfo.EMPTY

  override fun startInWriteAction(): Boolean = false
}
