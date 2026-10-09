// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.dataflow.scope;

import com.intellij.codeInsight.dataflow.DFALimitExceededException;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.jetbrains.python.psi.PyImportedNameDefiner;
import com.jetbrains.python.psi.PyTargetExpression;
import com.jetbrains.python.psi.types.TypeEvalContext;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface Scope {
  /**
   * @return the local, instance or class variable or the parameter with the given name, from the reaching definitions
   * @deprecated The method uses the obsolete DFA analysis. This interface has no replacement for it.
   */
  @Nullable
  @Deprecated
  @ApiStatus.Internal
  ScopeVariable getDeclaredVariable(@NotNull PsiElement anchorElement,
                                    @NotNull String name,
                                    @NotNull TypeEvalContext typeEvalContext) throws DFALimitExceededException;

  boolean hasGlobals();

  boolean isGlobal(String name);

  boolean hasNonLocals();

  boolean isNonlocal(String name);

  boolean containsDeclaration(String name);

  @NotNull
  List<PyImportedNameDefiner> getImportedNameDefiners();

  @NotNull
  @Unmodifiable
  Collection<PsiNamedElement> getNamedElements(String name, boolean includeNestedGlobals);

  @NotNull
  Collection<PsiNamedElement> getNamedElements();

  @NotNull
  Collection<PyTargetExpression> getTargetExpressions();

  @ApiStatus.Experimental
  @NotNull
  Set<String> getGlobals();
}
