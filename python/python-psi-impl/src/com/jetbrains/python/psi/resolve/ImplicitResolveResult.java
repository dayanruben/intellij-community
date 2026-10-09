// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.resolve;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.Nullable;


public class ImplicitResolveResult extends RatedResolveResult {
  public ImplicitResolveResult(final @Nullable PsiElement element, final int rate) {
    super(rate, element);
  }
}
