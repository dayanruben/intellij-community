// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.functionTypeComments.psi;

import com.intellij.lang.ASTNode;
import com.jetbrains.python.psi.PyExpression;
import com.jetbrains.python.psi.impl.PyElementImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Mikhail Golubev
 */
public class PyFunctionTypeAnnotation extends PyElementImpl {
  public PyFunctionTypeAnnotation(ASTNode astNode) {
    super(astNode);
  }

  public @NotNull PyParameterTypeList getParameterTypeList() {
    return findNotNullChildByClass(PyParameterTypeList.class);
  }

  public @Nullable PyExpression getReturnType() {
    return findChildByClass(PyExpression.class);
  }
}
