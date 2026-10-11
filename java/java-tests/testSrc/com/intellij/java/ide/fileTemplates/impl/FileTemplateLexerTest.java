/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.java.ide.fileTemplates.impl;

import com.intellij.ide.fileTemplates.impl.FileTemplateConfigurable;
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.LexerTestFixture;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.testFramework.junit5.fixture.TestFixtures;
import org.junit.jupiter.api.Test;

import static com.intellij.platform.testFramework.junit5.codeInsight.fixture.LexerTestFixtureKt.lexerFixture;

@TestFixtures
public class FileTemplateLexerTest {
  private final TestFixture<LexerTestFixture> lexer = lexerFixture("", () -> FileTemplateConfigurable.createDefaultLexer());

  @Test
  public void testEscapes() {
    lexer.get().doTest("\\#include foo $bar", """
      ESCAPE ('\\#')
      TEXT ('include foo ')
      MACRO ('$bar')""");
  }

  @Test
  public void testLiveTemplates() {
    lexer.get().doTest("#[[$FOO$]]#", """
      ESCAPE ('#[[')
      MACRO ('$FOO$')
      ESCAPE (']]#')""");
  }
}