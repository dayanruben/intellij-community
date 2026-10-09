package com.intellij.mermaid.lang.lexer

import com.intellij.mermaid.lang.MermaidTestingUtil
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.lexerFixture
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures

@TestFixtures
abstract class MermaidLexerTestCase {
  abstract val diagramName: String

  private val dirPath = "${MermaidTestingUtil.TEST_DATA_PATH}/lexer"

  private val lexer by lexerFixture(dirPath) { MermaidLexer() }

  protected fun doTest(text: String, expected: String? = null) {
    if (expected != null) {
      lexer.doTest(text, expected)
      return
    }
    val testName = lexer.testName.trimStart().replace(' ', '_')
    PlatformTestUtil.assertSameLinesWithFile("$dirPath/$diagramName/$testName.txt", lexer.printTokens(text, 0))
    lexer.checkCorrectRestart(text)
  }
}
