// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.extensions

import com.jetbrains.python.nameResolver.FQNamesProvider
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.types.PyClassLikeType
import com.jetbrains.python.psi.types.TypeEvalContext

/**
 * @author Ilya.Kazakevich
 */
fun PyClass.inherits(evalContext: TypeEvalContext, parentNames: Set<String>): Boolean =
  this.getAncestorTypes(evalContext).filterNotNull().mapNotNull(PyClassLikeType::getClassQName).any(parentNames::contains)

fun PyClass.inherits(evalContext: TypeEvalContext, vararg parentNames: String): Boolean = this.inherits(evalContext, parentNames.toHashSet())

fun PyClass.inherits(evalContext: TypeEvalContext?, parentNames: FQNamesProvider): Boolean =
  this.getAncestorClasses(evalContext).any(parentNames::isNameMatches)
