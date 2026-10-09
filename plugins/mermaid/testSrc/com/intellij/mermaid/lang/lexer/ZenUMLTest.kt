package com.intellij.mermaid.lang.lexer

import com.intellij.testFramework.junit5.fixture.TestFixtures
import org.junit.jupiter.api.Test

@TestFixtures
class ZenUMLTest {
  private val lexer by mermaidLexerFixture("zenUML")

  @Test
  fun `test zenUML`() {
    val content = """
    zenuml
      title Demo
      Alice->John: Hello John, how are you?
      John->Alice: Great!
      Alice->John: See you later!
    """.trimIndent()
    lexer.doTest(content)
  }
}
