// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.typeRepresentation

import com.jetbrains.python.psi.PyFileElementType

object PyTypeRepresentationFileElementType : PyFileElementType(PyTypeRepresentationDialect) {
  override fun getExternalId(): String = "PyTypeRepresentation.ID"
}
