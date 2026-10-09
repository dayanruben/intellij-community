// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.functionTypeComments;

import com.jetbrains.python.PyPsiBundle;
import com.jetbrains.python.PythonFileType;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * @author Mikhail Golubev
 */
public class PyFunctionTypeAnnotationFileType extends PythonFileType {
  public static final PyFunctionTypeAnnotationFileType INSTANCE = new PyFunctionTypeAnnotationFileType();

  private PyFunctionTypeAnnotationFileType() {
    super(PyFunctionTypeAnnotationDialect.INSTANCE);
  }

  @Override
  public @NotNull @NonNls String getName() {
    return "PythonFunctionTypeComment";
  }

  @Override
  public @NotNull String getDescription() {
    return PyPsiBundle.message("filetype.python.function.type.annotation.description");
  }

  @Override
  public @NotNull String getDefaultExtension() {
    return "functionTypeComment";
  }
}
