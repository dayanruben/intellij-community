// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.types;

import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.ui.IconManager;
import com.intellij.util.ProcessingContext;
import com.jetbrains.python.psi.AccessDirection;
import com.jetbrains.python.psi.PyExpression;
import com.jetbrains.python.psi.resolve.PyResolveContext;
import com.jetbrains.python.psi.resolve.RatedResolveResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class PyStructuralType implements PyType {
  private final @NotNull Set<String> myAttributes;
  private final boolean myInferredFromUsages;

  public PyStructuralType(@NotNull Set<String> attributes, boolean inferredFromUsages) {
    myAttributes = new LinkedHashSet<>(attributes);
    myInferredFromUsages = inferredFromUsages;
  }

  @Override
  public @Nullable List<? extends RatedResolveResult> resolveMember(@NotNull String name,
                                                                    @Nullable PyExpression location,
                                                                    @NotNull AccessDirection direction,
                                                                    @NotNull PyResolveContext resolveContext) {
    return Collections.emptyList();
  }

  @Override
  public Object[] getCompletionVariants(String completionPrefix, PsiElement location, ProcessingContext context) {
    final List<Object> variants = new ArrayList<>();
    for (String attribute : myAttributes) {
      if (!attribute.equals(completionPrefix)) {
        variants.add(LookupElementBuilder.create(attribute).withIcon(
          IconManager.getInstance().getPlatformIcon(com.intellij.ui.PlatformIcons.Field)));
      }
    }
    return variants.toArray();
  }

  @Override
  public @Nullable String getName() {
    return "{" + StringUtil.join(myAttributes, ", ") + "}";
  }

  @Override
  public boolean isBuiltin() {
    return false;
  }

  @Override
  public void assertValid(String message) {
  }

  @Override
  public String toString() {
    return "PyStructuralType(" + StringUtil.join(myAttributes, ", ") + ")";
  }

  public boolean isInferredFromUsages() {
    return myInferredFromUsages;
  }

  public @NotNull Set<String> getAttributeNames() {
    return Collections.unmodifiableSet(myAttributes);
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    PyStructuralType type = (PyStructuralType)o;
    return myInferredFromUsages == type.myInferredFromUsages && Objects.equals(myAttributes, type.myAttributes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(myAttributes, myInferredFromUsages);
  }
}
