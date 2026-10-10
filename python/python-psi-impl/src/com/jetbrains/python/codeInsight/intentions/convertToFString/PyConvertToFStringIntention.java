// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.intentions.convertToFString;

import com.intellij.modcommand.ActionContext;
import com.intellij.modcommand.ModPsiUpdater;
import com.intellij.modcommand.Presentation;
import com.intellij.modcommand.PsiUpdateModCommandAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.PyPsiBundle;
import com.jetbrains.python.PyTokenTypes;
import com.jetbrains.python.psi.LanguageLevel;
import com.jetbrains.python.psi.PyBinaryExpression;
import com.jetbrains.python.psi.PyCallExpression;
import com.jetbrains.python.psi.PyFile;
import com.jetbrains.python.psi.PyReferenceExpression;
import com.jetbrains.python.psi.PyStringLiteralExpression;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.jetbrains.python.psi.PyUtil.as;

/**
 * @author Mikhail Golubev
 */
public final class PyConvertToFStringIntention extends PsiUpdateModCommandAction<PsiElement> {
  PyConvertToFStringIntention() {
    super(PsiElement.class);
  }


  @Override
  public @Nls @NotNull String getFamilyName() {
    return PyPsiBundle.message("INTN.convert.to.fstring.literal");
  }


  @Override
  protected @Nullable Presentation getPresentation(@NotNull ActionContext context, @NotNull PsiElement element) {
    if (!(context.file() instanceof PyFile) || LanguageLevel.forElement(context.file()).isOlderThan(LanguageLevel.PYTHON36)) return null;

    final BaseConvertToFStringProcessor processor = findSuitableProcessor(element);
    if (processor != null && processor.isRefactoringAvailable()) return super.getPresentation(context, element);
    return null;
  }

  @Override
  protected void invoke(@NotNull ActionContext context, @NotNull PsiElement element, @NotNull ModPsiUpdater updater) {
    final BaseConvertToFStringProcessor processor = findSuitableProcessor(element);
    assert processor != null;
    processor.doRefactoring();
  }

  private static @Nullable BaseConvertToFStringProcessor findSuitableProcessor(@NotNull PsiElement anchor) {
    final PyBinaryExpression binaryExpr = PsiTreeUtil.getParentOfType(anchor, PyBinaryExpression.class);
    if (binaryExpr != null && binaryExpr.getOperator() == PyTokenTypes.PERC) {
      final PyStringLiteralExpression pyString = as(binaryExpr.getLeftExpression(), PyStringLiteralExpression.class);
      if (pyString != null) {
        return new OldStyleConvertToFStringProcessor(pyString);
      }
    }

    final PyCallExpression callExpr = PsiTreeUtil.getParentOfType(anchor, PyCallExpression.class);
    if (callExpr != null) {
      final PyReferenceExpression callee = as(callExpr.getCallee(), PyReferenceExpression.class);
      if (callee != null && PyNames.FORMAT.equals(callee.getName())) {
        final PyStringLiteralExpression pyString = as(callee.getQualifier(), PyStringLiteralExpression.class);
        if (pyString != null) {
          return new NewStyleConvertToFStringProcessor(pyString);
        }
      }
    }
    return null;
  }
}
