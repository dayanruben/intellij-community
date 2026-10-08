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
package com.intellij.util.xml.impl;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.module.Module;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.SubTag;
import com.intellij.util.xml.events.DomEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.intellij.testFramework.EdtTestUtil.runInEdtAndWait;
import static com.intellij.util.xml.impl.DomTestFixtures.domModuleFixture;
import static com.intellij.util.xml.impl.DomTestFixtures.domTestFixture;

@TestApplication
public class IncrementalUpdateEventsTest {
  private static final TestFixture<Module> moduleFixture = domModuleFixture();
  private final TestFixture<DomTestFixture> domFixture = domTestFixture(moduleFixture);
  private MyElement myElement;

  @BeforeEach
  void setUp() {
    runInEdtAndWait(() -> myElement = createElement("<a><child/><child/><child-element/><child-element/></a>"));
  }

  @Test
  public void testRemove0() {
    runInEdtAndWait(() -> {
      deleteTag(0);
      domFixture.get().putExpected(new DomEvent(myElement, false));
      domFixture.get().assertResultsAndClear();

      deleteTag(0);
      domFixture.get().putExpected(new DomEvent(myElement, false));
      domFixture.get().assertResultsAndClear();

      deleteTag(0);
      domFixture.get().putExpected(new DomEvent(myElement, false));
      domFixture.get().assertResultsAndClear();

      deleteTag(0);
      domFixture.get().putExpected(new DomEvent(myElement, false));
      domFixture.get().assertResultsAndClear();
    });
  }

  @Test
  public void testRemove1() {
    runInEdtAndWait(() -> {
      deleteTag(1);
      domFixture.get().putExpected(new DomEvent(myElement, false));
      domFixture.get().assertResultsAndClear();
    });
  }

  @Test
  public void testRemove2() {
    runInEdtAndWait(() -> {
      deleteTag(2);
      domFixture.get().putExpected(new DomEvent(myElement, false));
      domFixture.get().assertResultsAndClear();

      deleteTag(2);
      domFixture.get().putExpected(new DomEvent(myElement, false));
      domFixture.get().assertResultsAndClear();
    });
  }

  @Test
  public void testRemove3() {
    runInEdtAndWait(() -> {
      deleteTag(3);
      domFixture.get().putExpected(new DomEvent(myElement, false));
      domFixture.get().assertResultsAndClear();
    });
  }

  @Test
  public void testAdd0() {
    runInEdtAndWait(() -> {
      addChildTag(0);
      domFixture.get().putExpected(new DomEvent(myElement, false));
      domFixture.get().assertResultsAndClear();
    });
  }

  @Test
  public void testAdd1() {
    runInEdtAndWait(() -> {
      addChildTag(1);
      domFixture.get().putExpected(new DomEvent(myElement, false));
      domFixture.get().assertResultsAndClear();
    });
  }

  @Test
  public void testAdd2() {
    runInEdtAndWait(() -> {
      addChildElementTag(2);
      domFixture.get().putExpected(new DomEvent(myElement, false));
      domFixture.get().assertResultsAndClear();
    });
  }

  @Test
  public void testAdd3() {
    runInEdtAndWait(() -> {
      addChildElementTag(3);
      domFixture.get().putExpected(new DomEvent(myElement, false));
      domFixture.get().assertResultsAndClear();
    });
  }

  @Test
  public void testAdd4() {
    runInEdtAndWait(() -> {
      final XmlTag tag = myElement.getXmlTag();
      tag.addAfter(domFixture.get().createTag("<child-element/>"), tag.getSubTags()[3]);
      domFixture.get().putExpected(new DomEvent(myElement, false));
      domFixture.get().assertResultsAndClear();
    });
  }

  private MyElement getChild(final int index) {
    return myElement.getChildElements().get(index);
  }

  private void addChildTag(int index) {
    final XmlTag tag = myElement.getXmlTag();
    tag.addBefore(domFixture.get().createTag("<child/>"), tag.getSubTags()[index]);
  }

  private void addChildElementTag(int index) {
    final XmlTag tag = myElement.getXmlTag();
    tag.addBefore(domFixture.get().createTag("<child-element/>"), tag.getSubTags()[index]);
  }


  private void deleteTag(final int index) {
    WriteCommandAction.runWriteCommandAction(null, () -> myElement.getXmlTag().getSubTags()[index].delete());
  }

  private MyElement createElement(final String xml) {
    return domFixture.get().createElement(xml, MyElement.class);
  }

  public interface MyElement extends DomElement {
    MyElement getChild();

    @SubTag(value = "child", index = 1)
    MyElement getChild2();

    List<MyElement> getChildElements();
  }
}
