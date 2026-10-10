// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.controlflow

import com.intellij.openapi.util.Key
import com.jetbrains.python.codeInsight.dataflow.scope.Scope
import com.jetbrains.python.codeInsight.dataflow.scope.impl.ScopeImpl
import com.jetbrains.python.getEffectiveLanguageLevel
import com.jetbrains.python.psi.PyUtil
import java.lang.ref.SoftReference

object ControlFlowCache {
  private val CONTROL_FLOW_KEY = Key.create<SoftReference<PyControlFlow?>?>("com.jetbrains.python.codeInsight.controlflow.ControlFlow")
  private val SCOPE_KEY = Key.create<SoftReference<Scope?>?>("com.jetbrains.python.codeInsight.controlflow.Scope")

  @JvmStatic
  fun clear(scopeOwner: ScopeOwner) {
    scopeOwner.putUserData(CONTROL_FLOW_KEY, null)
    scopeOwner.putUserData(SCOPE_KEY, null)
  }

  fun getControlFlow(
    element: ScopeOwner,
    controlFlowBuilder: PyControlFlowBuilder,
  ): PyControlFlow {
    val ref = element.getUserData(CONTROL_FLOW_KEY)
    var flow = com.intellij.reference.SoftReference.dereference<PyControlFlow?>(ref)
    if (flow == null) {
      flow = controlFlowBuilder.buildControlFlow(element)
      element.putUserData(CONTROL_FLOW_KEY, SoftReference<PyControlFlow?>(flow))
    }
    return flow
  }

  @JvmStatic
  fun getControlFlow(element: ScopeOwner): PyControlFlow {
    val languageLevel = getEffectiveLanguageLevel(element.containingFile)
    return getControlFlow(element, PyControlFlowBuilder(languageLevel))
  }

  @JvmStatic
  fun getScope(element: ScopeOwner): Scope {
    val ref = element.getUserData(SCOPE_KEY)
    var scope = com.intellij.reference.SoftReference.dereference<Scope?>(ref)
    if (scope == null) {
      scope = ScopeImpl(element)
      element.putUserData(SCOPE_KEY, SoftReference<Scope?>(scope))
    }
    return scope
  }

  @JvmStatic
  fun getDataFlow(element: ScopeOwner, context: FlowContext): PyDataFlow {
    // Cache will reset on psi modification, same as TypeEvalContext
    return PyUtil.getParameterizedCachedValue(element, context) { ctx ->
      PyDataFlow(getControlFlow(element), ctx)
    }
  }
}
