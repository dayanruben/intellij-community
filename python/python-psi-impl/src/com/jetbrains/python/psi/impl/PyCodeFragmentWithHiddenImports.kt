// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.impl

import com.intellij.openapi.project.Project
import com.jetbrains.python.psi.LanguageLevel
import com.jetbrains.python.psi.PyElementGenerator
import com.jetbrains.python.psi.PyImportStatementBase
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
open class PyCodeFragmentWithHiddenImports(project: Project, name: String, text: CharSequence, isPhysical: Boolean)
  : PyExpressionCodeFragmentImpl(project, name, text, isPhysical) {

  private val _pseudoImports = mutableListOf<PyImportStatementBase>()

  val pseudoImports get(): List<PyImportStatementBase> = _pseudoImports

  fun addImports(pseudoImports: Collection<PyImportStatementBase>) {
    _pseudoImports.addAll(pseudoImports)
    clearCaches()
    myManager.beforeChange(false) // drops resolve caches
  }

  fun addImportsFromStrings(importStatements: Collection<String>) {
    val generator = PyElementGenerator.getInstance(project)
    val langLevel = LanguageLevel.forElement(this)
    importStatements
      .map { generator.createFromText(langLevel, PyImportStatementBase::class.java, it) }
      .let { addImports(it) }
  }

  override fun clone(): PyCodeFragmentWithHiddenImports {
    val clone = super.clone() as PyCodeFragmentWithHiddenImports
    clone._pseudoImports.clear()
    clone._pseudoImports.addAll(_pseudoImports)
    return clone
  }
}