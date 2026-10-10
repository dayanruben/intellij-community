// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package com.jetbrains.python.codeInsight.intentions;


import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;

public abstract class PyBaseIntentionAction extends BaseIntentionAction {

  @Override
  public final void invoke(@NotNull Project project, Editor editor, PsiFile psiFile) throws IncorrectOperationException {
    doInvoke(project, editor, psiFile);
  }

  public abstract void doInvoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException;
}
