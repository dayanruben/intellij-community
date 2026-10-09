package com.intellij.mermaid.lang.lexer

import com.intellij.testFramework.junit5.fixture.TestFixtures
import org.junit.jupiter.api.Test

@TestFixtures
class PieTest {
  private val lexer by mermaidLexerFixture("pie")

  @Test
  fun `test pie with title with newline at the end`() {
    val content = """
    pie
      title Pets will be available
    
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test with value and newlines`() {
    val content = """
    pie
      title Pets adopted by volunteers
    
    
      "Dogs" : 386
    
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test full complex`() {
    val content = """
    pie %% This is comment
      title Pets adopted by volunteers %% This is not comment
      "Dogs" : 386 %% This is comment
      %% This is comment
    """.trimIndent()
    lexer.doTest(content)
  }
}
