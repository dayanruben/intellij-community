// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.typeHints;

import com.intellij.lang.SyntaxTreeBuilder;
import com.intellij.psi.tree.IElementType;
import com.jetbrains.python.documentation.doctest.PyDoctestTokenTypes;
import com.jetbrains.python.parsing.ExpressionParsing;
import com.jetbrains.python.parsing.ParsingContext;
import com.jetbrains.python.parsing.PyParser;
import com.jetbrains.python.parsing.StatementParsing;
import com.jetbrains.python.psi.LanguageLevel;
import org.jetbrains.annotations.NotNull;

class PyTypeHintParser extends PyParser {

  @Override
  protected @NotNull ParsingContext createParsingContext(@NotNull SyntaxTreeBuilder builder, @NotNull LanguageLevel languageLevel) {
    return new ParsingContext(builder, languageLevel) {

      private final @NotNull StatementParsing myStatementParsing = new StatementParsing(this) {
        @Override
        protected @NotNull IElementType getReferenceType() {
          return PyDoctestTokenTypes.DOC_REFERENCE;
        }
      };

      private final @NotNull ExpressionParsing myExpressionParsing = new ExpressionParsing(this) {
        @Override
        protected @NotNull IElementType getReferenceType() {
          return PyDoctestTokenTypes.DOC_REFERENCE;
        }
      };

      @Override
      public @NotNull StatementParsing getStatementParser() {
        return myStatementParsing;
      }

      @Override
      public @NotNull ExpressionParsing getExpressionParser() {
        return myExpressionParsing;
      }
    };
  }
}
