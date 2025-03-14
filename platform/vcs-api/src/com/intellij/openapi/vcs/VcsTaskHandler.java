// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vcs;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

public abstract class VcsTaskHandler {

  private static final String DEFAULT_PROHIBITED_SYMBOLS = " ";

  public static VcsTaskHandler[] getAllHandlers(final Project project) {
    VcsTaskHandler[] extensions = EXTENSION_POINT_NAME.getExtensions(project);
    List<VcsTaskHandler> handlers = ContainerUtil.filter(extensions, handler -> handler.isEnabled());
    return handlers.toArray(new VcsTaskHandler[0]);
  }

  public static class TaskInfo implements Comparable<TaskInfo> {

    private final String myBranch;
    private final Collection<String> myRepositories;
    private final boolean myRemote;

    public TaskInfo(String branch, Collection<String> repositories) {
      this(branch, repositories, false);
    }

    public TaskInfo(String branch, Collection<String> repositories, boolean remote) {
      myBranch = branch;
      myRepositories = repositories;
      myRemote = remote;
    }

    public String getName() {
      return myBranch;
    }

    public boolean isRemote() { return myRemote; }

    public Collection<String> getRepositories() {
      return myRepositories;
    }

    @Override
    public String toString() {
      return getName();
    }

    @Override
    public int compareTo(TaskInfo o) {
      return Comparing.compare(myBranch, o.myBranch);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      TaskInfo info = (TaskInfo)o;

      if (myRemote != info.myRemote) return false;
      if (!myBranch.equals(info.myBranch)) return false;
      return myRepositories.size() == info.myRepositories.size() && myRepositories.containsAll(info.myRepositories);
    }

    @Override
    public int hashCode() {
      return myBranch.hashCode();
    }
  }

  private static final ExtensionPointName<VcsTaskHandler> EXTENSION_POINT_NAME = ExtensionPointName.create("com.intellij.vcs.taskHandler");

  public abstract boolean isEnabled();

  public abstract TaskInfo startNewTask(@NotNull String taskName);

  public abstract boolean switchToTask(TaskInfo taskInfo, @Nullable Runnable invokeAfter);

  public abstract void closeTask(@NotNull TaskInfo taskInfo, @NotNull TaskInfo original);

  public abstract boolean isSyncEnabled();

  /**
   * @return currently active (checked out) tasks (branches)
   */
  public abstract TaskInfo @NotNull [] getCurrentTasks();

  /**
   * @return all existing tasks (branches)
   */
  public abstract TaskInfo[] getAllExistingTasks();

  /**
   * Should check prohibited symbols and constructions; name ref conflicts depended on Repository will be checked separately if needed
   *
   * @param branchName to check
   * @return true if valid
   */
  public boolean isBranchNameValid(@NotNull String branchName) {
    return !branchName.contains(DEFAULT_PROHIBITED_SYMBOLS);
  }

  /**
   * Update branchName to valid
   *
   * @param suggestedName suggested name
   * @return new valid branchName
   */
  public @NotNull String cleanUpBranchName(@NotNull String suggestedName) {
    return suggestedName.replaceAll(DEFAULT_PROHIBITED_SYMBOLS, "-");
  }
}
