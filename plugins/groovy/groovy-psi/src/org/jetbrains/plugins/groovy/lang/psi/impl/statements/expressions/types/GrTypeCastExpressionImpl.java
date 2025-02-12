// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.types;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes;
import org.jetbrains.plugins.groovy.lang.psi.GroovyElementVisitor;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrTypeCastExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrTypeElement;
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.GrExpressionImpl;
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.TypesUtil;

public class GrTypeCastExpressionImpl extends GrExpressionImpl implements GrTypeCastExpression {

  public GrTypeCastExpressionImpl(@NotNull ASTNode node) {
    super(node);
  }

  @Override
  public void accept(@NotNull GroovyElementVisitor visitor) {
    visitor.visitCastExpression(this);
  }

  @Override
  public String toString() {
    return "Typecast expression";
  }

  @Override
  public PsiType getType() {
    final GrTypeElement typeElement = getCastTypeElement();
    return typeElement != null ? TypesUtil.boxPrimitiveType(typeElement.getType(), getManager(), getResolveScope()) : null;
  }

  @Override
  public GrTypeElement getCastTypeElement() {
    return findChildByClass(GrTypeElement.class);
  }

  @Override
  public @Nullable GrExpression getOperand() {
    return findExpressionChild(this);
  }

  @Override
  public @NotNull PsiElement getLeftParen() {
    ASTNode paren = getNode().findChildByType(GroovyTokenTypes.mLPAREN);
    assert paren != null;
    return paren.getPsi();
  }

  @Override
  public @NotNull PsiElement getRightParen() {
    ASTNode paren = getNode().findChildByType(GroovyTokenTypes.mRPAREN);
    assert paren != null;
    return paren.getPsi();
  }
}
