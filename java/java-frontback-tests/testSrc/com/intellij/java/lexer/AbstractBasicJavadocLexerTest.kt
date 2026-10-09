// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.lexer

import com.intellij.platform.syntax.lexer.Lexer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.syntaxLexerFixture
import com.intellij.testFramework.junit5.fixture.TestFixtures
import org.junit.jupiter.api.Test

@TestFixtures
abstract class AbstractBasicJavadocLexerTest {
  private val lexer by syntaxLexerFixture("") { createLexer() }

  protected abstract fun createLexer(): Lexer

  protected fun doTest(text: String, expected: String) {
    lexer.doTest(text, expected)
  }

  @Test
  fun testSnippetAttributes() {
    doTest("""
      /**
      * {@snippet attr1 = "attr1 value" attr2 = 'attr2 value' attr3 = attr3Value : 
      *    foo {} 
      * }
      */
      """.trimIndent().trim(),
           """DOC_COMMENT_START ('/**')
DOC_SPACE ('\n')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA (' ')
DOC_INLINE_TAG_START ('{')
DOC_TAG_NAME ('@snippet')
DOC_SPACE (' ')
DOC_TAG_VALUE_TOKEN ('attr1')
DOC_SPACE (' ')
DOC_TAG_VALUE_TOKEN ('=')
DOC_SPACE (' ')
DOC_TAG_VALUE_QUOTE ('"')
DOC_TAG_VALUE_TOKEN ('attr1 value')
DOC_TAG_VALUE_QUOTE ('"')
DOC_SPACE (' ')
DOC_TAG_VALUE_TOKEN ('attr2')
DOC_SPACE (' ')
DOC_TAG_VALUE_TOKEN ('=')
DOC_SPACE (' ')
DOC_TAG_VALUE_QUOTE (''')
DOC_TAG_VALUE_TOKEN ('attr2 value')
DOC_TAG_VALUE_QUOTE (''')
DOC_SPACE (' ')
DOC_TAG_VALUE_TOKEN ('attr3')
DOC_SPACE (' ')
DOC_TAG_VALUE_TOKEN ('=')
DOC_SPACE (' ')
DOC_TAG_VALUE_TOKEN ('attr3Value')
DOC_SPACE (' ')
DOC_TAG_VALUE_COLON (':')
DOC_SPACE (' \n')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA ('    foo {}')
DOC_SPACE (' \n')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA (' ')
DOC_INLINE_TAG_END ('}')
DOC_SPACE ('\n')
DOC_COMMENT_END ('*/')""")
  }

  @Test
  fun testAttributeNextToColon() {
    doTest("""
      /**
      * {@snippet attr1 = attrVal: 
      *    foo {} 
      * }
      */
      """.trimIndent().trim(),
           """DOC_COMMENT_START ('/**')
DOC_SPACE ('\n')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA (' ')
DOC_INLINE_TAG_START ('{')
DOC_TAG_NAME ('@snippet')
DOC_SPACE (' ')
DOC_TAG_VALUE_TOKEN ('attr1')
DOC_SPACE (' ')
DOC_TAG_VALUE_TOKEN ('=')
DOC_SPACE (' ')
DOC_TAG_VALUE_TOKEN ('attrVal')
DOC_TAG_VALUE_COLON (':')
DOC_SPACE (' \n')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA ('    foo {}')
DOC_SPACE (' \n')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA (' ')
DOC_INLINE_TAG_END ('}')
DOC_SPACE ('\n')
DOC_COMMENT_END ('*/')""")
  }

  @Test
  fun testAttributeListOnSeveralLines() {
    doTest("""
      /**
       * Attributes:
       * {@snippet attr1=""
       * attr2=''
       *  :
       * }
       */
      """.trimIndent().trim(),
           """DOC_COMMENT_START ('/**')
DOC_SPACE ('\n ')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA (' Attributes:')
DOC_SPACE ('\n ')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA (' ')
DOC_INLINE_TAG_START ('{')
DOC_TAG_NAME ('@snippet')
DOC_SPACE (' ')
DOC_TAG_VALUE_TOKEN ('attr1')
DOC_TAG_VALUE_TOKEN ('=')
DOC_TAG_VALUE_QUOTE ('"')
DOC_TAG_VALUE_QUOTE ('"')
DOC_SPACE ('\n ')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_SPACE (' ')
DOC_TAG_VALUE_TOKEN ('attr2')
DOC_TAG_VALUE_TOKEN ('=')
DOC_TAG_VALUE_QUOTE (''')
DOC_TAG_VALUE_QUOTE (''')
DOC_SPACE ('\n ')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_SPACE ('  ')
DOC_TAG_VALUE_COLON (':')
DOC_SPACE ('\n ')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA (' ')
DOC_INLINE_TAG_END ('}')
DOC_SPACE ('\n ')
DOC_COMMENT_END ('*/')""")
  }


  @Test
  fun testColonOnNextLine() {
    doTest("""
      /**
       * {@snippet 
       * :
       * }
       */
      """.trimIndent().trim(),
           """DOC_COMMENT_START ('/**')
DOC_SPACE ('\n ')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA (' ')
DOC_INLINE_TAG_START ('{')
DOC_TAG_NAME ('@snippet')
DOC_SPACE (' \n ')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_SPACE (' ')
DOC_TAG_VALUE_COLON (':')
DOC_SPACE ('\n ')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA (' ')
DOC_INLINE_TAG_END ('}')
DOC_SPACE ('\n ')
DOC_COMMENT_END ('*/')""")
  }

  @Test
  fun testBalancedBraces() {
    doTest("""
      /**
       * {@snippet 
       * :
       *     B0 {
       *     {
       *
       *     }
       *     B1 }
       * }
       */
      """.trimIndent().trim(),
           """DOC_COMMENT_START ('/**')
DOC_SPACE ('\n ')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA (' ')
DOC_INLINE_TAG_START ('{')
DOC_TAG_NAME ('@snippet')
DOC_SPACE (' \n ')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_SPACE (' ')
DOC_TAG_VALUE_COLON (':')
DOC_SPACE ('\n ')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA ('     B0 {')
DOC_SPACE ('\n ')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA ('     {')
DOC_SPACE ('\n ')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_SPACE ('\n ')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA ('     }')
DOC_SPACE ('\n ')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA ('     B1 }')
DOC_SPACE ('\n ')
DOC_COMMENT_LEADING_ASTERISKS ('*')
DOC_COMMENT_DATA (' ')
DOC_INLINE_TAG_END ('}')
DOC_SPACE ('\n ')
DOC_COMMENT_END ('*/')""")
  }

  @Test
  fun testParameterized() {
    doTest("///@see List<List<List>>", """
DOC_COMMENT_LEADING_ASTERISKS ('///')
DOC_TAG_NAME ('@see')
DOC_SPACE (' ')
DOC_TAG_VALUE_TOKEN ('List')
DOC_TAG_VALUE_LT ('<')
DOC_TAG_VALUE_TOKEN ('List')
DOC_TAG_VALUE_LT ('<')
DOC_TAG_VALUE_TOKEN ('List')
DOC_TAG_VALUE_GT ('>')
DOC_TAG_VALUE_GT ('>')""")
  }

  @Test
  fun testParameterized02() {
    doTest("///@see #meth(List<List<Int>>)", """
DOC_COMMENT_LEADING_ASTERISKS ('///')
DOC_TAG_NAME ('@see')
DOC_SPACE (' ')
DOC_TAG_VALUE_SHARP_TOKEN ('#')
DOC_TAG_VALUE_TOKEN ('meth')
DOC_TAG_VALUE_LPAREN ('(')
DOC_TAG_VALUE_TOKEN ('List')
DOC_TAG_VALUE_LT ('<')
DOC_TAG_VALUE_TOKEN ('List')
DOC_TAG_VALUE_LT ('<')
DOC_TAG_VALUE_TOKEN ('Int')
DOC_TAG_VALUE_GT ('>')
DOC_TAG_VALUE_GT ('>')
DOC_TAG_VALUE_RPAREN (')')
""")
  }

  @Test
  fun testParameterizedMalformed() {
    doTest("""
      /// {@link List<}
      /// @see #classic(List<)
    """.trimIndent(), """
DOC_COMMENT_LEADING_ASTERISKS ('///')
DOC_SPACE (' ')
DOC_INLINE_TAG_START ('{')
DOC_TAG_NAME ('@link')
DOC_SPACE (' ')
DOC_TAG_VALUE_TOKEN ('List')
DOC_TAG_VALUE_LT ('<')
DOC_INLINE_TAG_END ('}')
DOC_SPACE ('\n')
DOC_COMMENT_LEADING_ASTERISKS ('///')
DOC_COMMENT_DATA (' ')
DOC_TAG_NAME ('@see')
DOC_SPACE (' ')
DOC_TAG_VALUE_SHARP_TOKEN ('#')
DOC_TAG_VALUE_TOKEN ('classic')
DOC_TAG_VALUE_LPAREN ('(')
DOC_TAG_VALUE_TOKEN ('List')
DOC_TAG_VALUE_LT ('<')
DOC_TAG_VALUE_RPAREN (')')""")
  }

  @Test
  fun testParameterizedMarkdown() {
    doTest("///[List<List<List>>]", """
DOC_COMMENT_LEADING_ASTERISKS ('///')
DOC_LBRACKET ('[')
DOC_COMMENT_DATA ('List')
DOC_LT ('<')
DOC_COMMENT_DATA ('List')
DOC_LT ('<')
DOC_COMMENT_DATA ('List')
DOC_GT ('>')
DOC_GT ('>')
DOC_RBRACKET (']')
""")
  }

  @Test
  fun testParameterizedMarkdown02() {
    doTest("///[#meth(List<List<Int>>)]", """
DOC_COMMENT_LEADING_ASTERISKS ('///')
DOC_LBRACKET ('[')
DOC_SHARP ('#')
DOC_COMMENT_DATA ('meth')
DOC_LPAREN ('(')
DOC_COMMENT_DATA ('List')
DOC_LT ('<')
DOC_COMMENT_DATA ('List')
DOC_LT ('<')
DOC_COMMENT_DATA ('Int')
DOC_GT ('>')
DOC_GT ('>')
DOC_RPAREN (')')
DOC_RBRACKET (']')
""")
  }
  
  @Test
  fun testTagWithoutName() {
    doTest("""///{@} hewwo""", """
      DOC_COMMENT_LEADING_ASTERISKS ('///')
      DOC_INLINE_TAG_START ('{')
      DOC_COMMENT_BAD_CHARACTER ('@')
      DOC_INLINE_TAG_END ('}')
      DOC_COMMENT_DATA (' hewwo')
    """.trimIndent())
  }
}