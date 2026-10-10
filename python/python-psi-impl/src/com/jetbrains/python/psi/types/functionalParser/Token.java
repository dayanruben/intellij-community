// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.types.functionalParser;

import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;

public class Token<T> {
  private final @NotNull CharSequence myText;
  private final @NotNull TextRange myRange;
  private final @NotNull T myType;

  public Token(@NotNull T type, @NotNull CharSequence text, @NotNull TextRange range) {
    myText = text;
    myRange = range;
    myType = type;
  }

  public @NotNull T getType() {
    return myType;
  }

  public @NotNull CharSequence getText() {
    return myText;
  }

  public @NotNull TextRange getRange() {
    return myRange;
  }

  @Override
  public String toString() {
    return String.format("Token(<%s>, \"%s\", %s)", myType, myText, myRange);
  }
}
