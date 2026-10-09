// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.functionTypeComments;

import com.jetbrains.python.codeInsight.functionTypeComments.psi.PyFunctionTypeAnnotation;
import com.jetbrains.python.codeInsight.functionTypeComments.psi.PyParameterTypeList;
import com.jetbrains.python.psi.PyElementType;

/**
 * @author Mikhail Golubev
 */
public interface PyFunctionTypeAnnotationElementTypes {
  PyElementType FUNCTION_SIGNATURE = new PyElementType("FUNCTION_SIGNATURE", node -> new PyFunctionTypeAnnotation(node));
  PyElementType PARAMETER_TYPE_LIST = new PyElementType("PARAMETER_TYPE_LIST", node -> new PyParameterTypeList(node));
}
