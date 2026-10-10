// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi;

import com.intellij.lang.LanguageExtension;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/**
 * User : catherine
 *
 * filter for pythonvisitor.
 * check if we should visit element
 */
public interface PythonVisitorFilter {
  LanguageExtension<PythonVisitorFilter> INSTANCE = new LanguageExtension<>("Pythonid.visitorFilter");

  boolean isSupported(@NotNull Class<? extends PyElementVisitor> visitorClass, @NotNull PsiFile file);
}
