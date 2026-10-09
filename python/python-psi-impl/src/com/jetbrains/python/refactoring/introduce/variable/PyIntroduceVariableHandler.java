// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.refactoring.introduce.variable;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.PyPsiBundle;
import com.jetbrains.python.psi.PyStatement;
import com.jetbrains.python.refactoring.introduce.IntroduceHandler;
import com.jetbrains.python.refactoring.introduce.IntroduceOperation;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class PyIntroduceVariableHandler extends IntroduceHandler {
  public PyIntroduceVariableHandler() {
    super(new VariableValidator(), PyPsiBundle.message("refactoring.introduce.variable.dialog.title"));
  }

  @Override
  protected PsiElement addDeclaration(final @NotNull PsiElement expression,
                                      final @NotNull PsiElement declaration,
                                      @NotNull IntroduceOperation operation) {
    return doIntroduceVariable(expression, declaration, operation.getOccurrences(), operation.isReplaceAll());
  }

  public static PsiElement doIntroduceVariable(PsiElement expression,
                                               PsiElement declaration,
                                               List<? extends PsiElement> occurrences,
                                               boolean replaceAll) {
    PsiElement anchor = replaceAll ? IntroduceHandler.findAnchor(occurrences) : PsiTreeUtil.getParentOfType(expression, PyStatement.class);
    assert anchor != null;
    final PsiElement parent = anchor.getParent();
    return parent.addBefore(declaration, anchor);
  }

  @Override
  protected String getHelpId() {
    return "refactoring.introduceVariable";
  }

  @Override
  protected String getRefactoringId() {
    return "refactoring.python.introduce.variable";
  }
}
