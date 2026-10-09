// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.typeRepresentation

import com.intellij.psi.tree.IElementType
import com.jetbrains.python.codeInsight.typeRepresentation.psi.PyFunctionTypeRepresentation
import com.jetbrains.python.codeInsight.typeRepresentation.psi.PyNamedParameterTypeRepresentation
import com.jetbrains.python.codeInsight.typeRepresentation.psi.PyParameterListRepresentation
import com.jetbrains.python.psi.PyElementType
import com.jetbrains.python.psi.impl.PyElementImpl

object PyTypeRepresentationElementTypes {
  val FUNCTION_SIGNATURE: PyElementType = PyElementType("FUNCTION_SIGNATURE") { node -> PyFunctionTypeRepresentation(node) }
  val PARAMETER_TYPE_LIST: PyElementType = PyElementType("PARAMETER_TYPE_LIST") { node -> PyParameterListRepresentation(node) }
  val NAMED_PARAMETER_TYPE: PyElementType = PyElementType("NAMED_PARAMETER_TYPE") { node -> PyNamedParameterTypeRepresentation(node) }
  val PLACEHOLDER: IElementType = PyElementType("PLACEHOLDER") { node -> PyElementImpl(node) }
}
