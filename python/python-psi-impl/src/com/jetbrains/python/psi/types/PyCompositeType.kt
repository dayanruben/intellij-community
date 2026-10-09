// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.types

import org.jetbrains.annotations.ApiStatus

// TODO Make it sealed once everything is converted to Kotlin
/**
 * A composite type consists of several alternatives.
 * There are three known kinds of such types: unions, "unsafe" unions, and intersections.
 *
 * @see PyUnionType
 * @see PyIntersectionType
 * @see PyUnsafeUnionType
 */
@ApiStatus.Experimental
interface PyCompositeType : PyType {
  val members: Collection<PyType?>

  override fun <T> acceptTypeVisitor(visitor: PyTypeVisitor<T>): T? {
    if (visitor is PyTypeVisitorExt<T>) {
      return visitor.visitPyCompositeType(this)
    }
    return visitor.visitPyType(this)
  }
}