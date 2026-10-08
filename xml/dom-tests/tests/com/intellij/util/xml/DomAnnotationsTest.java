// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.xml;

import com.intellij.codeInspection.InspectionManager;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.module.Module;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.util.xml.highlighting.DomElementAnnotationsManager;
import com.intellij.util.xml.highlighting.DomElementProblemDescriptorImpl;
import com.intellij.util.xml.highlighting.DomElementsProblemsHolder;
import com.intellij.util.xml.highlighting.DomElementsProblemsHolderImpl;
import com.intellij.util.xml.highlighting.MockDomInspection;
import com.intellij.util.xml.impl.DomTestFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.intellij.testFramework.EdtTestUtil.runInEdtAndWait;
import static com.intellij.util.xml.impl.DomTestFixtures.domModuleFixture;
import static com.intellij.util.xml.impl.DomTestFixtures.domTestFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@TestApplication
public class DomAnnotationsTest {
  private static final TestFixture<Module> moduleFixture = domModuleFixture();
  private final TestFixture<DomTestFixture> domFixture = domTestFixture(moduleFixture);

  private <T extends DomElement> T createElement(final String xml, final Class<T> aClass) {
    final String name = "a.xml";
    final XmlFile file = (XmlFile)PsiFileFactory.getInstance(domFixture.get().getProject()).createFileFromText(name, XmlFileType.INSTANCE, xml, 0, true);
    final XmlTag tag = file.getDocument().getRootTag();
    final String rootTagName = tag != null ? tag.getName() : "root";
    final T element = domFixture.get().getDomManager().getFileElement(file, aClass, rootTagName).getRootElement();
    assertNotNull(element);
    assertSame(tag, element.getXmlTag());
    return element;
  }

  @Test
  public void testResolveProblemsAreReportedOnlyOnce() {
    runInEdtAndWait(() -> {
      MyElement myElement = createElement("<a><my-class>abc</my-class></a>", MyElement.class);
    
      new MockDomInspection<>(MyElement.class).checkFile(DomUtil.getFile(myElement), InspectionManager.getInstance(domFixture.get().getProject()), true);
      DomElementsProblemsHolder holder = DomElementAnnotationsManager.getInstance(domFixture.get().getProject()).getProblemHolder(myElement);

      DomElement element = myElement.getMyClass();
      assertEquals(0, holder.getProblems(myElement).size());
      assertEquals(0, holder.getProblems(myElement).size());
      assertEquals(1, holder.getProblems(element).size());
      assertEquals(1, holder.getProblems(element).size());
      assertEquals(1, holder.getProblems(myElement, true, true).size());
      assertEquals(1, holder.getProblems(myElement, true, true).size());
    });
  }

  @Test
  public void testMinSeverity() {
    runInEdtAndWait(() -> {
      MyElement element = createElement("<a/>", MyElement.class);
      DomElementsProblemsHolderImpl holder = new DomElementsProblemsHolderImpl(DomUtil.getFileElement(element));
      DomElementProblemDescriptorImpl error = new DomElementProblemDescriptorImpl(element, "abc", HighlightSeverity.ERROR);
      DomElementProblemDescriptorImpl warning = new DomElementProblemDescriptorImpl(element, "abc", HighlightSeverity.WARNING);
      holder.addProblem(error, MockDomInspection.getInspection());
      holder.addProblem(warning, MockDomInspection.getInspection());
      assertEquals(List.of(error), holder.getProblems(element, true, true, HighlightSeverity.ERROR));
      assertEquals(List.of(error, warning), holder.getProblems(element, true, true, HighlightSeverity.WARNING));
    });
  }

  public interface MyElement extends DomElement {
    GenericDomValue<PsiClass> getMyClass();
  }
}
