// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.template.impl;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class TemplateSubstitutionContext {
  private final @NotNull Project myProject;
  private final @NotNull Editor myEditor;

  @ApiStatus.Internal
  public TemplateSubstitutionContext(@NotNull Project project, @NotNull Editor editor) {
    myProject = project;
    myEditor = editor;
  }

  public @NotNull Project getProject() {
    return myProject;
  }

  /**
   * @return offset in the {@link #getDocument() document} where template is going to be inserted
   */
  public int getOffset() {
    return myEditor.getCaretModel().getOffset();
  }

  public @NotNull PsiFile getPsiFile() {
    Document document = myEditor.getDocument();
    PsiFile psiFile = PsiDocumentManager.getInstance(myProject).getPsiFile(document);
    return Objects.requireNonNull(psiFile, () -> "Can't find a psi file for the " + document + " in " + myEditor);
  }

  /**
   * @return document prepared for live template insertion
   * @apiNote document may differ from the text of the {@link #getPsiFile() psiFile}, e.g. selected text is already removed from the editor.
   */
  public @NotNull Document getDocument() {
    return myEditor.getDocument();
  }
}
