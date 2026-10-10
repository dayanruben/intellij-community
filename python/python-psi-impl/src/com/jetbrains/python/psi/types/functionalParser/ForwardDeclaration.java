// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.types.functionalParser;

import com.intellij.openapi.util.Pair;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class ForwardDeclaration<R, T> extends FunctionalParserBase<R, T> {
  private FunctionalParser<R, T> myParser = null;

  public static @NotNull <R, T> ForwardDeclaration<R, T> create() {
    return new ForwardDeclaration<>();
  }

  public @NotNull ForwardDeclaration<R, T> define(@NotNull FunctionalParser<R, T> parser) {
    myParser = parser;
    return this;
  }

  @Override
  public @NotNull Pair<R, State> parse(@NotNull List<Token<T>> tokens, @NotNull State state) throws ParserException {
    if (myParser != null) {
      return myParser.parse(tokens, state);
    }
    throw new IllegalStateException("Undefined forward parser declaration");
  }
}
