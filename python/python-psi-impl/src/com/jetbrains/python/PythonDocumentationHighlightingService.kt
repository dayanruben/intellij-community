// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
open class PythonDocumentationHighlightingService {
  companion object {
    @JvmStatic
    fun getInstance(): PythonDocumentationHighlightingService {
      return service()
    }
  }

  open fun highlightedCodeSnippet(project: Project, codeSnippet: String): String = codeSnippet
  open fun styledSpan(textAttributeKey: TextAttributesKey, text: String): String = text

  @NlsSafe
  open fun highlightCodeBlockInHtml(project: Project, codeBlock: String): String = codeBlock
}
