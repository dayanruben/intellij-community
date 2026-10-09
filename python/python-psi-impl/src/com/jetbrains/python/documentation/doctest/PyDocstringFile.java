// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.documentation.doctest;

import com.intellij.psi.FileViewProvider;

/**
 * @deprecated Use {@link PyDoctestFile} instead.
 */
@Deprecated
public class PyDocstringFile extends PyDoctestFile {
  public PyDocstringFile(FileViewProvider viewProvider) {
    super(viewProvider);
  }
}