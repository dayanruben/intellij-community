// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy.lang.psi.api.statements;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.api.formatter.GrControlStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;

public interface GrWhileStatement extends GrStatement, GrControlStatement, GrLoopStatement {

  @Nullable GrExpression getCondition();

  @Nullable PsiElement getLParenth();

  @Nullable PsiElement getRParenth();
}
