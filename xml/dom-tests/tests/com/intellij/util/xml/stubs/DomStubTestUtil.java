// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.xml.stubs;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.DebugUtil;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.psi.stubs.ObjectStubTree;
import com.intellij.psi.stubs.StubTreeLoader;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.TestDataFile;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.DomFileDescription;
import com.intellij.util.xml.DomFileElement;
import com.intellij.util.xml.DomManager;
import com.intellij.util.xml.impl.DomManagerImpl;
import com.intellij.util.xml.stubs.model.Foo;
import org.jetbrains.annotations.NotNull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DOM stub helpers for a test with any base class.
 * Call them on the EDT.
 */
public final class DomStubTestUtil {
  private static final String HTTP_FOO_DTD = "http://foo.dtd";
  private static final DomFileDescription<Foo> FOO_FILE_DESCRIPTION = new DomFileDescription<>(Foo.class, "foo", HTTP_FOO_DTD) {
    @Override
    public boolean hasStubs() {
      return true;
    }

    @Override
    public int getStubVersion() {
      return 0;
    }

    @Override
    protected void initializeFileDescription() {
      registerNamespacePolicy("foo", HTTP_FOO_DTD);
    }
  };

  private DomStubTestUtil() {
  }

  /**
   * Registers the stubbed DOM file description of the {@link Foo} test model until the disposal of {@code disposable}.
   */
  static void registerFooFileDescription(@NotNull Project project, @NotNull Disposable disposable) {
    ((DomManagerImpl)DomManager.getDomManager(project)).registerFileDescription(FOO_FILE_DESCRIPTION, disposable);
  }

  public static @NotNull ElementStub getRootStub(@TestDataFile @NotNull String filePath, @NotNull CodeInsightTestFixture fixture) {
    PsiFile psiFile = fixture.configureByFile(filePath);

    StubTreeLoader loader = StubTreeLoader.getInstance();
    VirtualFile file = psiFile.getVirtualFile();
    assertTrue(loader.canHaveStub(file));
    ObjectStubTree<?> stubTree = loader.readFromVFile(fixture.getProject(), file);
    assertNotNull(stubTree);
    ElementStub root = (ElementStub)stubTree.getRoot();
    assertNotNull(root);
    return root;
  }

  public static void doBuilderTest(@TestDataFile @NotNull String filePath, @NotNull String stubText, @NotNull CodeInsightTestFixture fixture) {
    ElementStub stub = getRootStub(filePath, fixture);
    assertEquals(stubText, DebugUtil.stubTreeToString(stub));
  }

  public static @NotNull <T extends DomElement> DomFileElement<T> prepare(@TestDataFile @NotNull String path,
                                                                          @NotNull Class<T> domClass,
                                                                          @NotNull CodeInsightTestFixture fixture) {
    XmlFile file = prepareFile(path, fixture);

    DomFileElement<T> fileElement = DomManager.getDomManager(fixture.getProject()).getFileElement(file, domClass);
    assertNotNull(fileElement);
    return fileElement;
  }

  /**
   * Copies the file to the project and builds its stubs.
   * The returned file is not parsed, so a DOM access uses the stubs.
   */
  public static @NotNull XmlFile prepareFile(@NotNull String path, @NotNull CodeInsightTestFixture fixture) {
    Project project = fixture.getProject();
    PsiManagerEx psiManager = PsiManagerEx.getInstanceEx(project);
    VirtualFile virtualFile = fixture.copyFileToProject(path);
    assertNotNull(virtualFile);
    XmlFile file = (XmlFile)psiManager.getFileManager().findFile(virtualFile);
    assertNotNull(file);
    assertFalse(file.getNode().isParsed());
    ObjectStubTree<?> tree = StubTreeLoader.getInstance().readOrBuild(project, virtualFile, file);
    assertNotNull(tree, "Can't build stubs for " + path);

    psiManager.cleanupForNextTest();

    file = (XmlFile)psiManager.findFile(virtualFile);
    assertNotNull(file);
    return file;
  }
}
