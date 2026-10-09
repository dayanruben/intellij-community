// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.types

import com.jetbrains.python.PyNames

val PyType?.isNoneType: Boolean
  get() = this is PyClassType && classQName != null && classQName in PyNames.TYPE_NONE_NAMES
