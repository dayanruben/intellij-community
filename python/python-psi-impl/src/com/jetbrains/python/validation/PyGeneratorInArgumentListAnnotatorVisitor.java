// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.validation;

import com.intellij.lang.ASTNode;
import com.jetbrains.python.PyPsiBundle;
import com.jetbrains.python.PyTokenTypes;
import com.jetbrains.python.psi.PyArgumentList;
import com.jetbrains.python.psi.PyElementVisitor;
import com.jetbrains.python.psi.PyExpression;
import com.jetbrains.python.psi.PyGeneratorExpression;
import org.jetbrains.annotations.NotNull;


final class PyGeneratorInArgumentListAnnotatorVisitor extends PyElementVisitor {
  private final @NotNull PyAnnotationHolder myHolder;

  PyGeneratorInArgumentListAnnotatorVisitor(@NotNull PyAnnotationHolder holder) { myHolder = holder; }

  @Override
  public void visitPyArgumentList(@NotNull PyArgumentList node) {
    if (node.getArguments().length > 1) {
      for (PyExpression expression : node.getArguments()) {
        if (expression instanceof PyGeneratorExpression) {
          ASTNode firstChildNode = expression.getNode().getFirstChildNode();
          if (firstChildNode.getElementType() != PyTokenTypes.LPAR) {
            myHolder.markError(expression, PyPsiBundle.message("ANN.generator.expression.must.be.parenthesized.if.not.sole.argument"));
          }
        }
      }
    }
  }
}
