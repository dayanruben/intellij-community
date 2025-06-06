// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.actions;

import com.intellij.dvcs.branch.DvcsBranchUtil;
import com.intellij.ide.ui.ToolbarSettings;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import git4idea.branch.GitBranchUtil;
import git4idea.i18n.GitBundle;
import git4idea.repo.GitRepository;
import git4idea.ui.branch.BranchIconUtil;
import git4idea.ui.branch.popup.GitBranchesTreePopupOnBackend;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Objects;

final class GitBranchesComboBoxAction extends ComboBoxAction implements DumbAware {
  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    var project = e.getProject();
    Presentation presentation = e.getPresentation();
    if (project == null || project.isDisposed() || !project.isOpen()) {
      presentation.setEnabledAndVisible(false);
      return;
    }
    GitRepository repo = GitBranchUtil.guessWidgetRepository(project, e.getDataContext());
    if (repo == null) {
      presentation.setEnabledAndVisible(false);
      return;
    }
    if (!ToolbarSettings.getInstance().isAvailable()) {
      presentation.setEnabledAndVisible(false);
      return;
    }

    String branchName = repo.getCurrentRevision() != null ? GitBranchUtil.getDisplayableBranchText(repo)
                                                          : GitBundle.message("no.revisions.available");
    String name = DvcsBranchUtil.shortenBranchName(branchName);
    presentation.setText(name, false);
    presentation.setIcon(BranchIconUtil.Companion.getBranchIcon(repo));
    presentation.setEnabledAndVisible(true);
    presentation.setDescription(GitBundle.messagePointer("action.Git.ShowBranches.pretty.description").get());
  }

  @Override
  protected @NotNull JBPopup createActionPopup(@NotNull DataContext context,
                                                 @NotNull JComponent component,
                                                 @Nullable Runnable disposeCallback) {
    Project project = Objects.requireNonNull(context.getData(CommonDataKeys.PROJECT));
    GitRepository repo = Objects.requireNonNull(GitBranchUtil.guessWidgetRepository(project, context));

    JBPopup popup = GitBranchesTreePopupOnBackend.create(project, repo);
    popup.addListener(new JBPopupListener() {
      @Override
      public void onClosed(@NotNull LightweightWindowEvent event) {
        if (disposeCallback != null) {
          disposeCallback.run();
        }
      }
    });
    return popup;
  }
}
