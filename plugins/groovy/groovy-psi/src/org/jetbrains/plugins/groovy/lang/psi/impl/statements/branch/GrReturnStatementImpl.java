// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.plugins.groovy.lang.psi.impl.statements.branch;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes;
import org.jetbrains.plugins.groovy.lang.psi.GroovyElementVisitor;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.branch.GrReturnStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.impl.GroovyPsiElementImpl;

public class GrReturnStatementImpl extends GroovyPsiElementImpl implements GrReturnStatement {
  public GrReturnStatementImpl(@NotNull ASTNode node) {
    super(node);
  }

  @Override
  public void accept(@NotNull GroovyElementVisitor visitor) {
    visitor.visitReturnStatement(this);
  }

  @Override
  public String toString() {
    return "RETURN statement";
  }

  @Override
  public @Nullable GrExpression getReturnValue() {
    return findExpressionChild(this);
  }

  @Override
  public @NotNull PsiElement getReturnWord() {
    return findNotNullChildByType(GroovyTokenTypes.kRETURN);
  }
}
