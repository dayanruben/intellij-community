// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.impl

import com.intellij.lang.ASTNode
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyElementVisitor
import com.jetbrains.python.psi.PyWithItem
import com.jetbrains.python.psi.PyWithStatement
import com.jetbrains.python.psi.types.PyClassType
import com.jetbrains.python.psi.types.PyLiteralType
import com.jetbrains.python.psi.types.PyTypeUtil.convertToType
import com.jetbrains.python.psi.types.TypeEvalContext

class PyWithItemImpl(astNode: ASTNode?) : PyElementImpl(astNode), PyWithItem {
  override fun acceptPyVisitor(pyVisitor: PyElementVisitor) {
    pyVisitor.visitPyWithItem(this)
  }

  override fun isSuppressingExceptions(context: TypeEvalContext): Boolean {
    val withStmt = PsiTreeUtil.getParentOfType(this, PyWithStatement::class.java, false) ?: return false
    val abstractType = if (withStmt.isAsync) "contextlib.AbstractAsyncContextManager" else "contextlib.AbstractContextManager"
    val contextManagerType = context.getType(expression).convertToType(abstractType, this, context)
    val exitResultType = (contextManagerType as? PyClassType)?.typeArguments?.getOrNull(1)
    return exitResultType == PyBuiltinCache.getInstance(this).boolType ||
           exitResultType is PyLiteralType && exitResultType.boolValue == true
  }
}
