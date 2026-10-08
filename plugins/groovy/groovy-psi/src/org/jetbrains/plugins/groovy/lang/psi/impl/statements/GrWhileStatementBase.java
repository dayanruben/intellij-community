// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy.lang.psi.impl.statements;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.GroovyElementTypes;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.GrCondition;
import org.jetbrains.plugins.groovy.lang.psi.api.formatter.GrControlStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrLoopStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.impl.GroovyPsiElementImpl;
import org.jetbrains.plugins.groovy.lang.psi.impl.PsiImplUtil;
import org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil;

/**
 * @author Bas Leijdekkers
 */
public abstract class GrWhileStatementBase extends GroovyPsiElementImpl implements GrLoopStatement, GrControlStatement {

  public GrWhileStatementBase(@NotNull ASTNode node) {
    super(node);
  }

  @Override
  public PsiAnnotation @NotNull [] getAnnotations() {
    return findChildrenByClass(PsiAnnotation.class);
  }

  public @Nullable GrExpression getCondition() {
    PsiElement lParenth = getLParenth();
    if (lParenth == null) return null;

    return PsiUtil.skipWhitespacesAndComments(lParenth.getNextSibling(), true) instanceof GrExpression expression ? expression : null;
  }

  public @Nullable PsiElement getLParenth() {
    return findChildByType(GroovyElementTypes.T_LPAREN);
  }

  public @Nullable PsiElement getRParenth() {
    return findChildByType(GroovyElementTypes.T_RPAREN);
  }

  @Override
  public <T extends GrCondition> T replaceBody(T statement) {
    return PsiImplUtil.replaceBody(statement, getBody(), getNode(), getProject());
  }
}
