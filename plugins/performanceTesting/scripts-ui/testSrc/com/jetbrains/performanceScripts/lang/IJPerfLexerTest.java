package com.jetbrains.performanceScripts.lang;

import com.intellij.openapi.application.PathManager;
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.LexerTestFixture;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.testFramework.junit5.fixture.TestFixtures;
import com.jetbrains.performanceScripts.lang.lexer.IJPerfLexerAdapter;
import org.junit.jupiter.api.Test;

import static com.intellij.platform.testFramework.junit5.codeInsight.fixture.LexerTestFixtureKt.lexerFixture;

@TestFixtures
public class IJPerfLexerTest {
  private final TestFixture<LexerTestFixture> lexer =
    lexerFixture(PathManager.getCommunityHomePath() + TestUtil.getDataSubPath("lexer"), () -> new IJPerfLexerAdapter());

  @Test
  public void testDoublePrefixCommand() {
    doTest();
  }

  @Test
  public void testSinglePrefixCommand() {
    doTest();
  }

  @Test
  public void testSpaceSeparatedParameters() {
    doTest();
  }

  @Test
  public void testSpaceSeparatedParametersValue() {
    doTest();
  }

  @Test
  public void testCommandWithoutParams() {
    doTest();
  }

  @Test
  public void testOptionWithValue() {
    doTest();
  }

  @Test
  public void testNumberOptions() {
    doTest();
  }

  @Test
  public void testPipeSeparatedOption() {
    doTest();
  }

  @Test
  public void testComment() {
    doTest();
  }

  @Test
  public void testFilePathInParameters() {
    doTest();
  }

  @Test
  public void testScriptWithEmptyLines() {
    doTest();
  }

  @Test
  public void testTextOptionWithSymbols() {
    doTest();
  }

  private void doTest() {
    lexer.get().doFileTest(IJPerfFileType.DEFAULT_EXTENSION);
  }
}