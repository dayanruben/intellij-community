// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.restructuredtext;

import com.intellij.python.community.helpersLocator.PythonHelpersLocator;
import com.intellij.restructuredtext.parsing.RestParserDefinition;
import com.intellij.testFramework.ParsingTestCase;

/**
 * User : catherine
 */
public class RestParsingTest extends ParsingTestCase {

  public RestParsingTest() {
    super("", "rst", new RestParserDefinition());
  }

  public void testTitle() {
    doTest(true);
  }

  public void testInjection() {
    doTest(true);
  }

  public void testReference() {
    doTest(true);
  }

  public void testReferenceTarget() {
    doTest(true);
  }

  public void testSubstitution() {
    doTest(true);
  }

  public void testDirectiveWithNewLine() {
    doTest(true);
  }

  @Override
  protected String getTestDataPath() {
    return PythonHelpersLocator.getPythonCommunityPath() + "/../plugins/restructuredtext/testData/psi";
  }


  @Override
  protected boolean checkAllPsiRoots() {
    return false;
  }
}
