// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy.lang.parser;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.LexerTestFixture;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.testFramework.junit5.fixture.TestFixtures;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.lang.lexer.GroovyLexer;
import org.jetbrains.plugins.groovy.util.TestUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.intellij.platform.testFramework.junit5.codeInsight.fixture.LexerTestFixtureKt.lexerFixture;

@TestFixtures
public class GroovyLexerTest {
  private final TestFixture<LexerTestFixture> lexer = lexerFixture(TestUtils.getAbsoluteTestDataPath() + "lexer", () -> new GroovyLexer());

  private static String printTokens(@NotNull Lexer lexer, @NotNull CharSequence text) {
    lexer.start(text, 0, text.length());
    List<List<String>> tokens = new ArrayList<>(Arrays.asList(new ArrayList<>(Arrays.asList("offset", "state", "text", "type"))));
    Object tokenType;
    while ((tokenType = lexer.getTokenType()) != null) {
      tokens.add(List.of(String.valueOf(lexer.getTokenStart()), String.valueOf(lexer.getState()),
                         "'" + StringUtil.escapeLineBreak(lexer.getTokenText()) + "'", tokenType.toString()));
      lexer.advance();
    }

    return formatTable(tokens);
  }

  private static String formatTable(List<List<String>> tokens) {
    int[] max = new int[tokens.get(0).size()];
    for (List<String> token : tokens) {
      for (int i = 0; i < token.size(); i++) {
        String column = token.get(i);
        max[i] = Math.max(column.length(), max[i]);
      }
    }

    StringBuilder result = new StringBuilder();
    for (List<String> token : tokens) {
      for (int i = 0; i < token.size(); i++) {
        String column = token.get(i);
        int padding = Math.max(column.length(), max[i]) - column.length() + 1;
        result.append(column).append(" ".repeat(padding));
      }
      result.append("\n");
    }

    return result.toString();
  }

  @Test
  public void testComments() {
    String text = """
             /**/
             /***/
             //
             //
             
             //
             
             
             //
             """;
    LexerTestFixture fixture = lexer.get();
    PlatformTestUtil.assertSameLinesWithFile(fixture.getPathToTestDataFile(".txt"), printTokens(fixture.createLexer(), text));
    fixture.checkCorrectRestart(text);
  }
}
