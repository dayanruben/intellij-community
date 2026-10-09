package com.intellij.mermaid.lang.lexer

import com.intellij.testFramework.junit5.fixture.TestFixtures
import org.junit.jupiter.api.Test

@TestFixtures
class MindmapTest {
  private val lexer by mermaidLexerFixture("mindmap")

  @Test
  fun `test simple`() {
    val content = """
    mindmap
      Root
        A
          B
          C    
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test node shapes`() {
    val content = """
    mindmap
      Root
        id[I am a square]
        id(I am a rounded square)
        id((I am a circle))
        id))I am a bang((
        id)I am a cloud(
        id{{I am a hexagon}}
        I am the default shape
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test double quoted node description`() {
    val content = """
    mindmap
      id["I am [(a)] square"]
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test icons`() {
    val content = """
    mindmap
      Root
        A
        ::icon(fa fa-book)
        B(B)
        ::icon(mdi mdi-skull-outline)
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test classes`() {
    val content = """
    mindmap
      Root
        A[A]
        :::urgent large
        B(B)
        C
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test id with colon`() {
    val content = """
    mindmap
      Root
        :
        i
        i:d
        i::d
        i:::d
        i::::d
        :id
        ::id
        :::id
        ::::id
    """.trimIndent()
    lexer.doTest(content)
  }

  @Test
  fun `test comments`() {
    val content = """
    mindmap
      Root
        %% A[A]
        %% :::urgent large
        B(B)
        C
    """.trimIndent()
    lexer.doTest(content)
  }
}
