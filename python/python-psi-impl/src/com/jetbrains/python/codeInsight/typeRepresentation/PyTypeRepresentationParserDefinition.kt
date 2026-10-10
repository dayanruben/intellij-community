// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.typeRepresentation

import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.jetbrains.python.PythonParserDefinition
import com.jetbrains.python.codeInsight.typeRepresentation.psi.PyTypeRepresentationFile

class PyTypeRepresentationParserDefinition : PythonParserDefinition() {
  override fun getCommentTokens(): TokenSet = TokenSet.EMPTY

  override fun createFile(viewProvider: FileViewProvider): PsiFile = PyTypeRepresentationFile(viewProvider)

  override fun getFileNodeType(): IFileElementType = PyTypeRepresentationFileElementType

  override fun createParser(project: Project?): PsiParser = PyTypeRepresentationParser()
}
