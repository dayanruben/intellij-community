// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.functionTypeComments;

import com.intellij.lang.PsiParser;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import com.jetbrains.python.PythonParserDefinition;
import com.jetbrains.python.codeInsight.functionTypeComments.psi.PyFunctionTypeAnnotationFile;
import org.jetbrains.annotations.NotNull;

/**
 * @author Mikhail Golubev
 */
public final class PyFunctionTypeAnnotationParserDefinition extends PythonParserDefinition {

  @Override
  public @NotNull TokenSet getCommentTokens() {
    return TokenSet.EMPTY;
  }

  @Override
  public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
    return new PyFunctionTypeAnnotationFile(viewProvider);
  }

  @Override
  public @NotNull IFileElementType getFileNodeType() {
    return PyFunctionTypeAnnotationFileElementType.INSTANCE;
  }

  @Override
  public @NotNull PsiParser createParser(Project project) {
    return new PyFunctionTypeAnnotationParser();
  }
}
