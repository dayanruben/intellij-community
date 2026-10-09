// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.functionTypeComments;

import com.intellij.lang.DependentLanguage;
import com.intellij.lang.Language;
import com.jetbrains.python.PythonLanguage;

/**
 * @author Mikhail Golubev
 */
public class PyFunctionTypeAnnotationDialect extends Language implements DependentLanguage {
  public static final PyFunctionTypeAnnotationDialect INSTANCE = new PyFunctionTypeAnnotationDialect();

  protected PyFunctionTypeAnnotationDialect() {
    super(PythonLanguage.getInstance(), "PyFunctionTypeComment");
  }
}
