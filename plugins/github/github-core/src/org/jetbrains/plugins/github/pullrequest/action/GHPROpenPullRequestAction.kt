// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.github.pullrequest.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import org.jetbrains.plugins.github.i18n.GithubBundle

private class GHPROpenPullRequestAction : DumbAwareAction(GithubBundle.messagePointer("pull.request.open.action"),
                                                  GithubBundle.messagePointer("pull.request.open.action.description")) {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val vm = e.getData(GHPRActionKeys.PULL_REQUESTS_CONNECTED_PROJECT_VM)
    val id = e.getData(GHPRActionKeys.PULL_REQUEST_ID)

    e.presentation.isEnabledAndVisible = vm != null && id != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val vm = e.getData(GHPRActionKeys.PULL_REQUESTS_CONNECTED_PROJECT_VM) ?: return
    val id = e.getData(GHPRActionKeys.PULL_REQUEST_ID) ?: return

    vm.viewPullRequest(id)
    vm.openPullRequestTimeline(id, false)
  }
}