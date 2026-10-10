// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package com.jetbrains.python.psi

import com.intellij.psi.PsiElement

/**
 * @param classOnly limit ancestors to this class only
 * @param limit upper limit to prevent huge unstub. [com.intellij.psi.PsiFile] is good choice
 */
fun <T : PsiElement> PsiElement.getAncestors(limit: PsiElement = this.containingFile, classOnly: Class<out T>): List<T> {
  var currentElement = this
  val result = ArrayList<T>()
  while (currentElement != limit) {
    currentElement = currentElement.parent
    if (classOnly.isInstance(currentElement)) {
      @Suppress("UNCHECKED_CAST") // Checked one line above
      result.add(currentElement as T)
    }
  }

  return result
}
