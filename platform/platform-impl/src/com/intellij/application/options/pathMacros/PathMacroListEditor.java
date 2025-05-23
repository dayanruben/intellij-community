// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.application.options.pathMacros;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathMacros;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.AnActionButtonRunnable;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.util.text.StringTokenizer;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class PathMacroListEditor {

  private final PathMacroListEditorUI ui;
  private final PathMacroTable myPathMacroTable;

  public PathMacroListEditor() {
    this(null);
  }

  public PathMacroListEditor(final Collection<String> undefinedMacroNames) {
    myPathMacroTable = undefinedMacroNames != null ? new PathMacroTable(undefinedMacroNames) : new PathMacroTable();
    ui = new PathMacroListEditorUI(
      ToolbarDecorator.createDecorator(myPathMacroTable)
        .setAddAction(new AnActionButtonRunnable() {
          @Override
          public void run(AnActionButton button) {
            myPathMacroTable.addMacro();
          }
        }).setRemoveAction(new AnActionButtonRunnable() {
          @Override
          public void run(AnActionButton button) {
            myPathMacroTable.removeSelectedMacros();
          }
        }).setEditAction(new AnActionButtonRunnable() {
          @Override
          public void run(AnActionButton button) {
            myPathMacroTable.editMacro();
          }
        }).disableUpDownActions().createPanel());

    fillIgnoredVariables();
  }

  private void fillIgnoredVariables() {
    final Collection<String> ignored = PathMacros.getInstance().getIgnoredMacroNames();
    ui.ignoredVariables.setText(StringUtil.join(ignored, ";"));
  }

  private boolean isIgnoredModified() {
    final Collection<String> ignored = PathMacros.getInstance().getIgnoredMacroNames();
    return !parseIgnoredVariables().equals(ignored);
  }

  private Collection<String> parseIgnoredVariables() {
    final String s = ui.ignoredVariables.getText();
    final List<String> ignored = new ArrayList<>();
    final StringTokenizer st = new StringTokenizer(s, ";");
    while (st.hasMoreElements()) {
      ignored.add(st.nextElement().trim());
    }

    return ignored;
  }

  public void commit() throws ConfigurationException {
    ApplicationManager.getApplication().runWriteAction(() -> {
      myPathMacroTable.commit();

      final Collection<String> ignored = parseIgnoredVariables();
      final PathMacros instance = PathMacros.getInstance();
      instance.setIgnoredMacroNames(ignored);
    });
  }

  public JComponent getPanel() {
    return ui.getContent();
  }

  public void reset() {
    myPathMacroTable.reset();
    fillIgnoredVariables();
  }

  public boolean isModified() {
    return myPathMacroTable.isModified() || isIgnoredModified();
  }

  private void createUIComponents() {
  }
}
