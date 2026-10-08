/*
 * Copyright 2000-2016 JetBrains s.r.o.
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

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileFilter;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.TestDataFile;
import com.intellij.testFramework.TestDataPath;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.DomFileElement;
import com.intellij.util.xml.DomManager;
import com.intellij.util.xml.DomUtil;
import com.intellij.util.xml.GenericAttributeValue;
import com.intellij.util.xml.GenericDomValue;
import com.intellij.util.xml.stubs.model.Bar;
import com.intellij.util.xml.stubs.model.Foo;
import com.intellij.util.xml.stubs.model.NotStubbed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static com.intellij.platform.testFramework.junit5.codeInsight.fixture.CodeInsightFixtureKt.codeInsightFixture;
import static com.intellij.testFramework.EdtTestUtil.runInEdtAndWait;
import static com.intellij.testFramework.JavaCodeInsightFixtureKt.setUpJdk;
import static com.intellij.testFramework.junit5.fixture.FixturesKt.moduleFixture;
import static com.intellij.testFramework.junit5.fixture.FixturesKt.projectFixture;
import static com.intellij.testFramework.junit5.fixture.FixturesKt.tempPathFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Dmitry Avdeev
 */
@TestApplication
@TestDataPath("$PROJECT_ROOT/community/xml/dom-tests/testData/stubs")
public class DomStubUsingTest {
  @SuppressWarnings("deprecation")
  private static final TestFixture<Project> projectFixture = projectFixture(tempPathFixture(), OpenProjectTask.build(), true);

  private final TestFixture<Path> pathFixture = tempPathFixture();
  private final TestFixture<Module> moduleFixture = moduleFixture(projectFixture, pathFixture, true);
  private final TestFixture<CodeInsightTestFixture> codeInsightFixture = codeInsightFixture(projectFixture, pathFixture);

  @BeforeEach
  void setUp() {
    DomStubTestUtil.registerFooFileDescription(projectFixture.get(), codeInsightFixture.get().getTestRootDisposable());
  }

  @Test
  public void testFoo() {
    runInEdtAndWait(() -> {
      DomFileElement<Foo> fileElement = prepare("foo.xml", Foo.class);
      PsiFile file = fileElement.getFile();
      assertFalse(file.getNode().isParsed());

      Foo foo = fileElement.getRootElement();
      assertEquals("foo", foo.getId().getValue());
      assertFalse(file.getNode().isParsed());

      List<Bar> bars = foo.getBars();
      assertFalse(file.getNode().isParsed());

      final List<GenericDomValue<String>> listElements = foo.getLists();
      final GenericDomValue<String> listElement0 = listElements.get(0);
      assertEquals("list0", listElement0.getValue());
      final GenericDomValue<String> listElement1 = listElements.get(1);
      assertEquals("list1", listElement1.getValue());
      assertFalse(file.getNode().isParsed());

      assertEquals(2, bars.size());
      Bar bar = bars.get(0);
      String value = bar.getString().getStringValue();
      assertEquals("xxx", value);

      Object o = bar.getString().getValue();
      assertEquals("xxx", o);

      Integer integer = bar.getInt().getValue();
      assertEquals(666, integer.intValue());

      assertFalse(file.getNode().isParsed());

      Bar emptyBar = bars.get(1);
      GenericAttributeValue<String> string = emptyBar.getString();
      assertNull(string.getXmlElement());

      assertFalse(file.getNode().isParsed());
    });
  }

  @Test
  public void testAccessingPsi() {
    runInEdtAndWait(() -> {
      DomFileElement<Foo> element = prepare("foo.xml", Foo.class);
      assertNotNull(element.getXmlElement());

      XmlTag tag = element.getRootTag();
      assertNotNull(tag);

      Foo foo = element.getRootElement();
      assertNotNull(foo.getXmlTag());

      Bar bar = foo.getBars().get(0);
      assertNotNull(bar.getXmlElement());

      XmlAttribute attribute = bar.getString().getXmlAttribute();
      assertNotNull(attribute);
    });
  }

  @Test
  public void testConverters() {
    runInEdtAndWait(() -> {
      // the converter resolves java.lang.String from the JDK
      setUpJdk(LanguageLevel.JDK_1_7, projectFixture.get(), moduleFixture.get(), codeInsightFixture.get().getTestRootDisposable());
      DomFileElement<Foo> element = prepare("converters.xml", Foo.class);
      Bar bar = element.getRootElement().getBars().get(0);
      PsiClass value = bar.getClazz().getValue();
      assertNotNull(value);
      assertEquals("java.lang.String", value.getQualifiedName());
      assertFalse(element.getFile().getNode().isParsed());
    });
  }

  @Test
  public void testParent() {
    runInEdtAndWait(() -> {
      DomFileElement<Foo> element = prepare("parent.xml", Foo.class);

      Bar bar = element.getRootElement().getBars().get(0);
      GenericAttributeValue<Integer> notStubbed = bar.getNotStubbed();
      DomElement parent = notStubbed.getParent();
      assertEquals(bar, parent);

      NotStubbed child = bar.getNotStubbeds().get(0);
      parent = child.getParent();
      assertEquals(bar, parent);
    });
  }

  @Test
  public void testChildrenOfType() {
    runInEdtAndWait(() -> {
      DomFileElement<Foo> element = prepare("foo.xml", Foo.class);
      Foo foo = element.getRootElement();
      List<Bar> bars = DomUtil.getChildrenOf(foo, Bar.class);
      assertEquals(2, bars.size());
    });
  }

  @Test
  public void testFileLoading() {
    runInEdtAndWait(() -> {
      XmlFile file = prepareFile("foo.xml");
      PsiManagerEx.getInstanceEx(projectFixture.get()).setAssertOnFileLoadingFilter(VirtualFileFilter.ALL, codeInsightFixture.get().getTestRootDisposable());
      DomFileElement<Foo> element = DomManager.getDomManager(projectFixture.get()).getFileElement(file, Foo.class);
      assertNotNull(element);
      GenericDomValue<String> id = element.getRootElement().getId();
      assertEquals("foo", id.getValue());
    });
  }

  @Test
  public void testStubbedElementUndefineNotExisting() {
    runInEdtAndWait(() -> {
      final DomFileElement<Foo> fileElement = prepare("foo.xml", Foo.class);
      final Bar bar = fileElement.getRootElement().getBars().get(0);

      assertUndefine(bar);
    });
  }

  @Test
  public void testRootElementUndefineNotExisting() {
    runInEdtAndWait(() -> {
      final DomFileElement<Foo> fileElement = prepare("foo.xml", Foo.class);

      final DomElement rootElement = fileElement.getRootElement();
      assertUndefine(rootElement);
    });
  }

  private <T extends DomElement> DomFileElement<T> prepare(@TestDataFile String path, Class<T> domClass) {
    return DomStubTestUtil.prepare(path, domClass, codeInsightFixture.get());
  }

  private XmlFile prepareFile(@TestDataFile String path) {
    return DomStubTestUtil.prepareFile(path, codeInsightFixture.get());
  }

  private static void assertUndefine(final DomElement domElement) {
    assertNotNull(domElement);
    assertTrue(domElement.exists());

    WriteCommandAction.writeCommandAction(null).run(() -> domElement.undefine());

    assertFalse(domElement.exists());
  }
}
