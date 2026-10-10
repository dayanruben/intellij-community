/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.intellij.util.xml;

import com.intellij.openapi.module.Module;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.xml.events.DomEvent;
import com.intellij.util.xml.impl.DomTestFixture;
import org.junit.jupiter.api.Test;

import static com.intellij.testFramework.EdtTestUtil.runInEdtAndWait;
import static com.intellij.util.xml.impl.DomTestFixtures.domModuleFixture;
import static com.intellij.util.xml.impl.DomTestFixtures.domTestFixture;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestApplication
public class SimpleValuesIncrementalUpdateTest {
  private static final TestFixture<Module> moduleFixture = domModuleFixture();
  private final TestFixture<DomTestFixture> domFixture = domTestFixture(moduleFixture);

  @Test
  public void testAttributeChange() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a/>");
      element.getXmlTag().setAttribute("attr", "foo");
      domFixture.get().putExpected(new DomEvent(element, false));
      domFixture.get().assertResultsAndClear();
      assertTrue(element.getAttr().isValid());

      element.getXmlTag().setAttribute("bttr", "foo");
      element.getXmlTag().setAttribute("attr", "bar");
      domFixture.get().putExpected(new DomEvent(element, false));
      domFixture.get().putExpected(new DomEvent(element, false));
      domFixture.get().assertResultsAndClear();
      assertTrue(element.getAttr().isValid());

      element.getXmlTag().setAttribute("attr", null);
      domFixture.get().putExpected(new DomEvent(element, false));
      domFixture.get().assertResultsAndClear();
      assertTrue(element.getAttr().isValid());
    });
  }

  @Test
  public void testAttributeValueChangeAsXmlElementChange() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a attr=\"foo\"/>");
      final GenericAttributeValue<String> attr = element.getAttr();
      attr.getXmlAttributeValue().getFirstChild().replace(domFixture.get().createTag("<a attr=\"bar\"/>").getAttribute("attr", null).getValueElement().getFirstChild());
      domFixture.get().putExpected(new DomEvent(element, false));
      domFixture.get().assertResultsAndClear();
      assertTrue(attr.isValid());
    });
  }

  @Test
  public void testTagValueChange() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><child> </child></a>").getChild();
      element.getXmlTag().getValue().setText("abc");
      domFixture.get().putExpected(new DomEvent(element, false));
      domFixture.get().assertResultsAndClear();

      element.getXmlTag().getValue().setText(null);
      domFixture.get().putExpected(new DomEvent(element, false));
      domFixture.get().assertResultsAndClear();
    });
  }

  @Test
  public void testAttrXmlEmptyUri() {
    runInEdtAndWait(() -> {
      final MyElement element = domFixture.get().createElement("<a xmlns=\"foo\"><ns-child attr=\"239\"/></a>" , MyElement.class);
      domFixture.get().getDomManager().getDomFileDescription(element.getXmlElement()).registerNamespacePolicy("foo", "foo");

      final GenericAttributeValue<String> attr = element.getNsChild().getAttr();
      attr.getXmlTag().setAttribute("attr", "42");
      domFixture.get().putExpected(new DomEvent(element.getNsChild(), false));
      domFixture.get().assertResultsAndClear();
    });
  }

  private MyElement createElement(final String xml) throws IncorrectOperationException {
    return domFixture.get().createElement(xml, MyElement.class);
  }

  public interface MyElement extends DomElement{
    GenericAttributeValue<String> getAttr();

    Integer getValue();

    MyElement getChild();

    MyNsElement getNsChild();
  }

  @Namespace("foo")
  public interface MyNsElement extends DomElement{
    GenericAttributeValue<String> getAttr();

  }

}
