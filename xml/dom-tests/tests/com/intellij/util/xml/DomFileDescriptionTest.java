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

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.util.xml.impl.DomApplicationComponent;
import com.intellij.util.xml.impl.DomFileElementImpl;
import com.intellij.util.xml.impl.DomManagerImpl;
import com.intellij.util.xml.impl.DomTestFixture;
import com.intellij.util.xml.impl.MockDomFileDescription;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static com.intellij.testFramework.EdtTestUtil.runInEdtAndWait;
import static com.intellij.util.xml.impl.DomTestFixtures.domModuleFixture;
import static com.intellij.util.xml.impl.DomTestFixtures.domTestFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestApplication
public class DomFileDescriptionTest {
  private final TestFixture<Module> moduleFixture = domModuleFixture();
  private final TestFixture<DomTestFixture> domFixture = domTestFixture(moduleFixture);
  private VirtualFile myFooElementFile;
  private VirtualFile myBarElementFile;

  @BeforeEach
  void setUp() throws IOException {
    runInEdtAndWait(() -> {
      myFooElementFile = createFile("a.xml", "<a/>").getVirtualFile();

      getDomManager().registerFileDescription(new MockDomFileDescription<>(FooElement.class, "a", myFooElementFile), getDisposable());

      myBarElementFile = createFile("b.xml", "<b/>").getVirtualFile();

      getDomManager().registerFileDescription(new DomFileDescription<>(BarElement.class, "b") {
        @Override
        public boolean isMyFile(@NotNull final XmlFile file) {
          String text = LoadTextUtil.loadText(myFooElementFile).toString();
          return text.contains("239");
        }

        @Override
        public boolean isAutomaticHighlightingEnabled() {
          return false;
        }
      }, getDisposable());

      domFixture.get().assertResultsAndClear();
    });
  }

  @Test
  public void testNoInitialDomnessInB() {
    runInEdtAndWait(() -> {
      assertFalse(getDomManager().isDomFile(PsiManager.getInstance(getProject()).findFile(myBarElementFile)));
      assertNull(getDomManager().getFileElement((XmlFile)PsiManager.getInstance(getProject()).findFile(myBarElementFile)));
    });
  }

  @Test
  public void testIsDomValue() throws IOException {
    runInEdtAndWait(() -> {
      final XmlFile file = (XmlFile)createFile("a.xml", "<b>42</b>");
      getDomManager().registerFileDescription(new DomFileDescription<>(MyElement.class, "b") {

        @Override
        public boolean isMyFile(@NotNull final XmlFile file) {
          return /*super.isMyFile(file) && */file.getText().contains("239");
        }
      }, getDisposable());


      assertFalse(getDomManager().isDomFile(file));
      assertNull(getDomManager().getFileElement(file));

      WriteCommandAction.runWriteCommandAction(getProject(), () -> file.getDocument().getRootTag().getValue().setText("239"));
      assertTrue(getDomManager().isDomFile(file));
      final DomFileElementImpl<MyElement> root = getDomManager().getFileElement(file);
      assertNotNull(root);
      final MyElement child = root.getRootElement().getChild();
      assertTrue(root.isValid());
      assertTrue(child.isValid());

      WriteCommandAction.runWriteCommandAction(getProject(), () -> file.getDocument().getRootTag().getValue().setText("57121"));
      assertFalse(getDomManager().isDomFile(file));
      assertNull(getDomManager().getFileElement(file));
      assertFalse(root.isValid());
      assertFalse(child.isValid());
    });
  }

  @Test
  public void testCopyFileDescriptionFromOriginalFile() throws IOException {
    runInEdtAndWait(() -> {
      final XmlFile file = (XmlFile)createFile("a.xml", "<b>42</b>");

      getDomManager().registerFileDescription(new MockDomFileDescription<>(MyElement.class, "b", file.getVirtualFile()), getDisposable());
      ApplicationManager.getApplication().runWriteAction(() -> {
        file.setName("b.xml");
      });

      assertTrue(getDomManager().isDomFile(file));
      final XmlFile copy = (XmlFile)file.copy();
      assertTrue(getDomManager().isDomFile(copy));
      assertNotEquals(getDomManager().getFileElement(file), getDomManager().getFileElement(copy));
    });
  }

  @Test
  public void testDependantFileDescriptionCauseStackOverflow() throws IOException {
    runInEdtAndWait(() -> {
      final XmlFile interestingFile = (XmlFile)createFile("a.xml", "<b>42</b>");

      getDomManager().registerFileDescription(new MockDomFileDescription<>(MyElement.class, "b", null), getDisposable());
      for (int i = 0; i < 239; i++) {
        getDomManager().registerFileDescription(new MockDomFileDescription<>(AbstractElement.class, "b", null) {
          @Override
          @NotNull
          public Set getDependencyItems(final XmlFile file) {
            getDomManager().isDomFile(interestingFile);
            return super.getDependencyItems(file);
          }
        }, getDisposable());
      }

      getDomManager().isDomFile(interestingFile);
    });
  }

  @Test
  public void testCheckNamespace() throws IOException {
    runInEdtAndWait(() -> {
      getDomManager().registerFileDescription(new DomFileDescription<>(NamespacedElement.class, "xxx", "bar") {

        @Override
        protected void initializeFileDescription() {
          registerNamespacePolicy("foo", "bar");
        }
      }, getDisposable());

      final PsiFile file = createFile("xxx.xml", "<xxx/>");
      assertFalse(getDomManager().isDomFile(file));

      WriteCommandAction.runWriteCommandAction(getProject(), () -> {
        ((XmlFile)file).getDocument().getRootTag().setAttribute("xmlns", "bar");
      });

      assertTrue(getDomManager().isDomFile(file));
    });
  }

  @Test
  public void testCheckDtdPublicId() throws IOException {
    runInEdtAndWait(() -> {
      getDomManager().registerFileDescription(new DomFileDescription<>(NamespacedElement.class, "xxx", "bar") {

        @Override
        protected void initializeFileDescription() {
          registerNamespacePolicy("foo", "bar");
        }
      }, getDisposable());

      final PsiFile file = createFile("xxx.xml", "<xxx/>");
      assertFalse(getDomManager().isDomFile(file));

      WriteCommandAction.runWriteCommandAction(getProject(), () -> {
        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(getProject());
        final Document document = documentManager.getDocument(file);
        document.insertString(0, "<!DOCTYPE xxx PUBLIC \"bar\" \"http://java.sun.com/dtd/ejb-jar_2_0.dtd\">\n");
        documentManager.commitDocument(document);
      });

      assertTrue(getDomManager().isDomFile(file));
    });
  }

  @Test
  public void testChangeCustomDomness() throws IOException {
    runInEdtAndWait(() -> {
      getDomManager().registerFileDescription(new DomFileDescription<>(MyElement.class, "xxx") {
        @Override
        public boolean isMyFile(@NotNull final XmlFile file) {
          return file.getText().contains("foo");
        }
      }, getDisposable());
      final XmlFile file = (XmlFile)createFile("xxx.xml", "<xxx zzz=\"foo\"><boy/><boy/><xxx/>");
      final MyElement boy = getDomManager().getFileElement(file, MyElement.class).getRootElement().getBoys().get(0);
      WriteCommandAction.runWriteCommandAction(getProject(), () -> {
        file.getDocument().getRootTag().setAttribute("zzz", "bar");
      });
      assertFalse(getDomManager().isDomFile(file));
      assertFalse(boy.isValid());
    });
  }

  @Test
  public void testInvalidRootTag() throws IOException {
    runInEdtAndWait(() -> {
      final XmlFile file = (XmlFile)createFile("foo.xml", "<a b>");
      DomFileDescription<FooElement> description = new DomFileDescription<>(FooElement.class, "a");
      getDomManager().registerFileDescription(description, getDisposable());
      DomFileElementImpl<FooElement> fileElement = getDomManager().getFileElement(file, FooElement.class);
      assertNotNull(fileElement);
      assertEquals("a", fileElement.getFileDescription().getRootTagName());
    });
  }

  @Test
  public void testModuleFreeIsMyFile() throws IOException {
    runInEdtAndWait(() -> {
      final XmlFile file = (XmlFile)createFile("xxx.xml", "<xxx zzz=\"foo\"/>");
      DomFileDescription<MyElement> description = new DomFileDescription<>(MyElement.class, "xxx") {
        @Override
        public boolean isMyFile(@NotNull XmlFile file) {
          return file.getText().contains("foo");
        }
      };
      getDomManager().registerFileDescription(description, getDisposable());

      assertTrue(getDomManager().isDomFile(file));
      DomFileElementImpl<MyElement> fileElement = getDomManager().getFileElement(file, MyElement.class);
      assertNotNull(fileElement);
      assertSame(description, fileElement.getFileDescription());

      WriteCommandAction.runWriteCommandAction(getProject(), () -> {
        file.getDocument().getRootTag().setAttribute("zzz", "bar");
      });
      assertFalse(getDomManager().isDomFile(file));
      assertNull(getDomManager().getFileElement(file, MyElement.class));
    });
  }

  @Test
  public void testModuleAwareIsMyFileReceivesModule() throws IOException {
    runInEdtAndWait(() -> {
      final XmlFile file = (XmlFile)createFile("xxx.xml", "<xxx/>");
      Ref<Module> seenModule = Ref.create();
      Ref<Boolean> called = Ref.create(false);
      getDomManager().registerFileDescription(new DomFileDescription<>(MyElement.class, "xxx") {
        @SuppressWarnings("deprecation")
        @Override
        public boolean isMyFile(@NotNull XmlFile file, @Nullable Module module) {
          called.set(true);
          seenModule.set(module);
          return true;
        }
      }, getDisposable());

      assertTrue(getDomManager().isDomFile(file));
      assertTrue(called.get());
      assertSame(domFixture.get().getModule(), seenModule.get());
    });
  }

  @Test
  public void testNoDescriptionForRootTag() throws IOException {
    runInEdtAndWait(() -> {
      final XmlFile file = (XmlFile)createFile("zzz.xml", "<zzz/>");
      assertNull(DomApplicationComponent.getInstance().findDescription(file));
      assertFalse(getDomManager().isDomFile(file));
      assertNull(getDomManager().getFileElement(file, MyElement.class));
    });
  }

  private Project getProject() {
    return domFixture.get().getProject();
  }

  private DomManagerImpl getDomManager() {
    return domFixture.get().getDomManager();
  }

  private Disposable getDisposable() {
    return domFixture.get().getDisposable();
  }

  private PsiFile createFile(String fileName, String text) throws IOException {
    return domFixture.get().createSourceFile(fileName, text);
  }

  public interface AbstractElement extends GenericDomValue<String> {
    GenericAttributeValue<String> getAttr();
  }

  public interface FooElement extends AbstractElement {
  }

  public interface BarElement extends AbstractElement {
  }

  public interface ZipElement extends AbstractElement {
  }

  public interface MyElement extends DomElement {

    MyElement getChild();

    List<MyElement> getBoys();

  }

  @Namespace("foo")
  public interface NamespacedElement extends DomElement {

  }

}
