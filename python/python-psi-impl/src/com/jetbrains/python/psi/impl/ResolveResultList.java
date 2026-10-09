// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.impl;

import com.google.common.collect.Lists;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.impl.light.LightElement;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.python.psi.PyImportedNameDefiner;
import com.jetbrains.python.psi.resolve.ImportedResolveResult;
import com.jetbrains.python.psi.resolve.RatedResolveResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ResolveResultList extends ArrayList<RatedResolveResult> {
  public static List<RatedResolveResult> to(PsiElement element) {
    if (element == null) {
      return Collections.emptyList();
    }
    final ResolveResultList list = new ResolveResultList();
    list.poke(element, RatedResolveResult.RATE_NORMAL);
    return list;
  }

  public static List<? extends RatedResolveResult> asImportedResults(@Nullable List<? extends RatedResolveResult> from,
                                                                     @Nullable PyImportedNameDefiner nameDefiner) {
    if (ContainerUtil.isEmpty(from)) {
      return Collections.emptyList();
    }
    return ContainerUtil.map(from, res -> new ImportedResolveResult(res.getElement(), res.getRate(), nameDefiner));
  }


  public static List<PsiElement> getElements(@NotNull List<? extends ResolveResult> from) {
    if (from.isEmpty()) return Collections.emptyList();
    return Lists.transform(from, res -> res != null ? res.getElement() : null);
  }

  // Allows to add non-null elements and discard nulls in a hassle-free way.

  public boolean poke(final PsiElement what, final int rate) {
    PyPsiUtils.assertValid(what);
    if (what == null) return false;
    if (!(what instanceof LightElement) && !what.isValid()) {
      throw new PsiInvalidElementAccessException(what, "Trying to resolve a reference to an invalid element");
    }
    super.add(new RatedResolveResult(rate, what));
    return true;
  }
}
