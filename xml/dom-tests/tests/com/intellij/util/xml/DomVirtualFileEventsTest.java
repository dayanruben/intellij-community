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

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import com.intellij.util.xml.events.DomEvent;
import com.intellij.util.xml.impl.DomFileElementImpl;
import com.intellij.util.xml.impl.DomTestFixture;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.intellij.testFramework.EdtTestUtil.runInEdtAndWait;
import static com.intellij.util.xml.impl.DomTestFixtures.domModuleFixture;
import static com.intellij.util.xml.impl.DomTestFixtures.domTestFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestApplication
public class DomVirtualFileEventsTest {
  private final TestFixture<Module> moduleFixture = domModuleFixture();
  private final TestFixture<DomTestFixture> domFixture = domTestFixture(moduleFixture);

  @BeforeEach
  void setUp() {
    runInEdtAndWait(() -> {
      domFixture.get().getDomManager().registerFileDescription(new DomFileDescription(MyElement.class, "a") {

        @Override
        public boolean isMyFile(@NotNull final XmlFile file) {
          return super.isMyFile(file) && file.getName().contains("a");
        }
      }, domFixture.get().getDisposable());
    });
  }

  @Test
  public void testCreateFile() throws IOException {
    runInEdtAndWait(() -> {
      WriteCommandAction.writeCommandAction(domFixture.get().getProject()).run(() -> {
        final VirtualFile dir = domFixture.get().createSourceDirectory();
        final VirtualFile childData = dir.createChildData(this, "abc.xml");
        System.gc();
        System.gc();
        System.gc();
        System.gc();
        domFixture.get().assertResultsAndClear();
        setFileText(childData, "<a/>");
        domFixture.get().assertEventCount(0);
        domFixture.get().assertResultsAndClear();
      });
    });
  }

  @Test
  public void testDeleteFile() throws IOException {
    runInEdtAndWait(() -> {
      WriteCommandAction.writeCommandAction(domFixture.get().getProject()).run(() -> {
        final VirtualFile dir = domFixture.get().createSourceDirectory();
        final VirtualFile childData = dir.createChildData(this, "abc.xml");
        domFixture.get().assertResultsAndClear();
        setFileText(childData, "<a/>");
        final DomFileElementImpl<DomElement> fileElement = getFileElement(childData);
        domFixture.get().assertResultsAndClear();

        childData.delete(this);
        domFixture.get().assertEventCount(1);
        domFixture.get().putExpected(new DomEvent(fileElement, false));
        domFixture.get().assertResultsAndClear();
        assertFalse(fileElement.isValid());
      });
    });
  }

  @Test
  public void testRenameFile() throws IOException {
    runInEdtAndWait(() -> {
      WriteCommandAction.writeCommandAction(domFixture.get().getProject()).run(() -> {
        final VirtualFile dir = domFixture.get().createSourceDirectory();
        final VirtualFile data = dir.createChildData(this, "abc.xml");
        setFileText(data, "<a/>");
        PsiDocumentManager.getInstance(domFixture.get().getProject()).commitAllDocuments();
        DomFileElementImpl<DomElement> fileElement = getFileElement(data);
        domFixture.get().assertEventCount(0);
        domFixture.get().assertResultsAndClear();

        data.rename(this, "deaf.xml");
        domFixture.get().assertEventCount(1);
        domFixture.get().putExpected(new DomEvent(fileElement, false));
        domFixture.get().assertResultsAndClear();
        assertEquals(fileElement, getFileElement(data));
        assertTrue(fileElement.isValid());
        fileElement = getFileElement(data);

        data.rename(this, "fff.xml");
        domFixture.get().assertEventCount(1);
        domFixture.get().putExpected(new DomEvent(fileElement, false));
        domFixture.get().assertResultsAndClear();
        assertNull(getFileElement(data));
        assertFalse(fileElement.isValid());
      });
    });
  }

  private DomFileElementImpl<DomElement> getFileElement(final VirtualFile file) {
    return domFixture.get().getDomManager().getFileElement((XmlFile)domFixture.get().getPsiManager().findFile(file));
  }

  private static void setFileText(VirtualFile file, String text) throws IOException {
    WriteAction.runAndWait(() -> LoadTextUtil.write(null, file, file, text, -1));
  }

  public interface MyElement extends DomElement {
  }

}
