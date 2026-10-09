// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.testFramework.junit5.codeInsight.fixture

import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.platform.syntax.util.lexer.LexerBase
import com.intellij.testFramework.fixtures.IdeaTestExecutionPolicy
import com.intellij.testFramework.junit5.fixture.TestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@TestFixtures
class SyntaxLexerTestFixtureTest {
  private val fixture by syntaxLexerFixture("lexer/data") { SyntaxWordLexer(restartable = true) }
  private val absoluteFixture by syntaxLexerFixture(IdeaTestExecutionPolicy.getHomePathWithPolicy() + "/lexer/data") { SyntaxWordLexer(restartable = true) }
  private val brokenRestartFixture by syntaxLexerFixture("lexer/data") { SyntaxWordLexer(restartable = false) }
  private val noRestartCheckFixture by syntaxLexerFixture("lexer/data", checkRestart = false) { SyntaxWordLexer(restartable = false) }

  @Test
  fun testInlineExpected() {
    fixture.doTest("ab  cd\nef", """
      WORD ('ab')
      SPACE ('  ')
      WORD ('cd\nef')
    """.trimIndent())
  }

  @Test
  fun testMismatch() {
    assertThrows<AssertionError> {
      fixture.doTest("ab", "SPACE ('ab')")
    }
  }

  @Test
  fun testExplicitLexer() {
    fixture.doTest("ab cd", "WORD ('ab')\nSPACE (' ')\nWORD ('cd')", SyntaxWordLexer(restartable = true))
  }

  @Test
  fun testBrokenRestart() {
    val expected = "WORD ('ab')\nSPACE (' ')\nWORD ('cd')"
    assertThrows<AssertionError> {
      brokenRestartFixture.doTest("ab cd", expected)
    }
    noRestartCheckFixture.doTest("ab cd", expected)
  }

  @Test
  fun testPathToTestDataFile() {
    assertEquals("pathToTestDataFile", fixture.testName)
    assertEquals(IdeaTestExecutionPolicy.getHomePathWithPolicy() + "/lexer/data/pathToTestDataFile.txt", fixture.getPathToTestDataFile(".txt"))
  }

  @Test
  fun testAbsoluteDirPath() {
    assertEquals(fixture.getPathToTestDataFile(".txt"), absoluteFixture.getPathToTestDataFile(".txt"))
  }

  @Test
  fun `test name with spaces`() {
    assertEquals("name_with_spaces", fixture.testName)
  }
}

private val SYNTAX_WORD = SyntaxElementType("WORD")
private val SYNTAX_SPACE = SyntaxElementType("SPACE")

/**
 * When [restartable] is `false`, the lexer ignores the start offset and always lexes from the start of the buffer.
 */
private class SyntaxWordLexer(private val restartable: Boolean) : LexerBase() {
  private var buffer: CharSequence = ""
  private var endOffset = 0
  private var tokenStart = 0
  private var tokenEnd = 0

  override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    this.buffer = buffer
    this.endOffset = endOffset
    tokenEnd = if (restartable) startOffset else 0
    advance()
  }

  override fun advance() {
    tokenStart = tokenEnd
    if (tokenStart < endOffset) {
      val space = buffer[tokenStart] == ' '
      while (tokenEnd < endOffset && (buffer[tokenEnd] == ' ') == space) {
        tokenEnd++
      }
    }
  }

  override fun getState(): Int = 0

  override fun getTokenType(): SyntaxElementType? = when {
    tokenStart >= endOffset -> null
    buffer[tokenStart] == ' ' -> SYNTAX_SPACE
    else -> SYNTAX_WORD
  }

  override fun getTokenStart(): Int = tokenStart

  override fun getTokenEnd(): Int = tokenEnd

  override fun getBufferSequence(): CharSequence = buffer

  override fun getBufferEnd(): Int = endOffset
}
