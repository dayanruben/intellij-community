// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.impl.references;

import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.QualifiedName;
import com.jetbrains.python.psi.PyFromImportStatement;
import com.jetbrains.python.psi.impl.PyReferenceExpressionImpl;
import com.jetbrains.python.psi.resolve.PyResolveContext;
import com.jetbrains.python.psi.resolve.RatedResolveResult;
import com.jetbrains.python.psi.resolve.ResolveImportUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * Reference to an imported name in a 'from ... import' statement:<br/>
 * <code>from foo import <u>name</u></code>
 */
public class PyFromImportNameReference extends PyImportReference {
  private final PyFromImportStatement myStatement;

  public PyFromImportNameReference(PyReferenceExpressionImpl element, PyResolveContext context) {
    super(element, context);
    myStatement = PsiTreeUtil.getParentOfType(element, PyFromImportStatement.class);
    assert myStatement != null;
  }

  @Override
  protected @NotNull List<RatedResolveResult> resolveInner() {
    QualifiedName qName = myElement.asQualifiedName();
    return qName == null
           ? Collections.emptyList()
           : ResolveImportUtil.resolveNameInFromImport(myStatement, qName);
  }
}
