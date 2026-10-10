// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.refactoring.introduce;

import com.intellij.lang.LanguageNamesValidation;
import com.intellij.lang.refactoring.NamesValidator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.PythonLanguage;
import com.jetbrains.python.psi.PyClass;
import com.jetbrains.python.psi.PyExpression;
import com.jetbrains.python.psi.PyFunction;
import com.jetbrains.python.psi.PyUtil;
import com.jetbrains.python.psi.impl.PyBuiltinCache;
import com.jetbrains.python.refactoring.PyReplaceExpressionUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class IntroduceValidator {
  private final NamesValidator myNamesValidator = LanguageNamesValidation.INSTANCE.forLanguage(PythonLanguage.getInstance());

  public boolean isNameValid(final String name, final Project project) {
    return (name != null) &&
           (myNamesValidator.isIdentifier(name, project)) &&
           !(myNamesValidator.isKeyword(name, project));
  }

  public boolean checkPossibleName(final @NotNull String name, final @NotNull PyExpression expression) {
    return check(name, expression) == null;
  }

  public abstract @Nullable String check(String name, PsiElement psiElement);

  public static boolean isDefinedInScope(String name, PsiElement psiElement) {
    if (psiElement.getUserData(PyReplaceExpressionUtil.SELECTION_BREAKS_AST_NODE) != null) {
      final Pair<PsiElement, TextRange> data = psiElement.getUserData(PyReplaceExpressionUtil.SELECTION_BREAKS_AST_NODE);
      psiElement = data.first;
    }
    PsiElement context = PsiTreeUtil.getParentOfType(psiElement, PyFunction.class);
    if (context == null) {
      context = PsiTreeUtil.getParentOfType(psiElement, PyClass.class);
    }
    if (context == null) {
      context = psiElement.getContainingFile();
    }

    return PyUtil.collectUsedNames(context).contains(name) || PyBuiltinCache.getInstance(psiElement).getByName(name) != null;
  }
}
