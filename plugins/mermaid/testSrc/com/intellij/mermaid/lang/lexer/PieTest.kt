package com.intellij.mermaid.lang.lexer

import org.junit.jupiter.api.Test

class PieTest: MermaidLexerTestCase() {
  override val diagramName: String
    get() = "pie"

  @Test
  fun `test pie with title with newline at the end`() {
    val content = """
    pie
      title Pets will be available
    
    """.trimIndent()
    doTest(content)
  }

  @Test
  fun `test with value and newlines`() {
    val content = """
    pie
      title Pets adopted by volunteers
    
    
      "Dogs" : 386
    
    """.trimIndent()
    doTest(content)
  }

  @Test
  fun `test full complex`() {
    val content = """
    pie %% This is comment
      title Pets adopted by volunteers %% This is not comment
      "Dogs" : 386 %% This is comment
      %% This is comment
    """.trimIndent()
    doTest(content)
  }
}
