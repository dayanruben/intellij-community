// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.functionTypeComments;

import com.jetbrains.python.psi.PyFileElementType;
import org.jetbrains.annotations.NotNull;

/**
 * @author Mikhail Golubev
 */
public class PyFunctionTypeAnnotationFileElementType extends PyFileElementType {
  public static final PyFunctionTypeAnnotationFileElementType INSTANCE =
    new PyFunctionTypeAnnotationFileElementType(PyFunctionTypeAnnotationDialect.INSTANCE);

  public PyFunctionTypeAnnotationFileElementType(PyFunctionTypeAnnotationDialect instance) {
    super(instance);
  }

  @Override
  public @NotNull String getExternalId() {
    return "PyFunctionTypeComment.ID";
  }
}
