// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.inspections.quickfix;

import com.intellij.codeInsight.daemon.impl.actions.AbstractBatchSuppressByNoInspectionCommentFix;
import com.intellij.codeInspection.util.IntentionName;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.psi.PyElement;
import org.jetbrains.annotations.NotNull;


public class PySuppressInspectionFix extends AbstractBatchSuppressByNoInspectionCommentFix {
  private final Class<? extends PyElement> myContainerClass;

  public PySuppressInspectionFix(final String ID,
                                 final @IntentionName @NotNull String text,
                                 final Class<? extends PyElement> containerClass) {
    super(ID, false);
    setText(text);
    myContainerClass = containerClass;
  }

  @Override
  public PsiElement getContainer(PsiElement context) {
    return PsiTreeUtil.getParentOfType(context, myContainerClass);
  }
}
