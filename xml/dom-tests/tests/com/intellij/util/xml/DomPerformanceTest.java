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

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.PerformanceUnitTest;
import com.intellij.testFramework.junit5.StressTestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.tools.ide.metrics.benchmark.Benchmark;
import com.intellij.util.xml.impl.DomManagerImpl;
import com.intellij.util.xml.impl.DomTestFixture;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;

import static com.intellij.testFramework.EdtTestUtil.runInEdtAndWait;
import static com.intellij.util.xml.impl.DomTestFixtures.domModuleFixture;
import static com.intellij.util.xml.impl.DomTestFixtures.domTestFixture;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@PerformanceUnitTest
@StressTestApplication
public class DomPerformanceTest {
  private final TestFixture<Module> moduleFixture = domModuleFixture();
  private final TestFixture<DomTestFixture> domFixture = domTestFixture(moduleFixture);

  @Test
  public void testVisitorPerformance(TestInfo testInfo) {
    Method testMethod = testInfo.getTestMethod().orElseThrow();
    runInEdtAndWait(() -> {
      Ref<MyElement> ref = new Ref<>();

      Benchmark.newBenchmark("creating", () -> ApplicationManager.getApplication().runWriteAction(() -> {
          MyElement element = createElement("<root xmlns=\"http://www.w3.org/1999/xhtml\"/>");
          MyElement child = element.addChildElement();
          child.getAttr().setValue("239");
          child.getChild239().getAttr().setValue("42");
          child.getChild().getAttr().setValue("42xx");
          child.getChild2().getAttr().setValue("42yy");
          child.addChildElement().getChild().addFooChild().getAttr().setValue("xxx");
          child.addChildElement().addFooChild().getAttr().setValue("yyy");
          child.addChildElement().addFooChild().addBarChild().addBarChild().addChildElement().getChild().getAttr().setValue("xxx");
          child.addChildElement().addBarComposite().setValue("ssss");
          child.addBarChild().getChild2().getAttr().setValue("234178956023");
          for (int i = 0; i < 239; i++) {
            element.addChildElement().copyFrom(child);
          }
          ref.set(element);
      }))
        .start(testMethod, "creating");

      MyElement newElement = createElement(DomUtil.getFile(ref.get()).getText());

      Benchmark.newBenchmark("visiting", () ->
        newElement.acceptChildren(new DomElementVisitor() {
          @Override
          public void visitDomElement(DomElement element) {
            element.acceptChildren(this);
          }
        })).start(testMethod, "visiting");
    });
  }

  @Test
  public void testShouldntParseNonDomFiles(TestInfo testInfo) throws IOException {
    Method testMethod = testInfo.getTestMethod().orElseThrow();
    runInEdtAndWait(() -> {
      for (int i = 0; i < 420; i++) {
        getDomManager().registerFileDescription(new DomFileDescription<>(MyChildElement.class, "foo") {

          @Override
          public boolean isMyFile(@NotNull final XmlFile file) {
            fail("isMyFile must not be called");
            return super.isMyFile(file);
          }
        }, domFixture.get().getDisposable());
        getDomManager().registerFileDescription(new DomFileDescription<>(MyChildElement.class, "bar") {

          @Override
          public boolean isMyFile(@NotNull final XmlFile file) {
            fail("isMyFile must not be called");
            return super.isMyFile(file);
          }
        }, domFixture.get().getDisposable());
      }

      getDomManager().createMockElement(MyChildElement.class, null, true);

      VirtualFile virtualFile = domFixture.get().createSourceFile("a.xml", "").getVirtualFile();
      WriteCommandAction.runWriteCommandAction(domFixture.get().getProject(), (ThrowableComputable<Void, IOException>)() -> {
        VfsUtil.saveText(virtualFile, "<root>\n" + StringUtil.repeat("<bar/>\n", 23942) + "</root>");
        return null;
      });

      PsiManager psiManager = domFixture.get().getPsiManager();
      ((PsiManagerEx)psiManager).cleanupForNextTest();
      final XmlFile file = (XmlFile)psiManager.findFile(virtualFile);
      assertFalse(file.getNode().isParsed());
      assertTrue(StringUtil.isNotEmpty(file.getText()));
      Benchmark.newBenchmark("DOM parsing", () -> assertNull(getDomManager().getFileElement(file))).start(testMethod, "DOM parsing");
    });
  }

  @Test
  public void testDontParseNamespacedDomFiles() throws IOException {
    runInEdtAndWait(() -> {
      getDomManager().registerFileDescription(new DomFileDescription(MyNamespacedElement.class, "foo") {
        @Override
        protected void initializeFileDescription() {
          registerNamespacePolicy("project", "project");
        }
      }, domFixture.get().getDisposable());

      XmlFile file = (XmlFile)domFixture.get().createSourceFile("a.xml", "<foo xmlns=\"project\"/>");
      assertFalse(file.getNode().isParsed());
      assertNotNull(DomManager.getDomManager(domFixture.get().getProject()).getFileElement(file, MyNamespacedElement.class));

      file = (XmlFile)domFixture.get().createSourceFile("a.xml", "<foo xmlns=\"project2\"/>");
      assertFalse(file.getNode().isParsed());
      assertNull(DomManager.getDomManager(domFixture.get().getProject()).getFileElement(file, MyNamespacedElement.class));
    });
  }

  private DomManagerImpl getDomManager() {
    return domFixture.get().getDomManager();
  }

  private MyElement createElement(String xml) {
    return domFixture.get().createElement(xml, MyElement.class);
  }

  @Namespace("project")
  public interface MyNamespacedElement extends DomElement {

  }

  public interface MyChildElement extends DomElement {
    @Attribute
    @Required
    GenericAttributeValue<String> getAttr();

    List<MyFooConcreteElement> getFooChildren();

    MyFooConcreteElement addFooChild();
  }

  public interface MyElement extends DomElement {
    @Attribute
    @Required
    GenericAttributeValue<String> getAttr();

    String getValue();

    void setValue(String s);

    MyChildElement getChild();

    @SubTag(value = "child", index = 1)
    MyChildElement getChild2();

    MyChildElement getChild239();

    List<MyElement> getChildElements();

    MyElement addChildElement();

    List<MyAbstractElement> getAbstractElements();

    @SubTagList("abstract-element")
    MyBarConcreteElement addBarChild();

    @SubTagList("abstract-element")
    MyFooConcreteElement addFooChild();

    @SubTagsList({"child-element", "abstract-element"})
    List<MyElement> getCompositeList();

    @SubTagsList(value = {"child-element", "abstract-element"}, tagName = "abstract-element")
    MyBarConcreteElement addBarComposite();
  }

  public interface MyAbstractElement extends MyElement {
  }

  public interface MyFooConcreteElement extends MyAbstractElement {
  }

  public interface MyBarConcreteElement extends MyAbstractElement {
  }
}
