// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.template.impl;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolder;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public final class TemplateManagerUtilBase {

  static final Key<TemplateStateBase> TEMPLATE_STATE_KEY = Key.create("TEMPLATE_STATE_KEY");

  public static TemplateStateBase getTemplateState(@NotNull Editor editor) {
    UserDataHolder stateHolder = InjectedLanguageEditorUtil.getTopLevelEditor(editor);
    TemplateStateBase templateState = stateHolder.getUserData(TEMPLATE_STATE_KEY);
    if (templateState != null && templateState.isDisposed()) {
      setTemplateState(stateHolder, null);
      return null;
    }
    return templateState;
  }

  @ApiStatus.Internal
  public static void setTemplateState(@NotNull UserDataHolder stateHolder, @Nullable TemplateStateBase value) {
    stateHolder.putUserData(TEMPLATE_STATE_KEY, value);
  }

  @ApiStatus.Internal
  public static TemplateStateBase clearTemplateState(@NotNull Editor editor) {
    TemplateStateBase prevState = getTemplateState(editor);
    if (prevState != null) {
      Editor stateEditor = prevState.getEditor();
      if (stateEditor != null) {
        setTemplateState(stateEditor, null);
      }
    }
    return prevState;
  }

}
