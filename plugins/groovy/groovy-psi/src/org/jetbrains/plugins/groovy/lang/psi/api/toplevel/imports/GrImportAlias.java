// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy.lang.psi.api.toplevel.imports;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElement;

public interface GrImportAlias extends GroovyPsiElement {

  @Nullable
  PsiElement getNameElement();

  @Nullable
  String getName();
}
