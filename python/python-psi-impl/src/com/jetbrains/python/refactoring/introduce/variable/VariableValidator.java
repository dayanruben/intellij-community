// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.refactoring.introduce.variable;

import com.intellij.psi.PsiElement;
import com.jetbrains.python.PyPsiBundle;
import com.jetbrains.python.refactoring.introduce.IntroduceValidator;
import org.jetbrains.annotations.Nullable;

public class VariableValidator extends IntroduceValidator {
  @Override
  public @Nullable String check(String name, PsiElement psiElement) {
    if (isDefinedInScope(name, psiElement)) {
      return PyPsiBundle.message("refactoring.introduce.variable.scope.error");
    }
    return null;
  }
}
