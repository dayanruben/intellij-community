// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.xml;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.DefaultPluginDescriptor;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.Key;
import com.intellij.serialization.ClassUtil;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.util.ParameterizedTypeImpl;
import com.intellij.util.xml.impl.DomTestFixture;
import com.intellij.util.xml.reflect.DomAttributeChildDescription;
import com.intellij.util.xml.reflect.DomCollectionChildDescription;
import com.intellij.util.xml.reflect.DomExtender;
import com.intellij.util.xml.reflect.DomExtenderEP;
import com.intellij.util.xml.reflect.DomExtension;
import com.intellij.util.xml.reflect.DomExtensionsRegistrar;
import com.intellij.util.xml.reflect.DomExtensionsRegistrarImpl;
import com.intellij.util.xml.reflect.DomFixedChildDescription;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
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
public class DomExtensionsTest {
  private static final TestFixture<Module> moduleFixture = domModuleFixture();
  private final TestFixture<DomTestFixture> domFixture = domTestFixture(moduleFixture);
  private static final Key<Boolean> BOOL_KEY = Key.create("aaa");

  @Test
  public void testExtendAttributes() {
    runInEdtAndWait(() -> {
      registerDomExtender(AttrDomExtender.class);
      assertTrue(getCustomChildren(domFixture.get().createElement("<a foo=\"xxx\"/>", MyElement.class)).isEmpty());

      MyElement element = domFixture.get().createElement("<a attr=\"foo\" foo=\"true\"/>", MyElement.class);
      final GenericAttributeValue child = assertInstanceOf(GenericAttributeValue.class, assertThat(getCustomChildren(element)).singleElement().actual());
      assertEquals("true", child.getStringValue());
      assertEquals(Boolean.TRUE, child.getValue());
      assertEquals(Boolean.class, DomUtil.getGenericValueParameter(child.getDomElementType()));
      assertSame(element.getXmlTag().getAttribute("foo"), child.getXmlElement());

      child.setStringValue("xxx");
      assertEquals("xxx", child.getStringValue());
      assertEquals("xxx", element.getXmlTag().getAttributeValue("foo"));

      element = domFixture.get().createElement("<a attr=\"foo\" foo=\"true\"/>", MyElement.class);
      final GenericAttributeValue value = domFixture.get().getDomManager().getDomElement(element.getXmlTag().getAttribute("foo"));
      assertNotNull(value);
      assertEquals(value, assertThat(getCustomChildren(element)).singleElement().actual());
      assertNotNull(element.getGenericInfo().getAttributeChildDescription("foo"));
    });
  }

  @Test
  public void testCustomAttributeChildClass() {
    runInEdtAndWait(() -> {
      registerDomExtender(AttrDomExtender3.class);
      final MyElement element = domFixture.get().createElement("<a attr=\"xxx\"/>", MyElement.class);
      assertEquals("xxx", assertInstanceOf(MyAttribute.class, assertThat(getCustomChildren(element)).singleElement().actual()).getXmlElementName());
    });
  }

  @Test
  public void testUserData() {
    runInEdtAndWait(() -> {
      registerDomExtender(AttrDomExtender3.class);
      final MyElement element = domFixture.get().createElement("<a attr=\"xxx\"/>", MyElement.class);
      final DomAttributeChildDescription description = element.getGenericInfo().getAttributeChildDescription("xxx");
      assertNotNull(description);
      assertSame(Boolean.TRUE, description.getUserData(BOOL_KEY));
    });
  }

  @Test
  public void testUseCustomConverter() {
    runInEdtAndWait(() -> {
      registerDomExtender(AttrDomExtender2.class);
      final MyElement myElement = domFixture.get().createElement("<a attr=\"xxx\" xxx=\"zzz\" yyy=\"zzz\"/>", MyElement.class);
      assertThat(getCustomChildren(myElement)).satisfiesExactlyInAnyOrder(element -> {
        final StringBuffer stringBuffer = ((GenericAttributeValue<StringBuffer>)element).getValue();
        assertEquals("zzz", stringBuffer.toString());
        assertInstanceOf(StringBufferConverter.class, ((GenericAttributeValue<StringBuffer>)element).getConverter());
        assertNotNull(myElement.getGenericInfo().getAttributeChildDescription("xxx"));

        Convert convert = element.getAnnotation(Convert.class);
        assertNotNull(convert);
        assertEquals(StringBufferConverter.class, convert.value());
        assertTrue(convert.soft());
      }, element -> {
        final StringBuffer stringBuffer = ((GenericAttributeValue<StringBuffer>)element).getValue();
        assertEquals("zzz", stringBuffer.toString());
        assertInstanceOf(StringBufferConverter.class, ((GenericAttributeValue<StringBuffer>)element).getConverter());
        assertNotNull(myElement.getGenericInfo().getAttributeChildDescription("yyy"));

        Convert convert = element.getAnnotation(Convert.class);
        assertNotNull(convert);
        assertEquals(StringBufferConverter.class, convert.value());
        assertFalse(convert.soft());
      });
    });
  }

  @Test
  public void testFixedChildren() {
    runInEdtAndWait(() -> {
      registerDomExtender(FixedDomExtender.class);
      final MyElement myElement = domFixture.get().createElement("<a attr=\"xxx\"><xxx>zzz</xxx><yyy attr=\"foo\"/><yyy attr=\"bar\"/></a>", MyElement.class);
      assertThat(getCustomChildren(myElement)).satisfiesExactlyInAnyOrder(element -> {
        @NotNull Type type = element.getDomElementType();
        assertEquals(GenericDomValue.class, ClassUtil.getRawType(type));
        final StringBuffer stringBuffer = ((GenericDomValue<StringBuffer>)element).getValue();
        assertEquals("zzz", stringBuffer.toString());
        assertInstanceOf(MyStringBufferConverter.class, ((GenericDomValue<StringBuffer>)element).getConverter());
        assertNotNull(myElement.getGenericInfo().getFixedChildDescription("xxx"));

        Convert convert = element.getAnnotation(Convert.class);
        assertNotNull(convert);
        assertEquals(MyStringBufferConverter.class, convert.value());
        assertTrue(convert.soft());

        assertNotNull(element.getGenericInfo().getAttributeChildDescription("aaa"));
      }, element -> {
        assertEquals("foo", assertInstanceOf(MyElement.class, element).getAttr().getValue());
        assertNull(element.getAnnotation(Convert.class));
      }, element -> {
        assertEquals("bar", assertInstanceOf(MyElement.class, element).getAttr().getValue());
        assertNull(element.getAnnotation(Convert.class));
      });
      final DomFixedChildDescription description = myElement.getGenericInfo().getFixedChildDescription("yyy");
      assertNotNull(description);
      assertEquals(2, description.getCount());
    });
  }

  @Test
  public void testCollectionChildren() {
    runInEdtAndWait(() -> {
      registerDomExtender(CollectionDomExtender.class);
      final MyElement myElement = domFixture.get().createElement("<a attr=\"xxx\"><xxx>zzz</xxx><xxx attr=\"foo\"/></a>", MyElement.class);
      assertThat(getCustomChildren(myElement)).satisfiesExactlyInAnyOrder(element -> {
        assertEquals("foo", assertInstanceOf(MyElement.class, element).getAttr().getValue());
        assertNull(element.getAnnotation(Convert.class));
      }, element -> {
        assertNull(assertInstanceOf(MyElement.class, element).getAttr().getValue());
        assertNull(element.getAnnotation(Convert.class));
      });
      assertNotNull(myElement.getGenericInfo().getCollectionChildDescription("xxx"));
    });
  }

  @Test
  public void testCollectionAdders() {
    runInEdtAndWait(() -> {
      registerDomExtender(CollectionDomExtender.class);
      MyElement myElement = domFixture.get().createElement("<a attr=\"xxx\"></a>", MyElement.class);
      DomCollectionChildDescription description = myElement.getGenericInfo().getCollectionChildDescription("xxx");
      assertThat(description).isNotNull();
      DomElement element2 = description.addValue(myElement);
      DomElement element0 = description.addValue(myElement, 0);
      DomElement element3 = description.addValue(myElement, MyConcreteElement.class);
      DomElement element1 = description.addValue(myElement, MyConcreteElement.class, 1);
      assertThat(getCustomChildren(myElement)).containsExactlyInAnyOrder(element0, element1, element2, element3);
    });
  }

  @Test
  public void testCustomChildrenAccessFromExtender() {
    runInEdtAndWait(() -> {
      registerDomExtender(MyCustomChildrenElement.class, CustomDomExtender.class);
      MyCustomChildrenElement myElement = domFixture.get().createElement("<a><xx/><yy/><concrete-child/><some-concrete-child/></a>", MyCustomChildrenElement.class);
      DomCollectionChildDescription description = myElement.getGenericInfo().getCollectionChildDescription("xx");
      assertThat(description).isNotNull();
      assertInstanceOf(MyDynamicElement.class, assertThat(description.getValues(myElement)).singleElement().actual());
      assertInstanceOf(MyCustomElement.class, assertThat(myElement.getCustomChidren()).singleElement().actual());
      assertInstanceOf(MyConcreteElement.class, assertThat(myElement.getConcreteChildren()).singleElement().actual());
      assertNotNull(assertInstanceOf(MyConcreteElement.class, myElement.getSomeConcreteChild()).getXmlTag());
    });
  }

  @Test
  public void testTolerateMalformedTags() {
    runInEdtAndWait(() -> {
      MyCustomChildrenElement myElement = domFixture.get().createElement("<a><xx/><concrete-child/><prefix:/></a>", MyCustomChildrenElement.class);
      assertEquals("xx", assertThat(myElement.getCustomChidren()).singleElement().actual().getXmlTag().getName());
    });
  }

  @Test
  public void testFirstChildRedefinitionOnExtending() {
    runInEdtAndWait(() -> {
      registerDomExtender(MyCustomChildrenElement.class, ModestDomExtender.class);

      final MyCustomChildrenElement myElement = domFixture.get().createElement("<a><concrete-child/><concrete-child/></a>", MyCustomChildrenElement.class);
      final List<MyConcreteElement> list = myElement.getConcreteChildren();
      final List<MyConcreteElement> list2 = myElement.getConcreteChildren();
      assertSame(list.get(0), list2.get(0));
      assertSame(list.get(1), list2.get(1));
    });
  }

  public static List<DomElement> getCustomChildren(final DomElement element) {
    final List<DomElement> children = new ArrayList<>();
    element.acceptChildren(new DomElementVisitor() {
      @Override
      public void visitDomElement(final DomElement element) {
        if (!"attr".equals(element.getXmlElementName())) {
          children.add(element);
        }
      }
    });
    return children;
  }

  public void registerDomExtender(Class<? extends DomExtender<MyElement>> extender) {
    registerDomExtender(MyElement.class, extender);
  }

  public <T extends DomElement> void registerDomExtender(final Class<T> domClass, final Class<? extends DomExtender<T>> extenderClass) {
    DomExtenderEP extenderEP = new DomExtenderEP(domClass.getName(), new DefaultPluginDescriptor(PluginId.getId("registerDomExtender"), getClass().getClassLoader()));
    extenderEP.extenderClassName = extenderClass.getName();
    ServiceContainerUtil.registerExtension(ApplicationManager.getApplication(), DomExtenderEP.EP_NAME, extenderEP, domFixture.get().getDisposable());
  }

  public interface MyElement extends DomElement {
    GenericAttributeValue<String> getAttr();
  }

  public interface MyCustomChildrenElement extends DomElement {
    @CustomChildren List<MyCustomElement> getCustomChidren();

    List<MyConcreteElement> getConcreteChildren();

    MyConcreteElement getSomeConcreteChild();
  }

  public interface MyConcreteElement extends MyElement {
  }
  public interface MyCustomElement extends MyElement {
  }
  public interface MyDynamicElement extends MyElement {
  }

  public static class AttrDomExtender extends DomExtender<MyElement> {
    @Override
    public void registerExtensions(@NotNull final MyElement element, @NotNull final DomExtensionsRegistrar registrar) {
      final String value = element.getAttr().getValue();
      if (value != null) {
        registrar.registerGenericAttributeValueChildExtension(new XmlName(value), Boolean.class);
      }
    }
  }

  public static class AttrDomExtender2 extends DomExtender<MyElement> {
    @Override
    public void registerExtensions(@NotNull final MyElement element, @NotNull final DomExtensionsRegistrar registrar) {
      final String value = element.getAttr().getValue();
      if (value != null) {
        registrar.registerGenericAttributeValueChildExtension(new XmlName(value), StringBuffer.class).setConverter(new StringBufferConverter(), true);
        registrar.registerGenericAttributeValueChildExtension(new XmlName("yyy"), StringBuffer.class).setConverter(new StringBufferConverter(), false);
      }
    }
  }



  public static class AttrDomExtender3 extends DomExtender<MyElement> {

    @Override
    public void registerExtensions(@NotNull final MyElement element, @NotNull final DomExtensionsRegistrar registrar) {
      final String value = element.getAttr().getValue();
      if (value != null) {
        registrar.registerAttributeChildExtension(new XmlName(value), MyAttribute.class).putUserData(BOOL_KEY, Boolean.TRUE);
      }
    }
  }

  public static class FixedDomExtender extends DomExtender<MyElement> {
    @Override
    public void registerExtensions(@NotNull final MyElement element, @NotNull final DomExtensionsRegistrar registrar) {
      final String value = element.getAttr().getValue();
      if (value != null) {
        final ParameterizedType type = new ParameterizedTypeImpl(GenericDomValue.class, StringBuffer.class);
        final DomExtension extension =
          registrar.registerFixedNumberChildExtension(new XmlName(value), type).setConverter(new MyStringBufferConverter(true), true);
        extension.addExtender(new DomExtender<GenericDomValue<StringBuffer>>(){

          @Override
          public void registerExtensions(@NotNull final GenericDomValue<StringBuffer> stringBufferGenericDomValue, @NotNull final DomExtensionsRegistrar registrar) {
            registrar.registerGenericAttributeValueChildExtension(new XmlName("aaa"), String.class);
          }
        });
        ((DomExtensionsRegistrarImpl)registrar).registerFixedNumberChildrenExtension(new XmlName("yyy"), MyElement.class, 2);
      }
    }
  }
  public static class CollectionDomExtender extends DomExtender<MyElement> {
    @Override
    public void registerExtensions(@NotNull final MyElement element, @NotNull final DomExtensionsRegistrar registrar) {
      final String value = element.getAttr().getValue();
      if (value != null) {
        registrar.registerCollectionChildrenExtension(new XmlName(value), MyElement.class).setConverter(new MyStringBufferConverter(true), true);
      }
    }
  }

  public static class CustomDomExtender extends DomExtender<MyCustomChildrenElement> {
    @Override
    public void registerExtensions(@NotNull final MyCustomChildrenElement element, @NotNull final DomExtensionsRegistrar registrar) {
      assertTrue(element.getCustomChidren().isEmpty());
      registrar.registerCollectionChildrenExtension(new XmlName("xx"), MyDynamicElement.class);
    }
  }

  public static class ModestDomExtender extends DomExtender<MyCustomChildrenElement> {
    @Override
    public void registerExtensions(@NotNull final MyCustomChildrenElement element, @NotNull final DomExtensionsRegistrar registrar) {
      registrar.registerCollectionChildrenExtension(new XmlName("xx"), MyDynamicElement.class);
    }
  }

  public static final class MyStringBufferConverter extends StringBufferConverter {
    public MyStringBufferConverter(boolean b) {
    }
  }

  public interface MyAttribute extends GenericAttributeValue<Boolean> {}
}

