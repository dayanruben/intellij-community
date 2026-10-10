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
package com.intellij.util.xml;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.module.Module;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.util.ArrayUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.xml.events.DomEvent;
import com.intellij.util.xml.impl.DomTestFixture;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.intellij.testFramework.EdtTestUtil.runInEdtAndWait;
import static com.intellij.util.xml.impl.DomTestFixtures.domModuleFixture;
import static com.intellij.util.xml.impl.DomTestFixtures.domTestFixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestApplication
public class DomChildrenTest {
  private static final TestFixture<Module> moduleFixture = domModuleFixture();
  private final TestFixture<DomTestFixture> domFixture = domTestFixture(moduleFixture);

  private MyElement createElement(final String xml) throws IncorrectOperationException {
    return domFixture.get().createElement(xml, MyElement.class);
  }

  @Test
  public void testGetChild() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a>" + "<child>foo</child>" + "</a>");
      assertNotNull(element.toString());
      assertEquals("foo", element.getMyChild().getValue());
      assertEquals("foo", element.getChild().getValue());
      assertSame(element.getChild(), element.getMyChild());
      assertNull(element.getChild239().getXmlTag());
    });
  }

  @Test
  public void testGetChild239() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><child/><child-239/></a>");
      assertNotNull(element.getChild239().getXmlTag());
    });
  }

  @Test
  public void testGetChildren() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a>" + "<child-element>foo</child-element>" + "<child-element>bar</child-element>" + "</a>");
      assertSubBars(element.getMyChildren());
      assertSubBars(element.getChildElements());
      assertSubBars(element.getMyChildren2());
    });
  }

  @Test
  public void testGetChildrenMultipleTimes() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a>" + "<child-element>" + " <child-element/>" + "</child-element>" + "</a>");
      for (int i = 0; i < 239; i++) {
        final List<MyElement> children = element.getChildElements();
        final MyElement child = children.get(0);
        final List<MyElement> children1 = child.getChildElements();
        final MyElement child1 = children1.get(0);
        assertNotNull(child1.getChild());
      }
    });
  }

  private void assertSubBars(final List<? extends MyElement> subBars) {
    assertEquals(2, subBars.size());
    assertEquals("foo", subBars.get(0).getValue());
    assertEquals("bar", subBars.get(1).getValue());
  }

  @Test
  public void testClassChoosers() {
    runInEdtAndWait(() -> {
      domFixture.get().getTypeChooserManager().registerTypeChooser(MyAbstractElement.class, new MyTypeChooser());
      try {
        MyElement element = createElement("<a>" +
                                          "<abstract-child>Foo</abstract-child>" +
                                          "<abstract-element>Foo</abstract-element>" +
                                          "<abstract-element>bar</abstract-element>" + "</a>");
        assertFalse(element.getAbstractChild()instanceof MyBarConcreteElement);
        final List<MyAbstractElement> abstractElements = element.getAbstractElements();
        assertTrue(abstractElements.get(0)instanceof MyFooConcreteElement);
        assertTrue(abstractElements.get(1)instanceof MyBarConcreteElement);
      }
      finally {
        domFixture.get().getTypeChooserManager().unregisterTypeChooser(MyAbstractElement.class);
      }
    });
  }

  @Test
  public void testAddConcreteElements() {
    runInEdtAndWait(() -> {
      domFixture.get().getTypeChooserManager().registerTypeChooser(MyAbstractElement.class, new MyTypeChooser());
      try {
        MyElement element = createElement("<a/>");
        element.addBarChild();
        element.addFooChild();
        element.addAbstractElement(MyFooConcreteElement.class);
        element.addAbstractElement(MyFooConcreteElement.class, 1);
        element.addAbstractElement(2, MyBarConcreteElement.class);
        Class[] classes = {MyBarConcreteElement.class, MyFooConcreteElement.class, MyBarConcreteElement.class,
          MyFooConcreteElement.class, MyFooConcreteElement.class};
        final List<MyAbstractElement> abstractElements = element.getAbstractElements();
        for (int i = 0; i < abstractElements.size(); i++) {
          MyAbstractElement abstractElement = abstractElements.get(i);
          assertTrue(classes[i].isInstance(abstractElement), i + " " + abstractElement.getClass());
          assertEquals(classes[i].getName(), abstractElement.getXmlTag().getAttributeValue("foo"), String.valueOf(i));
        }
      }
      finally {
        domFixture.get().getTypeChooserManager().unregisterTypeChooser(MyAbstractElement.class);
      }
    });
  }

  @Test
  public void testIndexedChild() {
    runInEdtAndWait(() -> {
      MyElement element = createElement("<a>" + "<child>foo</child>" + "<child>bar</child>" + "</a>");
      domFixture.get().assertCached(element.getChild2(), element.getXmlTag().getSubTags()[1]);
      assertEquals(0, element.getChildElements().size());
    });
  }

  @Test
  public void testDefiningIndexedChild() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a/>");
      final XmlTag tag = element.getChild2().ensureTagExists();
      final XmlTag[] subTags = element.getXmlTag().findSubTags("child");
      assertEquals(2, subTags.length);
      assertSame(tag, subTags[1]);

      domFixture.get().assertCached(element.getChild(), subTags[0]);
      final DomElement element1 = element.getChild();
      domFixture.get().putExpected(new DomEvent(element1, true));
      final DomElement element2 = element.getChild().getAttr();
      domFixture.get().putExpected(new DomEvent(element2, true));
      final DomElement element3 = element.getChild().isGenericValue();
      domFixture.get().putExpected(new DomEvent(element3, true));
      final DomElement element4 = element.getChild2();
      domFixture.get().putExpected(new DomEvent(element4, true));
      final DomElement element5 = element.getChild2().getAttr();
      domFixture.get().putExpected(new DomEvent(element5, true));
      final DomElement element6 = element.getChild2().isGenericValue();
      domFixture.get().putExpected(new DomEvent(element6, true));
      domFixture.get().assertResultsAndClear();
    });
  }

  @Test
  public void testAddChild() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><child-element/></a>");
      assertEquals(1, element.getChildElements().size());
      final MyElement firstChild = element.getChildElements().get(0);
      final XmlTag firstChildTag = element.getXmlTag().findSubTags("child-element")[0];
      domFixture.get().assertCached(firstChild, firstChildTag);

      final MyElement child = element.addChildElement();
      assertEquals(Arrays.asList(firstChild, child), element.getChildElements());
      final XmlTag childTag = element.getXmlTag().findSubTags("child-element")[1];
      domFixture.get().putExpected(new DomEvent(element, false));
      final DomElement element1 = child.getAttr();
      domFixture.get().putExpected(new DomEvent(element1, true));
      final DomElement element2 = child.isGenericValue();
      domFixture.get().putExpected(new DomEvent(element2, true));

      final MyElement newChild = element.addChildElement(1);
      assertEquals(Arrays.asList(firstChild, newChild, child), element.getChildElements());
      final XmlTag newChildTag = element.getXmlTag().findSubTags("child-element")[1];
      domFixture.get().putExpected(new DomEvent(element, false));
      final DomElement element3 = newChild.getAttr();
      domFixture.get().putExpected(new DomEvent(element3, true));
      final DomElement element4 = newChild.isGenericValue();
      domFixture.get().putExpected(new DomEvent(element4, true));

      final MyElement lastChild = element.addChildElement(239);
      assertEquals(Arrays.asList(firstChild, newChild, child, lastChild), element.getChildElements());
      final XmlTag lastChildTag = element.getXmlTag().findSubTags("child-element")[3];
      domFixture.get().putExpected(new DomEvent(element, false));
      final DomElement element5 = lastChild.getAttr();
      domFixture.get().putExpected(new DomEvent(element5, true));
      final DomElement element6 = lastChild.isGenericValue();
      domFixture.get().putExpected(new DomEvent(element6, true));
      domFixture.get().assertResultsAndClear();

      domFixture.get().assertCached(firstChild, firstChildTag);
      domFixture.get().assertCached(newChild, newChildTag);
      domFixture.get().assertCached(child, childTag);
      domFixture.get().assertCached(lastChild, lastChildTag);

      assertSame(firstChildTag, element.getXmlTag().findSubTags("child-element")[0]);
      assertSame(newChildTag, element.getXmlTag().findSubTags("child-element")[1]);
      assertSame(childTag, element.getXmlTag().findSubTags("child-element")[2]);
      assertSame(lastChildTag, element.getXmlTag().findSubTags("child-element")[3]);
    });
  }

  @Test
  public void testUndefineCollectionChild() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><child-element/><child-element/><child-element/></a>");
      final MyElement child1 = element.getChildElements().get(0);
      final MyElement child2 = element.getChildElements().get(1);
      final MyElement child3 = element.getChildElements().get(2);

      final List<XmlTag> oldChildren = new ArrayList<>(Arrays.asList(element.getXmlTag().getSubTags()));

      assertTrue(child2.isValid());
      assertEquals(element, child2.getParent());

      WriteCommandAction.runWriteCommandAction(null, () -> {
        child2.undefine();
        assertFalse(child2.isValid());

        oldChildren.remove(1);
        assertEquals(oldChildren, Arrays.asList(element.getXmlTag().getSubTags()));

        assertEquals(Arrays.asList(child1, child3), element.getChildElements());
        domFixture.get().assertCached(child1, element.getXmlTag().findSubTags("child-element")[0]);
        domFixture.get().assertCached(child3, element.getXmlTag().findSubTags("child-element")[1]);
      });


      domFixture.get().getCallRegistry().putExpected(new DomEvent(element, false));
      domFixture.get().getCallRegistry().assertResultsAndClear();
    });
  }

  @Test
  public void testUndefineFixedChild() {
    runInEdtAndWait(() -> {
      WriteCommandAction.writeCommandAction(domFixture.get().getProject()).run(() -> {
        final MyElement child = createElement("<a><child/></a>").getChild();

        domFixture.get().incModCount();
        child.ensureTagExists();
        assertTrue(child.isValid());
        assertNotNull(child.getXmlElement());

        domFixture.get().incModCount();
        child.undefine();
        assertTrue(child.isValid());
        assertNull(child.getXmlElement());

        child.ensureTagExists();
        domFixture.get().incModCount();
        assertTrue(child.isValid());
        assertNotNull(child.getXmlElement());
      });
    });
  }

  @Test
  public void testAttributes() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a/>");
      final GenericAttributeValue<String> attr = element.getAttr();
      assertSame(element.getXmlTag(), attr.getXmlTag());
      assertNull(attr.getValue());
      assertNull(attr.getXmlAttribute());

      assertEquals(attr, element.getAttr());
      attr.setValue("239");
      assertEquals("239", attr.getValue());
      final XmlAttribute attribute = element.getXmlTag().getAttribute("attr", null);
      assertSame(attribute, attr.getXmlAttribute());
      assertSame(attribute, attr.getXmlElement());
      assertSame(attribute, attr.ensureXmlElementExists());
      assertSame(attribute.getValueElement(), attr.getXmlAttributeValue());

      attr.setValue(null);
      assertFalse(attribute.isValid());
      assertNull(element.getXmlTag().getAttributeValue("attr"));
      assertNull(attr.getValue());
      assertNull(attr.getXmlAttribute());
    });
  }

  @Test
  public void testUndefineLastFixedChildWithNotEmptyCollection() {
    runInEdtAndWait(() -> {
      WriteCommandAction.writeCommandAction(domFixture.get().getProject()).run(() -> {
        final MyElement element = createElement("<a>" + "<child>1</child>" + "<child attr=\"\">2</child>" + "<child/></a>");
        final MyElement child = element.getChild();
        final MyElement child2 = element.getChild2();

        assertEquals("", child2.getAttr().getValue());
        assertTrue(child.isValid());
        assertTrue(child2.isValid());
        child.undefine();

        assertTrue(child.isValid());
        assertTrue(child2.isValid());
        assertTrue(child2.equals(element.getChild2()));
        assertNotNull(child.getXmlTag());

        child2.undefine();

        assertTrue(child.isValid());
        assertTrue(child2.isValid());
        assertTrue(child2.equals(element.getChild2()));
        assertEquals(child, element.getChild());
        assertEquals("", child.getValue());
        assertNull(element.getChild2().getValue());

        domFixture.get().getCallRegistry().putExpected(new DomEvent(child, false));
        domFixture.get().getCallRegistry().putExpected(new DomEvent(child2, false));
        domFixture.get().getCallRegistry().assertResultsAndClear();
      });
    });
  }

  @Test
  public void testUndefineNotLastFixedChild() {
    runInEdtAndWait(() -> {
      WriteCommandAction.writeCommandAction(domFixture.get().getProject()).run(() -> {
        final MyElement element = createElement("<a>" + "<child>1</child>" + "<child attr=\"\">2</child>" + "</a>");
        final MyElement child = element.getChild();
        final MyElement child2 = element.getChild2();

        assertTrue(child.isValid());
        assertTrue(child2.isValid());
        child.undefine();
        assertTrue(child.isValid());
        assertTrue(child2.isValid());
        assertEquals(element, child.getParent());
        assertEquals(element, child2.getParent());
        assertEquals(child, element.getChild());
        assertEquals(child2, element.getChild2());

        domFixture.get().getCallRegistry().putExpected(new DomEvent(child, false));
        domFixture.get().getCallRegistry().assertResultsAndClear();

        XmlTag[] subTags = element.getXmlTag().getSubTags();
        assertEquals(2, subTags.length);
        domFixture.get().assertCached(child, subTags[0]);
        domFixture.get().assertCached(child2, subTags[1]);
      });
    });
  }

  @Test
  public void testUndefineLastFixedChild() {
    runInEdtAndWait(() -> {
      WriteCommandAction.writeCommandAction(domFixture.get().getProject()).run(() -> {
        final MyElement element =
          createElement("<a>" + "<child>1</child>" + "<child attr=\"\">2</child>" + "<child attr=\"\">2</child>" + "</a>");
        final MyElement child = element.getChild();
        final MyElement child2 = element.getChild2();
        child2.undefine();

        domFixture.get().getCallRegistry().putExpected(new DomEvent(child2, false));
        domFixture.get().getCallRegistry().assertResultsAndClear();

        XmlTag[] subTags = element.getXmlTag().getSubTags();
        assertTrue(child.isValid());
        assertTrue(child2.isValid());
        assertEquals(1, subTags.length);
        assertNull(child2.getXmlTag());

        assertEquals(element, child.getParent());
        assertEquals(element, child2.getParent());
      });
    });
  }

  @Test
  public void testUndefineFixedChildWithNoTag() {
    runInEdtAndWait(() -> {
      WriteCommandAction.writeCommandAction(domFixture.get().getProject()).run(() -> {
        final MyElement element = createElement("<a/>");
        final MyElement child = element.getChild();
        child.undefine();

        domFixture.get().getCallRegistry().assertResultsAndClear();
        assertTrue(child.isValid());
        assertEquals(0, element.getXmlTag().getSubTags().length);

        assertSame(element, child.getParent());
      });
    });
  }

  @Test
  public void testGenericValuesCollection() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><generic-child>239</generic-child></a>");
      assertEquals(239, (int)element.getGenericChildren().get(0).getValue());
    });
  }

  @Test
  public void testIsBooleanGenericValueMethod() {
    runInEdtAndWait(() -> {
      assertNull(createElement("").isGenericValue().getValue());
    });
  }

  @Test
  public void testGetParentOfType() {
    runInEdtAndWait(() -> {
      domFixture.get().getTypeChooserManager().registerTypeChooser(MyAbstractElement.class, new MyTypeChooser());
      try {
        final MyElement element = createElement("");
        final MyAbstractElement abstractChild = element.getAbstractChild();
        final MyElement child239 = abstractChild.getChild239();

        assertSame(child239, child239.getParentOfType(MyElement.class, false));
        assertSame(abstractChild, child239.getParentOfType(MyElement.class, true));
        assertSame(abstractChild, child239.getParentOfType(MyAbstractElement.class, true));
        assertSame(DomUtil.getFileElement(element), child239.getParentOfType(DomFileElement.class, false));
        assertSame(DomUtil.getFileElement(element), DomUtil.getFileElement(element).getParentOfType(DomFileElement.class, false));
        assertNull(DomUtil.getFileElement(element).getParentOfType(DomFileElement.class, true));
      }
      finally {
        domFixture.get().getTypeChooserManager().unregisterTypeChooser(MyAbstractElement.class);
      }
    });
  }

  @Test
  public void testChildrenValidAfterUndefine() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><child/></a>");
      final MyElement child = element.getChild();
      ApplicationManager.getApplication().runWriteAction(() -> element.undefine());

      assertTrue(element.isValid());
      assertFalse(child.isValid());
    });
  }

  @Test
  public void testGetCompositeCollection() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("");
      final MyFooConcreteElement foo = element.addFooChild();
      final MyElement child = element.addChildElement();
      final MyBarConcreteElement bar = element.addBarChild();
      assertEquals(Arrays.asList(foo, bar, child), element.getCompositeList());
    });
  }

  @Test
  public void testAddToCompositeCollection() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("");
      final MyBarConcreteElement bar = element.addBarComposite();
      final MyElement child = element.addChildComposite();
      final MyElement child2 = element.addChildElement();
      final MyFooConcreteElement foo1 = element.addFooComposite(2);
      final MyFooConcreteElement foo2 = element.addFooComposite(0);
      assertEquals(Arrays.asList(foo2, bar, child, foo1, child2), element.getCompositeList());
    });
  }

  @Test
  public void testCreateElementWithRequiredChild() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("").addChildElement();
      assertNotNull(element.getAttr().getXmlAttribute());
      assertNotNull(element.isGenericValue().getXmlTag());
      assertTrue(element.getMyChildren2().isEmpty());
    });
  }

  @Test
  public void testSetRequiredChildValue() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a/>").getChild();
      element.isGenericValue().setStringValue("true");
      assertEquals(1, element.getXmlTag().findSubTags("generic-value").length);
      assertTrue(element.isGenericValue().getValue());
    });
  }

  @Test
  public void testGetFixedPath() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("");
      final MyElement child2 = element.getChild2();
      assertEquals(Arrays.asList("getChild2", "getChild2", "getChild239", "getAbstractChild", "getChild2"),
                   getFixedPath(child2.getChild2().getChild239().getAbstractChild().getChild2()));
      assertEquals(Arrays.asList("getChild2"), getFixedPath(child2));
      assertEquals(Arrays.asList(), getFixedPath(element));
      assertEquals(Arrays.asList("getAttr"), getFixedPath(element.getAttr()));
      assertNull(DomUtil.getFixedPath(element.addChildElement().getChild()));
    });
  }

  @Test
  public void testGetDomElementCustomChild() {
    runInEdtAndWait(() -> {
      final MyElement myElement = domFixture.get().createElement("<a><abstract-child/><generic-value/><bar/></a>", MyElement.class);
      final XmlTag tag = myElement.getXmlTag();
      assertEquals(1, myElement.getCustomChildren().size());
      assertInstanceOf(MyAbstractElement.class, domFixture.get().getDomManager().getDomElement(tag.getSubTags()[0]));
      assertInstanceOf(GenericDomValue.class, domFixture.get().getDomManager().getDomElement(tag.getSubTags()[1]));
      assertInstanceOf(MyElement.class, domFixture.get().getDomManager().getDomElement(tag.getSubTags()[2]));
    });
  }

  @Test
  public void testNoChildrenForMalformedTags() {
    runInEdtAndWait(() -> {
      final MyElement myElement = domFixture.get().createElement("<a><</a>", MyElement.class);
      final XmlTag tag = myElement.getXmlTag();
      assertEquals(0, myElement.getCustomChildren().size());
    });
  }

  @Test
  public void testCustomNameChildren() {
    runInEdtAndWait(() -> {
      final MyElement myElement = domFixture.get().createElement("<a><foo/><bar/><foo xmlns=\"z\"/></a>", MyElement.class);
      final XmlTag tag = myElement.getXmlTag();
      final List<MyElement> customChildren = myElement.getCustomChildren();
      assertEquals(myElement.getGenericInfo().getCustomNameChildrenDescription().get(0).getValues(myElement), customChildren);
      assertThat(customChildren).satisfiesExactly(
        element -> assertEquals(tag.getSubTags()[0], element.getXmlTag()),
        element -> assertEquals(tag.getSubTags()[1], element.getXmlTag()),
        element -> assertEquals(tag.getSubTags()[2], element.getXmlTag()));
    });
  }

  private List<String> getFixedPath(final DomElement element) {
    return ContainerUtil.map(DomUtil.getFixedPath(element), s -> s.getName());
  }

  @Test
  public void testElementsWithoutXmlGetItLater() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a/>");
      final MyElement child = element.getChild();
      final MyElement child2 = element.getChild2();
      final PsiElement tag = element.getXmlTag().add(domFixture.get().createTag("<child/>"));
      domFixture.get().incModCount();

      assertTrue(child.isValid());
      assertEquals(tag, child.getXmlTag());

      assertTrue(child2.isValid());
      assertNull(child2.getXmlTag());
    });
  }

  @Test
  public void testHigherOrderFixedsAreNotCustom() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><child/><child/><child/></a>");
      XmlTag third = element.getXmlTag().getSubTags()[2];
      assertNull(domFixture.get().getDomManager().getDomElement(third));
      assertTrue(element.getCustomChildren().isEmpty());
    });
  }


  public interface MyElement extends DomElement {
    @Attribute
    @Required
    GenericAttributeValue<String> getAttr();

    String getValue();

    @SubTag("child")
    MyElement getMyChild();

    MyElement getChild();

    @SubTag(value = "child", index = 1)
    MyElement getChild2();

    MyElement getChild239();

    @SubTagList("child-element")
    List<? extends MyElement> getMyChildren();

    @CustomChildren
    List<MyElement> getCustomChildren();

    @SubTagList("child-element")
    @Required
    List<MyElement> getMyChildren2();

    List<MyElement> getChildElements();

    MyElement addChildElement();

    MyElement addChildElement(int index);

    MyAbstractElement getAbstractChild();

    List<MyAbstractElement> getAbstractElements();

    @SubTagList("abstract-element")
    MyBarConcreteElement addBarChild();

    @SubTagList("abstract-element")
    MyFooConcreteElement addFooChild();

    <T extends MyAbstractElement> T addAbstractElement(Class<T> aClass);

    <T extends MyAbstractElement> T addAbstractElement(Class<T> aClass, int index);

    <T extends MyAbstractElement> T addAbstractElement(int index, Class<T> aClass);

    List<GenericDomValue<Integer>> getGenericChildren();

    @Required
    GenericDomValue<Boolean> isGenericValue();

    @SubTagsList({"child-element", "abstract-element"})
    List<MyElement> getCompositeList();

    @SubTagsList(value = {"child-element", "abstract-element"}, tagName = "abstract-element")
    MyBarConcreteElement addBarComposite();

    @SubTagsList(value = {"child-element", "abstract-element"}, tagName = "child-element")
    MyElement addChildComposite();

    @SubTagsList(value = {"child-element", "abstract-element"}, tagName = "abstract-element")
    MyFooConcreteElement addFooComposite(int index);

  }

  public interface MyAbstractElement extends MyElement {
  }

  public interface MyFooConcreteElement extends MyAbstractElement {
    MyFooConcreteElement getFoo();
  }

  public interface MyBarConcreteElement extends MyAbstractElement {
  }

  private static class MyTypeChooser extends TypeChooser {
    @Override
    public Type chooseType(final XmlTag tag) {
      return tag != null && tag.getText().contains("Foo") ? MyFooConcreteElement.class : MyBarConcreteElement.class;
    }

    @Override
    public void distinguishTag(final XmlTag tag, final Type aClass) throws IncorrectOperationException {
      tag.setAttribute("foo", ((Class<?>)aClass).getName());
    }

    @Override
    public Type[] getChooserTypes() {
      return ArrayUtil.EMPTY_CLASS_ARRAY;
    }
  }
}
