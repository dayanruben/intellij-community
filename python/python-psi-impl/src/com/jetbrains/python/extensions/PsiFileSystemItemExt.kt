// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.extensions

import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.util.QualifiedName
import com.jetbrains.python.psi.PyPsiFacade

/**
 * @author Ilya.Kazakevich
 */

fun PsiFileSystemItem.getQName(): QualifiedName? {
  val name = PyPsiFacade.getInstance(this.project).findShortestImportableName(this.virtualFile, this) ?: return null
  return QualifiedName.fromDottedString(name)
}