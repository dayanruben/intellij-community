package com.intellij.mermaid.lang.lexer

import com.intellij.mermaid.lang.MermaidTestingUtil
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.LexerTestFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.lexerFixture
import com.intellij.testFramework.junit5.fixture.TestFixture

/**
 * Creates a [LexerTestFixture] for [MermaidLexer] with the expected files of [diagramName].
 */
internal fun mermaidLexerFixture(diagramName: String): TestFixture<LexerTestFixture> =
  lexerFixture("${MermaidTestingUtil.TEST_DATA_PATH}/lexer/$diagramName") { MermaidLexer() }
