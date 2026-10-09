// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.documentation;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jsoup.nodes.Document;

import java.util.function.Function;


public interface PythonDocumentationLinkProvider {
  ExtensionPointName<PythonDocumentationLinkProvider> EP_NAME = ExtensionPointName.create("Pythonid.documentationLinkProvider");

  @Nullable
  String getExternalDocumentationUrl(PsiElement element, PsiElement originalElement);

  default @Nullable Function<Document, @Nls String> quickDocExtractor(@NotNull PsiNamedElement namedElement) {
    return null;
  }
}
