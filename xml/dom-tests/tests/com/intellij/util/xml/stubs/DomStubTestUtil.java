// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.xml.stubs;

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
import com.intellij.util.xml.DomFileElement;
import com.intellij.util.xml.DomManager;
import org.jetbrains.annotations.NotNull;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * DOM stub helpers for a test with any base class.
 * A JUnit 5 test can call them without the JUnit 3 {@link DomStubTest} on the compile classpath.
 * Call them on the EDT.
 */
public final class DomStubTestUtil {
  private DomStubTestUtil() {
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
    assertNotNull("Can't build stubs for " + path, tree);

    psiManager.cleanupForNextTest();

    file = (XmlFile)psiManager.findFile(virtualFile);
    assertNotNull(file);
    return file;
  }
}
