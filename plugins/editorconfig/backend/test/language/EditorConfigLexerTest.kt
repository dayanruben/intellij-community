// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.editorconfig.language

import com.intellij.editorconfig.common.syntax.lexer.EditorConfigLexerAdapter
import com.intellij.openapi.application.ex.PathManagerEx
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.lexerFixture
import com.intellij.testFramework.junit5.fixture.TestFixtures
import org.junit.jupiter.api.Test

// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

@TestFixtures
class EditorConfigLexerTest {
  private val lexer by lexerFixture(
    "${PathManagerEx.getCommunityHomePath()}/plugins/editorconfig/testData/org/editorconfig/language/lexer"
  ) { EditorConfigLexerAdapter() }

  @Test
  fun testEmpty() = doTest()

  @Test
  fun testComment() = doTest()

  @Test
  fun testKeyValuePair() = doTest()

  @Test
  fun testSimpleSection() = doTest()

  @Test
  fun testCharClassSection() = doTest()

  @Test
  fun testVariantSection() = doTest()

  @Test
  fun testComplexSection() = doTest()

  @Test
  fun testWhitespacedComplexSection() = doTest()

  @Test
  fun testWhitespacedKeyValuePair() = doTest()

  @Test
  fun testQualifiedName() = doTest()

  private fun doTest() =
    lexer.doFileTest("editorconfig")
}
