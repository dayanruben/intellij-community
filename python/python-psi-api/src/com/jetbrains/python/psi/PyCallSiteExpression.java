// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi;

import com.jetbrains.python.ast.PyAstCallSiteExpression;

/**
 * Marker interface for Python expressions that are call sites for explicit or implicit function calls.
 *
 */
public interface PyCallSiteExpression extends PyAstCallSiteExpression, PyCallSiteOwner, PyExpression {
}
