// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.documentation.doctest

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.jetbrains.python.psi.LanguageLevel
import com.jetbrains.python.psi.PyExpressionCodeFragment
import com.jetbrains.python.psi.impl.PyFileImpl

open class PyDoctestFile(viewProvider: FileViewProvider?) : PyFileImpl(viewProvider, PyDoctestLanguageDialect.getInstance()),
                                                            PyExpressionCodeFragment {
  override fun getFileType(): FileType {
    return PyDoctestFileType.INSTANCE
  }

  override fun toString(): String {
    return "DoctestFile:$name"
  }

  override fun getLanguageLevel(): LanguageLevel? {
    val languageManager = InjectedLanguageManager.getInstance(project)
    val host = languageManager.getInjectionHost(this)
    if (host != null) return LanguageLevel.forElement(host.getContainingFile())
    return super<PyExpressionCodeFragment>.getLanguageLevel()
  }
}