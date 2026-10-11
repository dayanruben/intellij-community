package com.intellij.mermaid.lang.lexer

import com.intellij.testFramework.junit5.fixture.TestFixtures
import org.junit.jupiter.api.Test

@TestFixtures
class LexerSanityTest {
  private val lexer by mermaidLexerFixture("common")

  @Test
  fun `test line comment`() {
    val content = """
    %% This is comment
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test line comment not eating next newline`() {
    val content = """
    %% This is comment
    
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test empty directive`() {
    val content = """
    %%{}%%
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test empty directive with whitespaces`() {
    val content = """
    %%{    }%%
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test directive with single simple numeric property`() {
    val content = """
    %%{ some: 42 }%%
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test directive with single simple quoted property`() {
    val content = """
    %%{ some: "42" }%%
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test directive with multiple simple properties`() {
    val content = """
    %%{ some: "42", other: 42, more: "value" }%%
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test directive with single simple property and whitespaces and newlines`() {
    val content = """
    %%{   some
      
      
      :
       
       
       42
         
         
         }%%
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test packet diagram`() {
    val content = """
    packet-beta
      0-15: "Source Port"
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test architecture diagram`() {
    val content = """
    architecture-beta
      group api(cloud)[API]
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test kanban diagram`() {
    val content = """
    kanban
      Todo
        [Create documentation]
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test bare sankey spelling`() {
    val content = """
    sankey
      a,b,1
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test bare xychart spelling`() {
    val content = """
    xychart
      line [1, 2, 3]
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test bare block spelling`() {
    val content = """
    block
      a b c
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test railroad diagram variant`() {
    val content = """
    railroad-ebnf-beta
      expr = term , { "+" , term } ;
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test cynefin diagram`() {
    val content = """
    cynefin-beta
      title Decisions
      domain complex
    """.trimIndent()
    lexer.doTest(content)
  }

  // The point of the generic fallback: an unmodelled family still gets comments, accessibility
  // statements and frontmatter rather than turning the whole file into one error.
  @Test
  fun `test generic diagram keeps comments and acc statements`() {
    val content = """
    treemap-beta
    %% a comment
      accTitle: Treemap title
      "Section"
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test generic diagram after frontmatter`() {
    val content = """
    ---
    title: Packets
    ---
    packet
      0-15: "Source Port"
    """.trimIndent()
    lexer.doTest(content)
  }

  // swimlane-beta reuses the flowchart grammar rather than the generic fallback, because upstream has no
  // swimlane parser of its own.
  @Test
  fun `test swimlane reuses flowchart`() {
    val content = """
    swimlane-beta LR
      A --> B
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test bare requirement spelling`() {
    val content = """
    requirement
      requirement test_req {
      id: 1
      text: some text
      risk: high
      verifymethod: test
      }
    """.trimIndent()
    lexer.doTest(content)
  }
}
