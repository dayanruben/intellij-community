// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.xml.impl;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;
import com.intellij.psi.XmlElementFactory;
import com.intellij.psi.impl.JavaPsiFacadeEx;
import com.intellij.psi.xml.XmlElement;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.LocalTimeCounter;
import com.intellij.util.xml.CallRegistry;
import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.DomManager;
import com.intellij.util.xml.TypeChooserManager;
import com.intellij.util.text.UniqueNameGenerator;
import com.intellij.util.xml.events.DomEvent;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DOM test helpers for a JUnit 5 test. Create the fixture with {@link DomTestFixtures#domTestFixture}.
 * The fixture records the DOM events of the project until the end of the test.
 * Call the helpers on the EDT.
 */
public final class DomTestFixture {
  private final Module myModule;
  private final Disposable myDisposable;
  private final CallRegistry<DomEvent> myCallRegistry = new CallRegistry<>();

  DomTestFixture(@NotNull Module module, @NotNull Disposable disposable) {
    myModule = module;
    myDisposable = disposable;
    getDomManager().addDomEventListener(event -> myCallRegistry.putActual(event), disposable);
  }

  public @NotNull Project getProject() {
    return myModule.getProject();
  }

  public @NotNull Module getModule() {
    return myModule;
  }

  /**
   * Returns the disposable of the test. The fixture disposes it on the EDT after the test.
   */
  public @NotNull Disposable getDisposable() {
    return myDisposable;
  }

  public @NotNull CallRegistry<DomEvent> getCallRegistry() {
    return myCallRegistry;
  }

  public @NotNull PsiManager getPsiManager() {
    return PsiManager.getInstance(getProject());
  }

  public @NotNull JavaPsiFacadeEx getJavaFacade() {
    return JavaPsiFacadeEx.getInstanceEx(getProject());
  }

  public @NotNull DomManagerImpl getDomManager() {
    return (DomManagerImpl)DomManager.getDomManager(getProject());
  }

  public @NotNull TypeChooserManager getTypeChooserManager() {
    return getDomManager().getTypeChooserManager();
  }

  public void assertCached(DomElement element, XmlElement xmlElement) {
    assertNotNull(xmlElement);
    assertSame(element.getXmlTag(), xmlElement);
    DomInvocationHandler currentDom = getDomManager().getDomHandler(xmlElement);
    assertNotNull(currentDom);
    assertEquals(element, currentDom.getProxy());
    assertTrue(element.isValid());
  }

  public void assertCached(DomFileElementImpl<?> element, XmlFile file) {
    assertNotNull(file);
    assertEquals(element, getDomManager().getFileElement(file));
  }

  public @NotNull XmlTag createTag(String text) throws IncorrectOperationException {
    return XmlElementFactory.getInstance(getProject()).createTagFromText(text);
  }

  /**
   * Creates a non-physical file with PSI events, as {@code LightPlatformTestCase.createFile} did.
   */
  public @NotNull PsiFile createFile(@NonNls @NotNull String fileName, @NonNls @NotNull String text) {
    FileType fileType = FileTypeRegistry.getInstance().getFileTypeByFileName(fileName);
    return PsiFileFactory.getInstance(getProject())
      .createFileFromText(fileName, fileType, text, LocalTimeCounter.currentTime(), true, false);
  }

  /**
   * Creates a non-physical {@code a.xml} file without PSI events, as {@code LightPlatformTestCase.createLightFile} did.
   */
  public @NotNull XmlFile createXmlFile(@NonNls @NotNull String text) throws IncorrectOperationException {
    FileType fileType = FileTypeRegistry.getInstance().getFileTypeByFileName("a.xml");
    return (XmlFile)PsiFileFactory.getInstance(getProject())
      .createFileFromText("a.xml", fileType, text, LocalTimeCounter.currentTime(), false, false);
  }

  /**
   * Creates the DOM root element of a new {@code a.xml} file and clears the recorded events.
   */
  public <T extends DomElement> T createElement(String xml, Class<T> aClass) throws IncorrectOperationException {
    T element = createElement(getDomManager(), xml, aClass);
    myCallRegistry.clear();
    return element;
  }

  public static <T extends DomElement> T createElement(DomManager domManager, String xml, Class<T> aClass)
    throws IncorrectOperationException {
    String name = "a.xml";
    //noinspection deprecation
    XmlFile file = (XmlFile)PsiFileFactory.getInstance(domManager.getProject()).createFileFromText(name, xml);
    XmlTag tag = file.getDocument().getRootTag();
    String rootTagName = tag != null ? tag.getName() : "root";
    //noinspection removal
    T element = domManager.getFileElement(file, aClass, rootTagName).getRootElement();
    assertNotNull(element);
    assertSame(tag, element.getXmlTag());
    return element;
  }

  /**
   * Creates a new directory in the source root of the module.
   */
  public @NotNull VirtualFile createSourceDirectory() throws IOException {
    return WriteAction.computeAndWait(() -> {
      VirtualFile sourceRoot = ModuleRootManager.getInstance(myModule).getSourceRoots()[0];
      String name = UniqueNameGenerator.generateUniqueName("src", it -> sourceRoot.findChild(it) == null);
      return sourceRoot.createChildDirectory(this, name);
    });
  }

  /**
   * Creates a physical file in a new source directory, as {@code JavaPsiTestCase.createFile} did.
   */
  public @NotNull PsiFile createSourceFile(@NonNls @NotNull String fileName, @NonNls @NotNull String text) throws IOException {
    VirtualFile dir = createSourceDirectory();
    VirtualFile file = WriteAction.computeAndWait(() -> {
      VirtualFile child = dir.createChildData(this, fileName);
      VfsUtil.saveText(child, text);
      return child;
    });
    IndexingTestUtil.waitUntilIndexesAreReady(getProject());
    return Objects.requireNonNull(getPsiManager().findFile(file));
  }

  public void putExpected(DomEvent event) {
    myCallRegistry.putExpected(event);
  }

  public void assertResultsAndClear() {
    myCallRegistry.assertResultsAndClear();
  }

  public void assertEventCount(int size) {
    assertEquals(size, myCallRegistry.getSize(), myCallRegistry.toString());
  }

  public void incModCount() {
    getPsiManager().dropPsiCaches();
  }
}
