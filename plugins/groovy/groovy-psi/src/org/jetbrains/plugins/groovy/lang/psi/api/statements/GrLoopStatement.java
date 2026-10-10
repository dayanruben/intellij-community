// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy.lang.psi.api.statements;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationOwner;
import com.intellij.psi.impl.PsiImplUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.GrCondition;

public interface GrLoopStatement extends GrStatement, PsiAnnotationOwner {
  <T extends GrCondition> T replaceBody (T statement) throws IncorrectOperationException;

  @Nullable
  GrStatement getBody();

  @Override
  default PsiAnnotation findAnnotation(@NotNull @NonNls String qualifiedName) {
    return PsiImplUtil.findAnnotation(this, qualifiedName);
  }

  @Override
  default PsiAnnotation @NotNull [] getApplicableAnnotations() {
    return getAnnotations();
  }

  @Override
  default @NotNull PsiAnnotation addAnnotation(@NotNull String qualifiedName) {
    throw new AssertionError("Not implemented");
  }
}
