// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.functionTypeComments.psi;

import com.intellij.lang.ASTNode;
import com.jetbrains.python.PythonDialectsTokenSetProvider;
import com.jetbrains.python.psi.PyExpression;
import com.jetbrains.python.psi.impl.PyElementImpl;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * @author Mikhail Golubev
 */
public class PyParameterTypeList extends PyElementImpl {
  public PyParameterTypeList(ASTNode astNode) {
    super(astNode);
  }

  public @NotNull List<PyExpression> getParameterTypes() {
    return findChildrenByType(PythonDialectsTokenSetProvider.getInstance().getExpressionTokens());
  }
}
