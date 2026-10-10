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

import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiRecursiveElementVisitor;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlTagValue;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.util.xml.impl.DomTestFixture;
import com.intellij.util.xml.impl.GenericDomValueReference;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.intellij.testFramework.EdtTestUtil.runInEdtAndWait;
import static com.intellij.util.xml.DomReferenceTestUtil.assertReference;
import static com.intellij.util.xml.DomReferenceTestUtil.assertVariants;
import static com.intellij.util.xml.DomReferenceTestUtil.getReference;
import static com.intellij.util.xml.impl.DomTestFixtures.domModuleFixture;
import static com.intellij.util.xml.impl.DomTestFixtures.domTestFixture;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestApplication
public class DomReferencesTest {
  private static final TestFixture<Module> moduleFixture = domModuleFixture();
  private final TestFixture<DomTestFixture> domFixture = domTestFixture(moduleFixture);

  @Test
  public void testMetaData() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("");
      element.getName().setValue("A");
      final XmlTag tag = element.getXmlTag();
      final DomMetaData metaData = assertInstanceOf(DomMetaData.class, tag.getMetaData());
      assertEquals(tag, metaData.getDeclaration());
      assertArrayEquals(new Object[]{DomUtil.getFileElement(element), tag}, metaData.getDependencies());
      assertEquals("A", metaData.getName());
      assertEquals("A", metaData.getName(null));

      metaData.setName("B");
      assertEquals("B", element.getName().getValue());
    });
  }

  @Test
  public void testNameReference() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><name>abc</name></a>");
      final DomTarget target = DomTarget.getTarget(element);
      assertNotNull(target);
      final XmlTag tag = element.getName().getXmlTag();
      assertNull(tag.getContainingFile().findReferenceAt(tag.getValue().getTextRange().getStartOffset()));
    });
  }

  @Test
  public void testProcessingInstruction() {
    runInEdtAndWait(() -> {
      createElement("<a><?xml version=\"1.0\"?></a>").getXmlTag().accept(new PsiRecursiveElementVisitor() {
        @Override public void visitElement(@NotNull PsiElement element) {
          super.visitElement(element);
          for (final PsiReference reference : element.getReferences()) {
            assertFalse(reference instanceof GenericDomValueReference);
          }
        }
      });
    });
  }

  @Test
  public void testBooleanReference() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><boolean>true</boolean></a>");
      assertVariants(assertReference(element.getBoolean()), "false", "true");
    });
  }

  @Test
  public void testBooleanAttributeReference() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a boolean-attribute=\"true\"/>");
      final PsiReference reference = getReference(element.getBooleanAttribute());
      assertVariants(reference, "false", "true");

      final XmlAttributeValue xmlAttributeValue = element.getBooleanAttribute().getXmlAttributeValue();
      final PsiElement psiElement = reference.getElement();
      assertEquals(xmlAttributeValue, psiElement);

      assertEquals(new TextRange(0, "true".length()).shiftRight(1), reference.getRangeInElement());
    });
  }

  @Test
  public void testEnumReference() {
    runInEdtAndWait(() -> {
      assertVariants(assertReference(createElement("<a><enum>239</enum></a>").getEnum(), null), "A", "B", "C");
      assertVariants(assertReference(createElement("<a><enum>A</enum></a>").getEnum()), "A", "B", "C");
    });
  }

  @Test
  public void testPsiClass() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><psi-class>java.lang.String</psi-class></a>");
      assertReference(element.getPsiClass(), getJavaLangString(),
                      element.getPsiClass().getXmlTag().getValue().getTextRange().getEndOffset() - 1);
    });
  }

  @Test
  public void testPsiType() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><psi-type>java.lang.String</psi-type></a>");
      assertReference(element.getPsiType(), getJavaLangString());
    });
  }

  @Test
  public void testIndentedPsiType() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><psi-type>  java.lang.Strin   </psi-type></a>");
      final PsiReference psiReference = assertReference(element.getPsiType(), null);
      assertEquals(new TextRange(22, 22 + "Strin".length()), psiReference.getRangeInElement());
    });
  }

  @Test
  public void testPsiPrimitiveType() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><psi-type>int</psi-type></a>");
      assertReference(element.getPsiType());
    });
  }

  @Test
  public void testPsiPrimitiveTypeArray() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><psi-type>int[]</psi-type></a>");
      final GenericDomValue value = element.getPsiType();
      final XmlTagValue tagValue = value.getXmlTag().getValue();
      final int i = tagValue.getText().indexOf(value.getStringValue());
      assertReference(value, value.getXmlTag(), tagValue.getTextRange().getStartOffset() + i + "int".length());
    });
  }

  @Test
  public void testPsiUnknownType() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><psi-type>#$^%*$</psi-type></a>");
      assertReference(element.getPsiType(), null);
    });
  }

  @Test
  public void testPsiArrayType() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><psi-type>java.lang.String[]</psi-type></a>");
      final XmlTag tag = element.getPsiType().getXmlTag();
      final TextRange valueRange = tag.getValue().getTextRange();
      final PsiReference reference = tag.getContainingFile().findReferenceAt(valueRange.getStartOffset() + "java.lang.".length());
      assertNotNull(reference);
      assertEquals(getJavaLangString(), reference.resolve());
      assertEquals("<psi-type>java.lang.".length(), reference.getRangeInElement().getStartOffset());
      assertEquals("String".length(), reference.getRangeInElement().getLength());
    });
  }

  @Test
  public void testJvmArrayType() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><jvm-psi-type>[Ljava.lang.String;</jvm-psi-type></a>");
      final XmlTag tag = element.getJvmPsiType().getXmlTag();
      final TextRange valueRange = tag.getValue().getTextRange();
      final PsiReference reference = tag.getContainingFile().findReferenceAt(valueRange.getEndOffset() - 1);
      assertNotNull(reference);
      assertEquals(getJavaLangString(), reference.resolve());
      assertEquals("<jvm-psi-type>[Ljava.lang.".length(), reference.getRangeInElement().getStartOffset());
      assertEquals("String".length(), reference.getRangeInElement().getLength());
    });
  }

  @Test
  public void testCustomResolving() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><string-buffer>239</string-buffer></a>");
      assertVariants(assertReference(element.getStringBuffer()), "239", "42", "foo", "zzz");
    });
  }

  @Test
  public void testAdditionalValues() {
    runInEdtAndWait(() -> {
      final MyElement element = createElement("<a><string-buffer>zzz</string-buffer></a>");
      final XmlTag tag = element.getStringBuffer().getXmlTag();
      assertTrue(tag.getContainingFile().findReferenceAt(tag.getValue().getTextRange().getStartOffset()).isSoft());
    });
  }

  private MyElement createElement(String xml) {
    return domFixture.get().createElement(xml, MyElement.class);
  }

  private PsiClass getJavaLangString() {
    return PsiType.getJavaLangString(domFixture.get().getPsiManager(), GlobalSearchScope.allScope(domFixture.get().getProject())).resolve();
  }

  public interface MyElement extends DomElement {
    GenericDomValue<Boolean> getBoolean();

    GenericAttributeValue<Boolean> getBooleanAttribute();

    @Convert(MyStringConverter.class)
    GenericDomValue<String> getConvertedString();

    GenericDomValue<MyEnum> getEnum();

    @NameValue GenericDomValue<String> getName();

    GenericDomValue<PsiClass> getPsiClass();

    GenericDomValue<PsiType> getPsiType();

    @Convert(JvmPsiTypeConverter.class)
    GenericDomValue<PsiType> getJvmPsiType();

    List<GenericDomValue<MyEnum>> getEnumChildren();

    @Convert(MyStringBufferConverter.class)
    GenericDomValue<StringBuffer> getStringBuffer();

    MyAbstractElement getChild();

    MyElement getRecursiveChild();

    List<MyGenericValue> getMyGenericValues();

    MyGenericValue getMyAnotherGenericValue();
  }

  @Convert(MyStringConverter.class)
  public interface MyGenericValue extends GenericDomValue<String> {

  }

  public interface MySomeInterface {
    GenericValue<PsiType> getFoo();
  }

  public interface MyAbstractElement extends DomElement {
    GenericAttributeValue<String> getFubar239();
    GenericAttributeValue<Runnable> getFubar();
  }

  public interface MyFooElement extends MyAbstractElement, MySomeInterface {
    @Override
    GenericDomValue<PsiType> getFoo();

    GenericAttributeValue<Set> getFubar2();
  }

  public interface MyBarElement extends MyAbstractElement {
    GenericDomValue<StringBuffer> getBar();
  }


  public enum MyEnum {
    A,B,C
  }

  public static class MyStringConverter extends ResolvingConverter<String> {

    @Override
    @NotNull
    public Collection<String> getVariants(final @NotNull ConvertContext context) {
      return Collections.emptyList();
    }

    @Override
    public String fromString(final String s, final @NotNull ConvertContext context) {
      return s;
    }

    @Override
    public String toString(final String s, final @NotNull ConvertContext context) {
      return s;
    }
  }

  public static class MyStringBufferConverter extends ResolvingConverter<StringBuffer> {

    @Override
    public StringBuffer fromString(final String s, final @NotNull ConvertContext context) {
      return s == null ? null : new StringBuffer(s);
    }

    @Override
    public String toString(final StringBuffer t, final @NotNull ConvertContext context) {
      return t == null ? null : t.toString();
    }

    @NotNull
    @Override
    public Collection<StringBuffer> getVariants(final @NotNull ConvertContext context) {
      return Arrays.asList(new StringBuffer("239"), new StringBuffer("42"), new StringBuffer("foo"));
    }

    @NotNull
    @Override
    public Set<String> getAdditionalVariants(@NotNull ConvertContext context) {
      return Collections.singleton("zzz");
    }
  }
}
