// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.impl.references

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference

/**
 * EP to check if some reference points to some element
 */
interface PyReferenceCustomTargetChecker {
  companion object {
    private val EP_NAME = ExtensionPointName.create<PyReferenceCustomTargetChecker>("Pythonid.pyReferenceCustomTargetChecker")
    fun isReferenceTo(reference: PsiReference, to: PsiElement) = EP_NAME.extensions.any { it.isReferenceTo(reference, to) }
  }

  fun isReferenceTo(reference: PsiReference, to: PsiElement): Boolean
}