// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.xml;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlTagValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Reference checks for the DOM values in a JUnit 5 test. Call them on the EDT.
 */
public final class DomReferenceTestUtil {
  private DomReferenceTestUtil() {
  }

  public static PsiReference assertReference(GenericDomValue<?> value) {
    return assertReference(value, value.getXmlTag());
  }

  public static PsiReference assertReference(GenericDomValue<?> value, PsiElement resolveTo) {
    XmlTagValue tagValue = value.getXmlTag().getValue();
    TextRange textRange = tagValue.getTextRange();
    String s = value.getStringValue();
    assertNotNull(s);
    int i = tagValue.getText().indexOf(s);
    return assertReference(value, resolveTo, textRange.getStartOffset() + i + s.length());
  }

  public static PsiReference assertReference(GenericDomValue<?> value, PsiElement resolveTo, int offset) {
    XmlTag tag = value.getXmlTag();
    PsiReference reference = tag.getContainingFile().findReferenceAt(offset);
    assertNotNull(reference);
    reference.getVariants();
    assertEquals(resolveTo, reference.resolve());
    return reference;
  }

  public static PsiReference getReference(GenericAttributeValue<?> value) {
    XmlAttributeValue attributeValue = value.getXmlAttributeValue();
    assertNotNull(attributeValue);
    PsiReference reference = attributeValue.getContainingFile().findReferenceAt(attributeValue.getTextRange().getStartOffset() + 1);
    assertNotNull(reference);
    assertEquals(attributeValue, reference.resolve());
    return reference;
  }

  @SuppressWarnings("removal")
  public static void assertVariants(PsiReference reference, String... variants) {
    Object[] refVariants = reference.getVariants();
    assertNotNull(refVariants);
    assertEquals(refVariants.length, variants.length);
    int i = 0;
    for (String variant : variants) {
      Object refVariant = refVariants[i++];
      if (refVariant instanceof LookupElement lookupElement) {
        assertEquals(variant, lookupElement.getLookupString());
      }
      else if (refVariant instanceof com.intellij.codeInsight.lookup.PresentableLookupValue lookupValue) {
        assertEquals(variant, lookupValue.getPresentation());
      }
      else {
        assertEquals(variant, refVariant.toString());
      }
    }
  }
}
