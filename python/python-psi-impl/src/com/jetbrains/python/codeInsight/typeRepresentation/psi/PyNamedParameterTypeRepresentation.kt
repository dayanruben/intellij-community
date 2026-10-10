// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.typeRepresentation.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.impl.PyElementImpl

class PyNamedParameterTypeRepresentation(astNode: ASTNode) : PyElementImpl(astNode) {
  override fun getName(): String? {
    return node.findChildByType(PyTokenTypes.IDENTIFIER)?.text
  }

  val typeExpression: PyExpression?
    get() = findChildByClass(PyExpression::class.java)

  val defaultValue: PyExpression?
    get() {
      val eqNode = node.findChildByType(PyTokenTypes.EQ) ?: return null
      return PsiTreeUtil.getNextSiblingOfType(eqNode.psi, PyExpression::class.java)
    }

  override fun toString(): String {
    return "${super.toString()}('$name')"
  }
}
