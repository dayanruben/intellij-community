// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.refactoring.classes;

import com.intellij.openapi.util.Condition;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.psi.PyArgumentList;
import com.jetbrains.python.psi.PyAssignmentStatement;
import com.jetbrains.python.psi.PyCallExpression;
import com.jetbrains.python.psi.PyElement;
import com.jetbrains.python.psi.PyExpression;
import com.jetbrains.python.psi.PyRecursiveElementVisitor;
import com.jetbrains.python.psi.PyReferenceExpression;
import com.jetbrains.python.psi.PyTargetExpression;
import org.jetbrains.annotations.NotNull;

/**
 * Searches for element in another element's usages.
 * Parametrize it with needle and make stack to accept it.
 *
 * @author Ilya.Kazakevich
 */
final class DependencyVisitor extends PyRecursiveElementVisitor {

  private final @NotNull PyElement myElementToFind;
  private boolean myDependencyFound;

  /**
   * @param elementToFind what to find
   */
  DependencyVisitor(final @NotNull PyElement elementToFind) {
    myElementToFind = elementToFind;
  }

  @Override
  public void visitPyTargetExpression(@NotNull PyTargetExpression node) {
    var value = node.findAssignedValue();
    if (value != null) {
      var reference = value.getReference();
      if (reference != null && reference.isReferenceTo(myElementToFind)) {
        myDependencyFound = true;
        return;
      }
    }
    super.visitPyTargetExpression(node);
  }

  @Override
  public void visitPyCallExpression(final @NotNull PyCallExpression node) {
    final PyExpression callee = node.getCallee();
    if (callee != null) {
      final PsiReference calleeReference = callee.getReference();
      if ((calleeReference != null) && calleeReference.isReferenceTo(myElementToFind)) {
        myDependencyFound = true;
        return;
      }
      final String calleeName = callee.getName();

      final String name = myElementToFind.getName();
      if ((calleeName != null) && calleeName.equals(name)) {  // Check by name also
        myDependencyFound = true;
      }

      // Member could be used as method param
      final PyArgumentList list = node.getArgumentList();
      if (list != null) {
        for (final PyExpression expression : node.getArgumentList().getArgumentExpressions()) {
          final PsiReference reference = expression.getReference();
          if ((reference != null) && reference.isReferenceTo(myElementToFind)) {
            myDependencyFound = true;
          }
          if ((name != null) && name.equals(expression.getName())) {
            myDependencyFound = true;
          }
        }
      }
    }
  }

  @Override
  public void visitPyReferenceExpression(final @NotNull PyReferenceExpression node) {

    final PsiPolyVariantReference reference = node.getReference();
    if (reference.isReferenceTo(myElementToFind)) {
      myDependencyFound = true;
      return;
    }
    // TODO: This step is member-type specific. Move to MemberManagers?
    if (myElementToFind instanceof PyAssignmentStatement) {
      final PyExpression[] targets = ((PyAssignmentStatement)myElementToFind).getTargets();

      if (targets.length != 1) {
        return;
      }
      final PyExpression expression = targets[0];

      if (reference.isReferenceTo(expression)) {
        myDependencyFound = true;
        return;
      }
      if (node.getText().equals(expression.getText())) { // Check by name also
        myDependencyFound = true;
      }
      return;
    }
    final PsiElement declaration = reference.resolve();
    myDependencyFound = PsiTreeUtil.findFirstParent(declaration, new PsiElementCondition()) != null;
  }

  public boolean isDependencyFound() {
    return myDependencyFound;
  }

  private class PsiElementCondition implements Condition<PsiElement> {
    @Override
    public boolean value(final PsiElement psiElement) {
      return psiElement.equals(myElementToFind);
    }
  }
}
