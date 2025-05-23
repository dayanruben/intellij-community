// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.streams.trace.dsl.impl.java

import com.intellij.debugger.streams.core.trace.dsl.Expression
import com.intellij.debugger.streams.core.trace.dsl.ListVariable
import com.intellij.debugger.streams.core.trace.dsl.VariableDeclaration
import com.intellij.debugger.streams.core.trace.dsl.impl.VariableImpl
import com.intellij.debugger.streams.core.trace.impl.handler.type.ListType

/**
 * @author Vitaliy.Bibaev
 */
class JavaListVariable(override val type: ListType, name: String)
  : VariableImpl(type, name), ListVariable {

  override fun get(index: Expression): Expression = call("get", index)
  override fun set(index: Expression, newValue: Expression): Expression = call("set", index, newValue)
  override fun add(element: Expression): Expression = call("add", element)

  override fun contains(element: Expression): Expression = call("contains", element)

  override fun size(): Expression = call("size")

  override fun defaultDeclaration(): VariableDeclaration =
    JavaVariableDeclaration(this, false, type.defaultValue)
}