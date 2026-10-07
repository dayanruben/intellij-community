/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.util.xml.stubs;

import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.TestDataFile;
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.DomFileDescription;
import com.intellij.util.xml.DomFileElement;
import com.intellij.util.xml.DomManager;
import com.intellij.util.xml.impl.DomManagerImpl;
import com.intellij.util.xml.stubs.model.Foo;

/**
 * @author Dmitry Avdeev
 */
public abstract class DomStubTest extends LightJavaCodeInsightFixtureTestCase {

  private static final String HTTP_FOO_DTD = "http://foo.dtd";
  private static final DomFileDescription<Foo> DOM_FILE_DESCRIPTION = new DomFileDescription<>(Foo.class, "foo", HTTP_FOO_DTD) {
    @Override
    public boolean hasStubs() {
      return true;
    }

    @Override
    public int getStubVersion() {
      return 0;
    }

    @Override
    protected void initializeFileDescription() {
      registerNamespacePolicy("foo", HTTP_FOO_DTD);
    }
  };

  public static ElementStub getRootStub(@TestDataFile String filePath, JavaCodeInsightTestFixture fixture) {
    return DomStubTestUtil.getRootStub(filePath, fixture);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    ((DomManagerImpl)DomManager.getDomManager(getProject())).registerFileDescription(DOM_FILE_DESCRIPTION, myFixture.getTestRootDisposable());
  }

  @Override
  protected String getBasePath() {
    return "/xml/dom-tests/testData/stubs";
  }

  protected ElementStub getRootStub(@TestDataFile String filePath) {
    return getRootStub(filePath, myFixture);
  }

  protected void doBuilderTest(@TestDataFile String file, String stubText) {
    DomStubTestUtil.doBuilderTest(file, stubText, myFixture);
  }

  protected <T extends DomElement> DomFileElement<T> prepare(@TestDataFile String path, Class<T> domClass) {
    return DomStubTestUtil.prepare(path, domClass, myFixture);
  }

  protected XmlFile prepareFile(String path) {
    return DomStubTestUtil.prepareFile(path, myFixture);
  }
}
