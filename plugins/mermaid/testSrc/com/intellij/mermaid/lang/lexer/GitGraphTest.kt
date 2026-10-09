package com.intellij.mermaid.lang.lexer

import org.junit.jupiter.api.Test

class GitGraphTest : MermaidLexerTestCase() {
  override val diagramName: String
    get() = "gitGraph"

  @Test
  fun `test simple git graph`() {
    val content = """
    gitGraph
      commit
      commit
      branch develop
      checkout develop
      commit
      commit
      checkout main
      merge develop
      commit
      commit
    """.trimIndent()
    doTest(content)
  }

  @Test
  fun `test commit id`() {
    val content = """
    gitGraph
      commit id: "Alpha"
    """.trimIndent()
    doTest(content)
  }

  @Test
  fun `test commit type`() {
    val content = """
    gitGraph
      commit id: "Normal"
      commit id: "Reverse" type: REVERSE
      commit type: HIGHLIGHT
    """.trimIndent()
    doTest(content)
  }

  @Test
  fun `test commit tags`() {
    val content = """
    gitGraph
      commit tag: "v1.0.0"
      commit id: "Reverse" type: REVERSE tag: "RC_1"
      commit tag: "8.8.4" type: HIGHLIGHT id: "Highlight"
    """.trimIndent()
    doTest(content)
  }

  @Test
  fun `test cherry pick`() {
    val content = """
    gitGraph
      cherry-pick id : "A"
    """.trimIndent()
    doTest(content)
  }

  @Test
  fun `test order`() {
    val content = """
    gitGraph
      branch test1 order: 1
    """.trimIndent()
    doTest(content)
  }

  @Test
  fun `test merge`() {
    val content = """
    gitGraph
      commit id: "7"
      checkout main
      merge nice_feature id: "customID" tag: "customTag" type: REVERSE
      checkout very_nice_feature
      commit id: "8"
      checkout main
      merge nice_feature type: REVERSE tag: "customTag" 
      commit id: "9"
    """.trimIndent()
    doTest(content)
  }

  @Test
  fun `test quoted branch names`() {
    val content = """
    gitGraph
      commit
      branch "branch"
      checkout "branch"
      commit
      checkout main
      merge "branch"
    """.trimIndent()
    doTest(content)
  }

  @Test
  fun `test colon`() {
    val content = """
    gitGraph:
      commit
    """.trimIndent()
    doTest(content)
  }

  @Test
  fun `test dir`() {
    val content = """
    gitGraph LR:
      commit
    """.trimIndent()
    doTest(content)
  }

  @Test
  fun `test another dir`() {
    val content = """
    gitGraph TB:
      commit
    """.trimIndent()
    doTest(content)
  }
}
