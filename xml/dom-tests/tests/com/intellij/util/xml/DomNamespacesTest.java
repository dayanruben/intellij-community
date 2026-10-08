/*
 * Copyright 2000-2015 JetBrains s.r.o.
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

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.xml.XmlFile;
import com.intellij.openapi.module.Module;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.util.xml.impl.DomFileElementImpl;
import com.intellij.util.xml.impl.DomTestFixture;
import com.intellij.util.xml.reflect.DomGenericInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.intellij.testFramework.EdtTestUtil.runInEdtAndWait;
import static com.intellij.util.xml.impl.DomTestFixtures.domModuleFixture;
import static com.intellij.util.xml.impl.DomTestFixtures.domTestFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestApplication
public class DomNamespacesTest {
  private static final TestFixture<Module> moduleFixture = domModuleFixture();
  private final TestFixture<DomTestFixture> domFixture = domTestFixture(moduleFixture);

  @Test
  public void testUseExistingNamespace() {
    runInEdtAndWait(() -> {
      final MyElement element = domFixture.get().createElement("<a xmlns=\"foo\" xmlns:bar=\"bar\"/>", MyElement.class);
      registerNamespacePolicies(element);

      final XmlTag fooChildTag = element.getFooChild().ensureTagExists();
      assertEquals("foo-child", fooChildTag.getName());
      assertEquals("foo", fooChildTag.getNamespace());
      assertEquals("", fooChildTag.getNamespacePrefix());

      final XmlTag barChildTag = element.getBarChild().ensureTagExists();
      assertEquals("bar:bar-child", barChildTag.getName());
      assertEquals("bar", barChildTag.getNamespace());
      assertEquals("bar", barChildTag.getNamespacePrefix());
    });
  }

  @Test
  public void testDefineNewNamespace() {
    runInEdtAndWait(() -> {
      final MyElement element = domFixture.get().createElement("<a/>", MyElement.class);
      registerNamespacePolicies(element);

      final XmlTag fooChildTag = element.getFooChild().ensureTagExists();
      assertEquals("foo-child", fooChildTag.getName());
      assertEquals("foo", fooChildTag.getNamespace());
      assertEquals("", fooChildTag.getNamespacePrefix());
      assertEquals("foo", fooChildTag.getAttributeValue("xmlns"));

      final XmlTag barChildTag = element.getBarChild().ensureTagExists();
      assertEquals("bar-child", barChildTag.getName());
      assertEquals("bar", barChildTag.getNamespace());
      assertEquals("", barChildTag.getNamespacePrefix());
      assertEquals("bar", barChildTag.getAttributeValue("xmlns"));
    });
  }

  @Test
  public void testCollectionChildNamespace() {
    runInEdtAndWait(() -> {
      final MyElement element = domFixture.get().createElement("<a xmlns:foo=\"foo\"/>", MyElement.class);
      registerNamespacePolicies(element);

      final XmlTag fooChildTag = element.addFooElement().getXmlTag();
      assertEquals("foo:foo-element", fooChildTag.getName());
      assertEquals("foo", fooChildTag.getNamespace());
      assertEquals("foo", fooChildTag.getNamespacePrefix());
      assertNull(fooChildTag.getAttributeValue("xmlns"));
    });
  }

  @Test
  public void testNoNamespaceForFixedChild() {
    runInEdtAndWait(() -> {
      final MyElement element = domFixture.get().createElement("<a xmlns:foo=\"foo\"/>", MyElement.class);
      registerNamespacePolicies(element);

      final XmlTag childTag = element.getSomeChild().ensureTagExists();
      assertEquals("some-child", childTag.getName());
      assertEquals("", childTag.getNamespace());
      assertEquals("", childTag.getNamespacePrefix());
      assertNull(childTag.getAttributeValue("xmlns"));
    });
  }

  @Test
  public void testNoNamespaceForCollectionChild() {
    runInEdtAndWait(() -> {
      final MyElement element = domFixture.get().createElement("<a xmlns:foo=\"foo\"/>", MyElement.class);
      registerNamespacePolicies(element);

      final XmlTag childTag = element.addChild().getXmlTag();
      assertEquals("child", childTag.getName());
      assertEquals("", childTag.getNamespace());
      assertEquals("", childTag.getNamespacePrefix());
      assertNull(childTag.getAttributeValue("xmlns"));
    });
  }

  @Test
  public void testNamespaceEqualToParent() {
    runInEdtAndWait(() -> {
      final MyElement element = domFixture.get().createElement("<a xmlns=\"foo\"/>", MyElement.class);
      registerNamespacePolicies(element);

      final XmlTag childTag = element.addChild().getXmlTag();
      assertEquals("child", childTag.getName());
      assertEquals("foo", childTag.getNamespace());
      assertEquals("", childTag.getNamespacePrefix());
      assertNull(childTag.getAttributeValue("xmlns"));
    });
  }

  @Test
  public void testNamespaceEqualToParent2() {
    runInEdtAndWait(() -> {
      final MyElement root = domFixture.get().createElement("<a xmlns=\"foo\"/>", MyElement.class);
      registerNamespacePolicies(root);
      final MyFooElement element = root.addFooElement();

      final MyElement child = element.addChild();
      final XmlTag childTag = child.getXmlTag();
      assertEquals("child", childTag.getName());
      assertEquals("foo", childTag.getNamespace());
      assertEquals("", childTag.getNamespacePrefix());
      assertNull(childTag.getAttributeValue("xmlns"));

      assertEquals("foo", element.getXmlElementNamespaceKey());
      assertNull(child.getXmlElementNamespaceKey());
    });
  }

  @Test
  public void testHardcodedNamespacePrefix() {
    runInEdtAndWait(() -> {
      final XmlFile xmlFile = domFixture.get().createXmlFile("<a xmlns:sys=\"\"/>");
      final MyElement element = domFixture.get().getDomManager().getFileElement(xmlFile, MyElement.class, "a").getRootElement();
      final MyElement hardcodedElement = element.getHardcodedElement();
      WriteCommandAction.runWriteCommandAction(domFixture.get().getProject(), () -> {
        hardcodedElement.ensureTagExists();
      });
      assertTrue(element.isValid());
      assertTrue(hardcodedElement.isValid());
      assertNotNull(hardcodedElement.getXmlElement());
      assertEquals("<sys:aaa/>", hardcodedElement.getXmlElement().getText());
      assertEquals("sys:aaa", hardcodedElement.getXmlTag().getName());

      WriteCommandAction.runWriteCommandAction(domFixture.get().getProject(), () -> {
        hardcodedElement.getHardcodedElement().getHardcodedElement().ensureTagExists();
      });

      assertTrue(element.isValid());
      assertTrue(hardcodedElement.isValid());
      assertNotNull(hardcodedElement.getXmlElement());
      assertEquals("sys:aaa", hardcodedElement.getHardcodedElement().getHardcodedElement().getXmlTag().getName());

      assertEquals(1, element.getXmlTag().getSubTags().length);
    });
  }

  @Test
  public void testAutoChooseNamespaceIfPresent() {
    runInEdtAndWait(() -> {
      final MyElement root = domFixture.get().createElement("<a xmlns=\"foo\"/>", MyElement.class);
      domFixture.get().getDomManager().getDomFileDescription(root.getXmlElement()).registerNamespacePolicy("foo", "bar", "foo");

      final XmlTag fooChildTag = root.getFooChild().ensureTagExists();
      assertEquals("foo-child", fooChildTag.getName());
      assertEquals("foo", fooChildTag.getNamespace());
      assertEquals("", fooChildTag.getNamespacePrefix());
      assertEquals(0, fooChildTag.getAttributes().length);
    });
  }

  @Test
  public void testNonemptyRootTagPrefix() {
    runInEdtAndWait(() -> {
      domFixture.get().getDomManager().registerFileDescription(new DomFileDescription<>(MyFooElement.class, "a", "foons") {

        @Override
        protected void initializeFileDescription() {
          super.initializeFileDescription();
          registerNamespacePolicy("foo", "foons");
        }
      }, domFixture.get().getDisposable());

      final XmlFile psiFile = domFixture.get().createXmlFile("<f:a xmlns:f=\"foons\"/>");
      final DomFileElementImpl<MyFooElement> element = domFixture.get().getDomManager().getFileElement(psiFile, MyFooElement.class);
      assertNotNull(element);

      final MyFooElement root = element.getRootElement();
      assertNotNull(root);
      assertSame(psiFile.getDocument().getRootTag(), root.getXmlElement());
    });
  }

  @Test
  public void testSpringAopLike() {
    runInEdtAndWait(() -> {
      domFixture.get().getDomManager().registerFileDescription(new DomFileDescription<>(MyBeans.class, "beans", "beans", "aop") {

        @Override
        protected void initializeFileDescription() {
          super.initializeFileDescription();
          registerNamespacePolicy("beans", "beans");
          registerNamespacePolicy("aop", "aop");
        }
      }, domFixture.get().getDisposable());

      final XmlFile psiFile = domFixture.get().createXmlFile("<beans xmlns=\"beans\" xmlns:aop=\"aop\">" +
                                            "<aop:config>" +
                                            "<aop:pointcut/>" +
                                            "</aop:config>" +
                                            "</beans>");
      final MyBeans beans = domFixture.get().getDomManager().getFileElement(psiFile, MyBeans.class).getRootElement();
      final DomElement pointcut =
        domFixture.get().getDomManager().getDomElement(beans.getXmlTag().findFirstSubTag("aop:config").findFirstSubTag("aop:pointcut"));
      assertNotNull(pointcut);
      final MyAopConfig aopConfig = beans.getConfig();
      final List<MySpringPointcut> pointcuts = aopConfig.getPointcuts();
      assertEquals(1, pointcuts.size());
      assertEquals(pointcuts.get(0), pointcut);
    });
  }

  @Test
  public void testSpringUtilLike() {
    runInEdtAndWait(() -> {
      domFixture.get().getDomManager().registerFileDescription(new DomFileDescription<>(MyBeans.class, "beans", "beans", "util") {

        @Override
        protected void initializeFileDescription() {
          super.initializeFileDescription();
          registerNamespacePolicy("beans", "beans");
          registerNamespacePolicy("util", "util");
        }
      }, domFixture.get().getDisposable());

      final XmlFile psiFile = domFixture.get().createXmlFile("<beans xmlns=\"beans\" xmlns:util=\"util\">" +
                                            "<util:list>" +
                                            "<ref>aaa</ref>" +
                                            "<util:child>bbb</util:child>" +
                                            "</util:list></beans>");

      final MyList listOrSet = assertInstanceOf(MyList.class, domFixture.get().getDomManager().getFileElement(psiFile, MyBeans.class).getRootElement().getList());
      assertNotNull(listOrSet.getXmlTag());

      final XmlTag listTag = psiFile.getDocument().getRootTag().findFirstSubTag("util:list");
      assertNotNull(domFixture.get().getDomManager().getDomElement(listTag.findFirstSubTag("ref")));
      assertNotNull(domFixture.get().getDomManager().getDomElement(listTag.findFirstSubTag("util:child")));

      assertEquals("aaa", listOrSet.getRef().getValue());
      assertEquals("bbb", listOrSet.getChild().getValue());
    });
  }

  private void registerNamespacePolicies(final MyElement element) {
    registerNamespacePolicies(element, "foo", "bar");
  }

  private void registerNamespacePolicies(final MyElement element, final String foo, final String bar) {
    final DomFileDescription description = domFixture.get().getDomManager().getDomFileDescription(element.getXmlElement());
    description.registerNamespacePolicy("foo", foo);
    description.registerNamespacePolicy("bar", bar);
  }

  @Test
  public void testFindChildDescriptionWithoutNamespace() {
    runInEdtAndWait(() -> {
      final DomGenericInfo info = domFixture.get().getDomManager().getGenericInfo(MyListOrSet.class);
      assertNotNull(info.getAttributeChildDescription("attr"));
      assertNotNull(info.getAttributeChildDescription("attr").getType());
      assertNotNull(info.getCollectionChildDescription("child"));
      assertNotNull(info.getCollectionChildDescription("child").getType());
      assertNotNull(info.getFixedChildDescription("ref"));
      assertNotNull(info.getFixedChildDescription("ref").getType());
    });
  }

  @Test
  public void testCopyFromHonorsNamespaces() {
    runInEdtAndWait(() -> {
      final MyElement element = domFixture.get().createElement("<a xmlns=\"foo\" xmlns:bar=\"bar\"/>", MyElement.class);
      registerNamespacePolicies(element);

      final MyElement element2 = domFixture.get().createElement("<f:a xmlns:f=\"foo1\" xmlns:b=\"bar1\" xmlns=\"foo1\">" +
                                               "<foo-child/>" +
                                               "<b:bar-child/>" +
                                               "<f:some-child/>" +
                                               "<f:foo-element attr-2=\"239\" attr=\"42\"/>" +
                                               "<f:foo-element/>" +
                                               "<f:bool/>" +
                                               "<sys:aaa/>" +
                                               "</f:a>", MyElement.class);
      registerNamespacePolicies(element2, "foo1", "bar1");
      WriteCommandAction.runWriteCommandAction(domFixture.get().getProject(), () -> element.copyFrom(element2));

      assertEquals("<a xmlns=\"foo\" xmlns:bar=\"bar\">" +
                   "<bar:bar-child/>" +
                   "<bool/>" +
                   "<foo-child/>" +
                   "<some-child/>" +
                   "<sys:aaa/>" +
                   "<foo-element attr=\"42\" attr-2=\"239\"/>" +
                   "<foo-element/>" +
                   "</a>",
                   element.getXmlTag().getText());
    });
  }

  @Test
  public void testAttributeWithAnotherNamespace() {
    runInEdtAndWait(() -> {
      final MyElement element = domFixture.get().createElement("<a xmlns=\"foo\" xmlns:bar=\"bar\"><foo-child bar:my-attribute=\"xxx\"/></a>", MyElement.class);
      registerNamespacePolicies(element);
      final MyFooElement fooElement = element.getFooChild();
      final MyAttribute myAttribute = fooElement.getMyAttribute();
      assertNotNull(myAttribute.getXmlAttribute());
      assertEquals("xxx", myAttribute.getStringValue());
    });
  }

  public interface MyElement extends DomElement {
    MyFooElement getFooChild();
    MyBarElement getBarChild();

    MyElement getSomeChild();

    List<MyFooElement> getFooElements();
    MyFooElement addFooElement();

    List<MyElement> getChildren();
    MyElement addChild();

    GenericAttributeValue<String> getAttr();
    GenericAttributeValue<String> getAttr2();

    @SubTag(indicator = true)
    GenericDomValue<Boolean> getBool();

    @SubTag("sys:aaa")
    MyElement getHardcodedElement();
  }

  @Namespace("foo")
  public interface MyFooElement extends MyElement {
    MyAttribute getMyAttribute();
  }

  @Namespace("bar")
  public interface MyBarElement extends MyElement {

  }

  @Namespace("bar")
  public interface MyAttribute extends GenericAttributeValue<String> {

  }

  @Namespace("beans")
  public interface MyBeans extends DomElement {
    MyAopConfig getConfig();
    MyList getList();
  }

  @Namespace("aop")
  public interface MyAopConfig extends DomElement {
    List<MySpringPointcut> getPointcuts();
  }

  @Namespace("aop")
  public interface MySpringPointcut extends DomElement {

  }

  @Namespace("beans")
  public interface MyListOrSet extends DomElement {
    GenericDomValue<String> getRef();
    List<GenericDomValue<String>> getChildren();
    GenericAttributeValue<String> getAttr();
  }

  @Namespace("util")
  public interface MyList extends MyListOrSet {
    GenericDomValue<String> getChild();
  }


}
