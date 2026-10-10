// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.impl;

import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.tree.IElementType;
import com.jetbrains.python.psi.PyPlainStringElement;
import org.jetbrains.annotations.NotNull;

/**
 * @author Mikhail Golubev
 */
public class PyPlainStringElementImpl extends LeafPsiElement implements PyPlainStringElement {
  public PyPlainStringElementImpl(@NotNull IElementType type, CharSequence text) {
    super(type, text);
  }
}
