// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.typeRepresentation

import com.intellij.lang.DependentLanguage
import com.intellij.lang.Language
import com.jetbrains.python.PythonLanguage

/**
 * Used to represent types that are not possible to express in the language like callable types
 *
 * This is used to serialize types, and for type engine communication
 */
object PyTypeRepresentationDialect : Language(PythonLanguage.getInstance(), "PyTypeRepresentation"), DependentLanguage

const val PyModuleTypeName: String = "Module"
