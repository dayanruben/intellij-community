// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.functionTypeComments;

import com.intellij.lang.SyntaxTreeBuilder;
import com.intellij.openapi.util.NlsContexts.ParsingError;
import com.intellij.psi.tree.IElementType;
import com.jetbrains.python.PyElementTypes;
import com.jetbrains.python.PyParsingBundle;
import com.jetbrains.python.PyTokenTypes;
import com.jetbrains.python.documentation.doctest.PyDoctestTokenTypes;
import com.jetbrains.python.parsing.ExpressionParsing;
import com.jetbrains.python.parsing.ParsingContext;
import com.jetbrains.python.parsing.PyParser;
import com.jetbrains.python.parsing.StatementParsing;
import com.jetbrains.python.psi.LanguageLevel;
import org.jetbrains.annotations.NotNull;

/**
 * @author Mikhail Golubev
 */
public class PyFunctionTypeAnnotationParser extends PyParser {
  @Override
  protected ParsingContext createParsingContext(SyntaxTreeBuilder builder, LanguageLevel languageLevel) {
    return new ParsingContext(builder, languageLevel) {
      private final StatementParsing myStatementParsing = new AnnotationParser(this);
      private final ExpressionParsing myExpressionParsing = new ExpressionParsing(this) {
        @Override
        protected IElementType getReferenceType() {
          return PyDoctestTokenTypes.DOC_REFERENCE;
        }
      };

      @Override
      public StatementParsing getStatementParser() {
        return myStatementParsing;
      }

      @Override
      public ExpressionParsing getExpressionParser() {
        return myExpressionParsing;
      }
    };
  }

  private static class AnnotationParser extends StatementParsing {
    AnnotationParser(ParsingContext context) {
      super(context);
    }

    @Override
    public void parseStatement() {
      if (myBuilder.eof()) return;
      parseFunctionType();
    }

    private void parseFunctionType() {
      if (atToken(PyTokenTypes.LPAR)) {
        final SyntaxTreeBuilder.Marker funcTypeMark = myBuilder.mark();
        parseParameterTypeList();
        checkMatches(PyTokenTypes.RARROW, PyParsingBundle.message("rarrow.expected"));
        final boolean parsed = getExpressionParser().parseSingleExpression(false);
        if (!parsed) {
          myBuilder.error(PyParsingBundle.message("PARSE.expected.expression"));
        }
        funcTypeMark.done(PyFunctionTypeAnnotationElementTypes.FUNCTION_SIGNATURE);
      }
      recoverUntilMatches(PyParsingBundle.message("unexpected.tokens"));
    }

    private void parseParameterTypeList() {
      assert atToken(PyTokenTypes.LPAR);
      final SyntaxTreeBuilder.Marker listMark = myBuilder.mark();
      myBuilder.advanceLexer();

      final ExpressionParsing exprParser = getExpressionParser();
      int paramCount = 0;
      while (!(atAnyOfTokens(PyTokenTypes.RPAR, PyTokenTypes.RARROW, PyTokenTypes.STATEMENT_BREAK) || myBuilder.eof())) {
        if (paramCount > 0) {
          checkMatches(PyTokenTypes.COMMA, PyParsingBundle.message("PARSE.expected.comma"));
        }
        boolean parsed;
        if (atToken(PyTokenTypes.MULT)) {
          final SyntaxTreeBuilder.Marker starMarker = myBuilder.mark();
          myBuilder.advanceLexer();
          parsed = exprParser.parseSingleExpression(false);
          starMarker.done(PyElementTypes.STAR_EXPRESSION);
        }
        else if (atToken(PyTokenTypes.EXP)) {
          final SyntaxTreeBuilder.Marker doubleStarMarker = myBuilder.mark();
          myBuilder.advanceLexer();
          parsed = exprParser.parseSingleExpression(false);
          doubleStarMarker.done(PyElementTypes.DOUBLE_STAR_EXPRESSION);
        }
        else {
          parsed = exprParser.parseSingleExpression(false);
        }
        if (!parsed) {
          myBuilder.error(PyParsingBundle.message("PARSE.expected.expression"));
          recoverUntilMatches(PyParsingBundle.message("PARSE.expected.expression"), PyTokenTypes.COMMA, PyTokenTypes.RPAR,
                              PyTokenTypes.RARROW, PyTokenTypes.STATEMENT_BREAK);
        }
        paramCount++;
      }
      checkMatches(PyTokenTypes.RPAR, PyParsingBundle.message("PARSE.expected.rpar"));
      listMark.done(PyFunctionTypeAnnotationElementTypes.PARAMETER_TYPE_LIST);
    }

    private void recoverUntilMatches(@NotNull @ParsingError String errorMessage, IElementType @NotNull ... types) {
      final SyntaxTreeBuilder.Marker errorMarker = myBuilder.mark();
      boolean hasNonWhitespaceTokens = false;
      while (!(atAnyOfTokens(types) || myBuilder.eof())) {
        // Regular whitespace tokens are already skipped by advancedLexer() 
        if (!atToken(PyTokenTypes.STATEMENT_BREAK)) {
          hasNonWhitespaceTokens = true;
        }
        myBuilder.advanceLexer();
      }
      if (hasNonWhitespaceTokens) {
        errorMarker.error(errorMessage);
      }
      else {
        errorMarker.drop();
      }
    }
  }
}
