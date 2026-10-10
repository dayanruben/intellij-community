// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.impl.blockEvaluator;

import com.jetbrains.python.psi.PyExpression;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Ilya.Kazakevich
 */
@SuppressWarnings("PackageVisibleField") // Package-only class
class PyEvaluationResult {
  final @NotNull Map<String, Object> myNamespace = new HashMap<>();
  final @NotNull Map<String, List<PyExpression>> myDeclarations = new HashMap<>();

  @NotNull
  List<PyExpression> getDeclarations(final @NotNull String name) {
    final List<PyExpression> expressions = myDeclarations.get(name);
    return (expressions != null) ? expressions : Collections.emptyList();
  }
}
