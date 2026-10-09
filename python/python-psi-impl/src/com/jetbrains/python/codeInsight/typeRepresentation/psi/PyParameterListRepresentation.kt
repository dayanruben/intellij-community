// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.typeRepresentation.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import com.jetbrains.python.codeInsight.typeRepresentation.PyTypeRepresentationElementTypes
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PySlashParameter
import com.jetbrains.python.psi.impl.PyElementImpl

class PyParameterListRepresentation(astNode: ASTNode) : PyElementImpl(astNode) {
  val parameters: List<PsiElement>
    get() = children.filter { it is PyExpression || it is PySlashParameter || it is PyNamedParameterTypeRepresentation || it.elementType == PyTypeRepresentationElementTypes.PLACEHOLDER }
}
