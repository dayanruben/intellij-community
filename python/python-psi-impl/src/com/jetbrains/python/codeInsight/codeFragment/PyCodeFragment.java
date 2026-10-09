// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.codeFragment;

import com.intellij.codeInsight.codeFragment.CodeFragment;
import com.intellij.openapi.util.Pair;
import com.jetbrains.python.psi.types.PyType;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

@NotNullByDefault
public class PyCodeFragment extends CodeFragment {
  /** Maps variable names to their type names and types. */
  private final Map<String, Pair<String, PyType>> myInputTypes;
  private final @Nullable String myOutputType;
  private final Set<PyType> myOutputTypes;
  private final Set<String> myGlobalWrites;
  private final Set<String> myNonlocalWrites;
  private final boolean myYieldInside;
  private final boolean myAsync;

  public PyCodeFragment(final Set<String> input,
                        final Set<String> output,
                        final Map<String, Pair<String, PyType>> inputTypeNames,
                        final @Nullable String outputType,
                        final Set<PyType> outputTypes,
                        final Set<String> globalWrites,
                        final Set<String> nonlocalWrites,
                        final boolean returnInside,
                        final boolean yieldInside,
                        final boolean isAsync) {
    super(input, output, returnInside);
    myInputTypes = inputTypeNames;
    myOutputType = outputType;
    myOutputTypes = outputTypes;
    myGlobalWrites = globalWrites;
    myNonlocalWrites = nonlocalWrites;
    myYieldInside = yieldInside;
    myAsync = isAsync;
  }

  /** Returns the type name of the input variable with the given name. */
  public @Nullable String getInputTypeName(String varName) {
    Pair<String, PyType> type = myInputTypes.get(varName);
    return type == null ? null : type.first;
  }

  /** Returns the type of the input variable with the given name. */
  public @Nullable PyType getInputType(String varName) {
    Pair<String, PyType> type = myInputTypes.get(varName);
    return type == null ? null : type.second;
  }

  public @Nullable String getOutputType() {
    return myOutputType;
  }

  public Set<PyType> getOutputTypes() {
    return myOutputTypes;
  }

  public Set<String> getGlobalWrites() {
    return myGlobalWrites;
  }

  public Set<String> getNonlocalWrites() {
    return myNonlocalWrites;
  }

  public boolean isYieldInside() {
    return myYieldInside;
  }

  public boolean isAsync() {
    return myAsync;
  }
}
