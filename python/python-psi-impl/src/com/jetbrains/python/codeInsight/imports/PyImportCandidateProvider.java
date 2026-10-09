// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.imports;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.psi.PsiReference;


public interface PyImportCandidateProvider {
  ExtensionPointName<PyImportCandidateProvider> EP_NAME = ExtensionPointName.create("Pythonid.importCandidateProvider");

  void addImportCandidates(PsiReference reference, String name, AutoImportQuickFix quickFix);
}
