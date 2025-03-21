// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.structuralsearch.plugin.ui;

import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.FakePsiElement;
import com.intellij.psi.impl.source.DummyHolder;
import com.intellij.psi.impl.source.DummyHolderFactory;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class StructuralSearchTemplateDocumentationProvider extends AbstractDocumentationProvider {
  @Override
  public PsiElement getDocumentationElementForLookupItem(PsiManager psiManager, Object object, PsiElement element) {
    if (object instanceof Configuration) {
      return new ConfigurationElement((Configuration)object, psiManager);
    }
    return null;
  }

  @Override
  public @Nls String generateDoc(PsiElement element, @Nullable PsiElement originalElement) {
    if (!(element instanceof ConfigurationElement)) {
      return null;
    }

    Configuration configuration = ((ConfigurationElement)element).getConfiguration();
    return DocumentationMarkup.DEFINITION_START + StringUtil.escapeXmlEntities(configuration.getName()) + DocumentationMarkup.DEFINITION_END +
           DocumentationMarkup.CONTENT_START + StringUtil.escapeXmlEntities(configuration.getMatchOptions().getSearchPattern()) +
           DocumentationMarkup.CONTENT_END;
  }

  private static class ConfigurationElement extends FakePsiElement {
    private final @NotNull Configuration myConfiguration;
    private final @NotNull PsiManager myPsiManager;
    private final @NotNull DummyHolder myDummyHolder;

    ConfigurationElement(@NotNull Configuration configuration, @NotNull PsiManager psiManager) {
      myConfiguration = configuration;
      myPsiManager = psiManager;
      myDummyHolder = DummyHolderFactory.createHolder(myPsiManager, null);
    }

    public @NotNull Configuration getConfiguration() {
      return myConfiguration;
    }

    @Override
    public PsiElement getParent() {
      return myDummyHolder;
    }

    @Override
    public ItemPresentation getPresentation() {
      return new ItemPresentation() {
        @Override
        public String getPresentableText() {
          return myConfiguration.getName();
        }

        @Override
        public @Nullable Icon getIcon(boolean unused) {
          return null;
        }
      };
    }

    @Override
    public PsiManager getManager() {
      return myPsiManager;
    }
  }
}
