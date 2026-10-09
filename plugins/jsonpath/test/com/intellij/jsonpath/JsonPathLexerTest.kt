// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.jsonpath

import com.intellij.jsonpath.lexer.JsonPathLexer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.lexerFixture
import com.intellij.testFramework.junit5.fixture.TestFixtures
import org.junit.jupiter.api.Test

@TestFixtures
class JsonPathLexerTest {
  private val ROOT: String = "\$"
  private val lexer by lexerFixture("unused") { JsonPathLexer() }

  @Test
  fun testRoot() {
    lexer.doTest("\$", "\$ ('\$')")
    lexer.doTest("@", "@ ('@')")
    lexer.doTest("name", "IDENTIFIER ('name')")

    lexer.doTest("\$[0]", """
      $ROOT ('$ROOT')
      [ ('[')
      INTEGER_NUMBER ('0')
      ] (']')
    """.trimIndent())

    lexer.doTest("@[-100]", """
      @ ('@')
      [ ('[')
      INTEGER_NUMBER ('-100')
      ] (']')
    """.trimIndent())
  }

  @Test
  fun testDottedPaths() {
    lexer.doTest("\$.path", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('path')
    """.trimIndent())

    lexer.doTest("\$..path", """
      $ROOT ('$ROOT')
      .. ('..')
      IDENTIFIER ('path')
    """.trimIndent())

    lexer.doTest("@.path", """
      @ ('@')
      . ('.')
      IDENTIFIER ('path')
    """.trimIndent())

    lexer.doTest("@..path", """
      @ ('@')
      .. ('..')
      IDENTIFIER ('path')
    """.trimIndent())

    lexer.doTest("\$.path", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('path')
    """.trimIndent())

    lexer.doTest("\$.long.path.with.root", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('long')
      . ('.')
      IDENTIFIER ('path')
      . ('.')
      IDENTIFIER ('with')
      . ('.')
      IDENTIFIER ('root')
    """.trimIndent())

    lexer.doTest("@.long.path.with.eval", """
      @ ('@')
      . ('.')
      IDENTIFIER ('long')
      . ('.')
      IDENTIFIER ('path')
      . ('.')
      IDENTIFIER ('with')
      . ('.')
      IDENTIFIER ('eval')
    """.trimIndent())
  }

  @Test
  fun testQuotedPaths() {
    lexer.doTest("\$['quoted']['path']", """
      $ROOT ('$ROOT')
      [ ('[')
      SINGLE_QUOTED_STRING (''quoted'')
      ] (']')
      [ ('[')
      SINGLE_QUOTED_STRING (''path'')
      ] (']')
    """.trimIndent())
    lexer.doTest("\$.['quoted'].path", """
      $ROOT ('$ROOT')
      . ('.')
      [ ('[')
      SINGLE_QUOTED_STRING (''quoted'')
      ] (']')
      . ('.')
      IDENTIFIER ('path')
    """.trimIndent())
    lexer.doTest("\$.[\"quo\\ted\"]", """
      $ROOT ('$ROOT')
      . ('.')
      [ ('[')
      DOUBLE_QUOTED_STRING ('"quo\ted"')
      ] (']')
    """.trimIndent())
  }

  @Test
  fun testFilterExpression() {
    lexer.doTest("\$.demo[?(@.filter > 2)]", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('demo')
      [ ('[')
      ? ('?')
      ( ('(')
      @ ('@')
      . ('.')
      IDENTIFIER ('filter')
      WHITE_SPACE (' ')
      GT_OP ('>')
      WHITE_SPACE (' ')
      INTEGER_NUMBER ('2')
      ) (')')
      ] (']')
    """.trimIndent())

    lexer.doTest("\$.demo[?(@.filter == 7.2)]", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('demo')
      [ ('[')
      ? ('?')
      ( ('(')
      @ ('@')
      . ('.')
      IDENTIFIER ('filter')
      WHITE_SPACE (' ')
      EQ_OP ('==')
      WHITE_SPACE (' ')
      DOUBLE_NUMBER ('7.2')
      ) (')')
      ] (']')
    """.trimIndent())

    lexer.doTest("\$.demo[?(@.filter != 'value')]", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('demo')
      [ ('[')
      ? ('?')
      ( ('(')
      @ ('@')
      . ('.')
      IDENTIFIER ('filter')
      WHITE_SPACE (' ')
      NE_OP ('!=')
      WHITE_SPACE (' ')
      SINGLE_QUOTED_STRING (''value'')
      ) (')')
      ] (']')
    """.trimIndent())

    lexer.doTest("\$.demo[?(@.filter == true)]", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('demo')
      [ ('[')
      ? ('?')
      ( ('(')
      @ ('@')
      . ('.')
      IDENTIFIER ('filter')
      WHITE_SPACE (' ')
      EQ_OP ('==')
      WHITE_SPACE (' ')
      true ('true')
      ) (')')
      ] (']')
    """.trimIndent())

    lexer.doTest("\$.demo[?(@.filter != false)]", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('demo')
      [ ('[')
      ? ('?')
      ( ('(')
      @ ('@')
      . ('.')
      IDENTIFIER ('filter')
      WHITE_SPACE (' ')
      NE_OP ('!=')
      WHITE_SPACE (' ')
      false ('false')
      ) (')')
      ] (']')
    """.trimIndent())

    lexer.doTest("\$.demo[?(@.null != null)]", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('demo')
      [ ('[')
      ? ('?')
      ( ('(')
      @ ('@')
      . ('.')
      IDENTIFIER ('null')
      WHITE_SPACE (' ')
      NE_OP ('!=')
      WHITE_SPACE (' ')
      null ('null')
      ) (')')
      ] (']')
    """.trimIndent())

    lexer.doTest("\$.demo[?('a' in @.in)]", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('demo')
      [ ('[')
      ? ('?')
      ( ('(')
      SINGLE_QUOTED_STRING (''a'')
      WHITE_SPACE (' ')
      NAMED_OP ('in')
      WHITE_SPACE (' ')
      @ ('@')
      . ('.')
      IDENTIFIER ('in')
      ) (')')
      ] (']')
    """.trimIndent())
  }

  @Test
  fun testBooleanOperations() {
    lexer.doTest("\$.demo[?(@.a>=10 && $.b<=2)]", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('demo')
      [ ('[')
      ? ('?')
      ( ('(')
      @ ('@')
      . ('.')
      IDENTIFIER ('a')
      GE_OP ('>=')
      INTEGER_NUMBER ('10')
      WHITE_SPACE (' ')
      AND_OP ('&&')
      WHITE_SPACE (' ')
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('b')
      LE_OP ('<=')
      INTEGER_NUMBER ('2')
      ) (')')
      ] (']')
    """.trimIndent())
  }

  @Test
  fun testIndexExpression() {
    lexer.doTest("\$.demo[(@.length - 1)]", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('demo')
      [ ('[')
      ( ('(')
      @ ('@')
      . ('.')
      IDENTIFIER ('length')
      WHITE_SPACE (' ')
      MINUS_OP ('-')
      WHITE_SPACE (' ')
      INTEGER_NUMBER ('1')
      ) (')')
      ] (']')
    """.trimIndent())
  }

  @Test
  fun testRegexLiteral() {
    lexer.doTest("\$.demo[?(@.attr =~ /[a-z]/)]", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('demo')
      [ ('[')
      ? ('?')
      ( ('(')
      @ ('@')
      . ('.')
      IDENTIFIER ('attr')
      WHITE_SPACE (' ')
      RE_OP ('=~')
      WHITE_SPACE (' ')
      REGEX_STRING ('/[a-z]/')
      ) (')')
      ] (']')
    """.trimIndent())

    lexer.doTest("\$.demo[?(@.attr =~ /[0-9]/i)]", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('demo')
      [ ('[')
      ? ('?')
      ( ('(')
      @ ('@')
      . ('.')
      IDENTIFIER ('attr')
      WHITE_SPACE (' ')
      RE_OP ('=~')
      WHITE_SPACE (' ')
      REGEX_STRING ('/[0-9]/i')
      ) (')')
      ] (']')
    """.trimIndent())

    lexer.doTest("\$.demo[?(@.attr =~ /[0-9]/iu)]", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('demo')
      [ ('[')
      ? ('?')
      ( ('(')
      @ ('@')
      . ('.')
      IDENTIFIER ('attr')
      WHITE_SPACE (' ')
      RE_OP ('=~')
      WHITE_SPACE (' ')
      REGEX_STRING ('/[0-9]/iu')
      ) (')')
      ] (']')
    """.trimIndent())

    lexer.doTest("\$.demo[?(@ =~ /test/U)]", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('demo')
      [ ('[')
      ? ('?')
      ( ('(')
      @ ('@')
      WHITE_SPACE (' ')
      RE_OP ('=~')
      WHITE_SPACE (' ')
      REGEX_STRING ('/test/U')
      ) (')')
      ] (']')
    """.trimIndent())
  }

  @Test
  fun testWildcardMultiplyOperators() {
    lexer.doTest("\$.demo[*].demo[?(@.attr * 2 == 10)]", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('demo')
      [ ('[')
      * ('*')
      ] (']')
      . ('.')
      IDENTIFIER ('demo')
      [ ('[')
      ? ('?')
      ( ('(')
      @ ('@')
      . ('.')
      IDENTIFIER ('attr')
      WHITE_SPACE (' ')
      MULTIPLY_OP ('*')
      WHITE_SPACE (' ')
      INTEGER_NUMBER ('2')
      WHITE_SPACE (' ')
      EQ_OP ('==')
      WHITE_SPACE (' ')
      INTEGER_NUMBER ('10')
      ) (')')
      ] (']')
    """.trimIndent())
  }

  @Test
  fun testArrayLiteralsInCondition() {
    lexer.doTest("@[?(@.attr in [1, 2, 3])]", """
      @ ('@')
      [ ('[')
      ? ('?')
      ( ('(')
      @ ('@')
      . ('.')
      IDENTIFIER ('attr')
      WHITE_SPACE (' ')
      NAMED_OP ('in')
      WHITE_SPACE (' ')
      [ ('[')
      INTEGER_NUMBER ('1')
      , (',')
      WHITE_SPACE (' ')
      INTEGER_NUMBER ('2')
      , (',')
      WHITE_SPACE (' ')
      INTEGER_NUMBER ('3')
      ] (']')
      ) (')')
      ] (']')
    """.trimIndent())
  }

  @Test
  fun testObjectLiteralsInCondition() {
    lexer.doTest("\$[?(@.attr in {'a': 1, 'b': { }, 'c': [1, 2]})]", """
      $ROOT ('${'$'}')
      [ ('[')
      ? ('?')
      ( ('(')
      @ ('@')
      . ('.')
      IDENTIFIER ('attr')
      WHITE_SPACE (' ')
      NAMED_OP ('in')
      WHITE_SPACE (' ')
      { ('{')
      SINGLE_QUOTED_STRING (''a'')
      : (':')
      WHITE_SPACE (' ')
      INTEGER_NUMBER ('1')
      , (',')
      WHITE_SPACE (' ')
      SINGLE_QUOTED_STRING (''b'')
      : (':')
      WHITE_SPACE (' ')
      { ('{')
      WHITE_SPACE (' ')
      } ('}')
      , (',')
      WHITE_SPACE (' ')
      SINGLE_QUOTED_STRING (''c'')
      : (':')
      WHITE_SPACE (' ')
      [ ('[')
      INTEGER_NUMBER ('1')
      , (',')
      WHITE_SPACE (' ')
      INTEGER_NUMBER ('2')
      ] (']')
      } ('}')
      ) (')')
      ] (']')
    """.trimIndent())
  }

  @Test
  fun testNamedOperator() {
    lexer.doTest("\$.x[?(@.a in \$.b)].in.avg()", """
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('x')
      [ ('[')
      ? ('?')
      ( ('(')
      @ ('@')
      . ('.')
      IDENTIFIER ('a')
      WHITE_SPACE (' ')
      NAMED_OP ('in')
      WHITE_SPACE (' ')
      $ROOT ('$ROOT')
      . ('.')
      IDENTIFIER ('b')
      ) (')')
      ] (']')
      . ('.')
      IDENTIFIER ('in')
      . ('.')
      IDENTIFIER ('avg')
      ( ('(')
      ) (')')
    """.trimIndent())
  }

  @Test
  fun testArrayOnTheLeft() {
    lexer.doTest("@[?([1] contains 1)]", """
      @ ('@')
      [ ('[')
      ? ('?')
      ( ('(')
      [ ('[')
      INTEGER_NUMBER ('1')
      ] (']')
      WHITE_SPACE (' ')
      NAMED_OP ('contains')
      WHITE_SPACE (' ')
      INTEGER_NUMBER ('1')
      ) (')')
      ] (']')
    """.trimIndent())
  }
}