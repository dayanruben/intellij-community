// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.typeHints;

import com.intellij.lang.DependentLanguage;
import com.intellij.lang.Language;
import com.jetbrains.python.PythonLanguage;
import org.jetbrains.annotations.NotNull;

public class PyTypeHintDialect extends Language implements DependentLanguage {

  public static final @NotNull PyTypeHintDialect INSTANCE = new PyTypeHintDialect();

  private PyTypeHintDialect() {
    super(PythonLanguage.getInstance(), "PyTypeHint");
  }
}
