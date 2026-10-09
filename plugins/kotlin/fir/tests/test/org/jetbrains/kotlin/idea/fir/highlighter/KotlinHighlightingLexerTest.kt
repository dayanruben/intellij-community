// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.fir.highlighter

import com.intellij.platform.testFramework.junit5.codeInsight.fixture.lexerFixture
import com.intellij.testFramework.junit5.fixture.TestFixtures
import org.jetbrains.kotlin.idea.highlighter.KotlinHighlightingLexer
import org.junit.jupiter.api.Test

@TestFixtures
class KotlinHighlightingLexerTest {
    private val lexer by lexerFixture("") { KotlinHighlightingLexer() }

    @Test
    fun testCharLiteralValidEscape() {
        lexer.doTest(
            "'\\n'", """CHARACTER_LITERAL (''')
                           |VALID_STRING_ESCAPE_TOKEN ('\n')
                           |CHARACTER_LITERAL (''')""".trimMargin()
        )
    }

    @Test
    fun testCharLiteralInvalidEscape() {
        lexer.doTest(
            "'\\q'", """CHARACTER_LITERAL (''')
                           |INVALID_CHARACTER_ESCAPE_TOKEN ('\q')
                           |CHARACTER_LITERAL (''')""".trimMargin()
        )
    }
}
