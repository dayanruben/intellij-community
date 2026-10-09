// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.impl;

import com.intellij.navigation.ColoredItemPresentation;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.PyPsiBundle;
import com.jetbrains.python.psi.PyClass;
import com.jetbrains.python.psi.PyElement;
import com.jetbrains.python.psi.PyPossibleClassMember;
import com.jetbrains.python.psi.resolve.QualifiedNameFinder;
import com.jetbrains.python.pyi.PyiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

public class PyElementPresentation implements ColoredItemPresentation {
  private final @NotNull PyElement myElement;

  public PyElementPresentation(@NotNull PyElement element) {
    myElement = element;
  }

  @Override
  public @Nullable TextAttributesKey getTextAttributesKey() {
    return null;
  }

  @Override
  public @Nullable String getPresentableText() {
    final String name = myElement.getName();
    return name != null ? name : PyNames.UNNAMED_ELEMENT;
  }

  @Override
  public @Nullable String getLocationString() {
    PsiFile containingFile = myElement.getContainingFile();

    String packageForFile = getPackageForFile(containingFile);
    if (packageForFile == null) return null;

    boolean isPyiFile = containingFile instanceof PyiFile;

    PyClass containingClass = myElement instanceof PyPossibleClassMember ? ((PyPossibleClassMember)myElement).getContainingClass() : null;
    if (containingClass != null) {
      if (isPyiFile) {
        return PyPsiBundle.message("element.presentation.location.string.in.class.stub", containingClass.getName(), packageForFile);
      }
      else {
        return PyPsiBundle.message("element.presentation.location.string.in.class", containingClass.getName(), packageForFile);
      }
    }

    if (isPyiFile) {
      return PyPsiBundle.message("element.presentation.location.string.module.stub", packageForFile);
    }
    else {
      return PyPsiBundle.message("element.presentation.location.string.module", packageForFile);
    }
  }

  @Override
  public @Nullable Icon getIcon(boolean unused) {
    return myElement.getIcon(0);
  }

  public static @Nullable String getPackageForFile(@NotNull PsiFile containingFile) {
    final VirtualFile vFile = containingFile.getVirtualFile();

    if (vFile != null) {
      return QualifiedNameFinder.findShortestImportableName(containingFile, vFile);
    }
    return null;
  }
}
