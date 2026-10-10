// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.types.functionalParser;

import org.jetbrains.annotations.NotNull;

public final class ParserException extends Exception {
  private final @NotNull FunctionalParserBase.State myState;

  public ParserException(@NotNull String message, @NotNull FunctionalParserBase.State state) {
    super(message);
    myState = state;
  }

  @NotNull
  FunctionalParserBase.State getState() {
    return myState;
  }

  @Override
  public Throwable fillInStackTrace() {
    return this;
  }
}
