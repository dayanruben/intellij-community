// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.tensorFlow

import com.intellij.openapi.util.registry.Registry
import com.jetbrains.python.psi.resolve.QualifiedNameFinder
import com.jetbrains.python.pyi.PyiFile
import com.jetbrains.python.pyi.PyiStubSuppressor

class TensorflowPyiStubsSuppressor : PyiStubSuppressor {

  override fun isIgnoredStub(file: PyiFile): Boolean {
    if (Registry.`is`("enable.tensorflow.pyi.stubs")) return false
    return QualifiedNameFinder.findShortestImportableQName(file)?.firstComponent == "tensorflow"
  }
}

