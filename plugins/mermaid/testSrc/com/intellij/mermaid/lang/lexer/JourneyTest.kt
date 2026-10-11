package com.intellij.mermaid.lang.lexer

import com.intellij.testFramework.junit5.fixture.TestFixtures
import org.junit.jupiter.api.Test

@TestFixtures
class JourneyTest {
  private val lexer by mermaidLexerFixture("journey")

  @Test
  fun `test simple journey title and section title`() {
    val content = """
    journey
      title My working day
      section Go to work
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test journey title and section title with whitespaces`() {
    val content = """
    journey
      title     My working day
      section       Go to work
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test journey title and section title with whitespaces and sharp`() {
    val content = """
    journey
      title     My working# day
      section       Go to# work
    
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test journey with one section and tasks`() {
    val content = """
    journey
      title My working day
      section Go to work
        Make tea: 5: Me
        Go upstairs: 3: Me
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test journey with sharp after task`() {
    val content = """
    journey
      title My working day
      section Go to work
        Make tea: 5: Me#123
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test journey with sharp in task`() {
    val content = """
    journey
      title My working day
      section Go to work
        Make tea: #5: Me
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test journey with two sections`() {
    val content = """
    journey
      title My working day
      section Go to work
        Make tea: 5: Me
      section Go home
        Go downstairs: 5: Me
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test journey task with whitespaces`() {
    val content = """
    journey
      title My working day
      section Go to work
            Make tea  :    5   :   Me
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test journey`() {
    val content = """
    journey
      title My working day
      section Go to work
         : Make tea: 5: Me
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test journey with comments`() {
    val content = """
    journey %% This is comment 
      title My working day %% This is not comment
      section Go to work %% This is not comment
        Make tea: 5: Me %% This is not comment
        %% This is comment
    """.trimIndent()
    lexer.doTest(content)
  }
}
