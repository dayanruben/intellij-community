// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vcs.changes.committed;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.vcs.*;
import com.intellij.util.NotNullFunction;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;


@ApiStatus.Internal
public class SelectFilteringAction extends LabeledComboBoxAction implements DumbAware {

  private final @NotNull Project myProject;
  private final @NotNull CommittedChangesTreeBrowser myBrowser;
  private @NotNull ChangeListFilteringStrategy myPreviousSelection = NoneChangeListFilteringStrategy.INSTANCE;

  public SelectFilteringAction(@NotNull Project project, @NotNull CommittedChangesTreeBrowser browser) {
    super(VcsBundle.message("committed.changes.filter.title"));
    myProject = project;
    myBrowser = browser;
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setText(myPreviousSelection.toString());
  }

  @Override
  protected @NotNull DefaultActionGroup createPopupActionGroup(@NotNull JComponent button, @NotNull DataContext context) {
    return new DefaultActionGroup(ContainerUtil.map(collectStrategies(),
                                                    (NotNullFunction<ChangeListFilteringStrategy, AnAction>)strategy -> new SetFilteringAction(strategy)));
  }

  @Override
  protected @NotNull Condition<AnAction> getPreselectCondition() {
    return action -> ((SetFilteringAction)action).myStrategy.getKey().equals(myPreviousSelection.getKey());
  }

  private @NotNull List<ChangeListFilteringStrategy> collectStrategies() {
    List<ChangeListFilteringStrategy> result = new ArrayList<>();

    result.add(NoneChangeListFilteringStrategy.INSTANCE);
    result.add(new StructureFilteringStrategy(myProject));

    boolean addNameFilter = false;
    for (AbstractVcs vcs : ProjectLevelVcsManager.getInstance(myProject).getAllActiveVcss()) {
      CommittedChangesProvider provider = vcs.getCommittedChangesProvider();

      if (provider != null) {
        addNameFilter = true;

        for (ChangeListColumn column : provider.getColumns()) {
          if (ChangeListColumn.isCustom(column)) {
            result.add(new ColumnFilteringStrategy(column, provider.getClass()));
          }
        }
      }
    }
    if (addNameFilter) {
      result.add(new ColumnFilteringStrategy(ChangeListColumn.NAME, CommittedChangesProvider.class));
    }

    return result;
  }

  private final class SetFilteringAction extends DumbAwareAction {

    private final @NotNull ChangeListFilteringStrategy myStrategy;

    private SetFilteringAction(@NotNull ChangeListFilteringStrategy strategy) {
      super(strategy.toString());
      myStrategy = strategy;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      if (!NoneChangeListFilteringStrategy.INSTANCE.equals(myPreviousSelection)) {
        myBrowser.removeFilteringStrategy(myPreviousSelection.getKey());
      }
      if (!NoneChangeListFilteringStrategy.INSTANCE.equals(myStrategy)) {
        myBrowser.setFilteringStrategy(myStrategy);
      }
      myPreviousSelection = myStrategy;
    }
  }
}
