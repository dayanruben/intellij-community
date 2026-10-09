// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.completion;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.DumbAware;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.filters.ElementFilter;
import com.intellij.psi.filters.position.FilterPattern;
import com.intellij.util.ProcessingContext;
import com.intellij.util.Processor;
import com.jetbrains.python.PythonLanguage;
import com.jetbrains.python.psi.LanguageLevel;
import com.jetbrains.python.psi.PyArgumentList;
import com.jetbrains.python.psi.PyClass;
import com.jetbrains.python.psi.PyExpressionStatement;
import com.jetbrains.python.psi.PyReferenceExpression;
import com.jetbrains.python.psi.PyStatementList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class PyMetaClassCompletionContributor extends CompletionContributor implements DumbAware {
  public PyMetaClassCompletionContributor() {
    extend(CompletionType.BASIC,
           PlatformPatterns
             .psiElement()
             .withLanguage(PythonLanguage.getInstance())
             .withParents(PyReferenceExpression.class, PyExpressionStatement.class, PyStatementList.class, PyClass.class)
             .and(hasLanguageLevel(LanguageLevel::isPython2)),
           new CompletionProvider<>() {
             @Override
             protected void addCompletions(@NotNull CompletionParameters parameters,
                                           @NotNull ProcessingContext context,
                                           @NotNull CompletionResultSet result) {
               result.addElement(LookupElementBuilder.create("__metaclass__ = "));
             }
           });
    extend(CompletionType.BASIC,
           PlatformPatterns
             .psiElement()
             .withLanguage(PythonLanguage.getInstance())
             .withParents(PyReferenceExpression.class, PyArgumentList.class, PyClass.class)
             .and(hasLanguageLevel(level -> !level.isPython2())),
           new CompletionProvider<>() {
             @Override
             protected void addCompletions(@NotNull CompletionParameters parameters,
                                           @NotNull ProcessingContext context,
                                           @NotNull CompletionResultSet result) {
               result.addElement(LookupElementBuilder.create("metaclass="));
             }
           });
  }

  public static FilterPattern hasLanguageLevel(final @NotNull Processor<? super LanguageLevel> processor) {
    return new FilterPattern(new ElementFilter() {
      @Override
      public boolean isAcceptable(Object element, @Nullable PsiElement context) {
        if (element instanceof PsiElement) {
          return processor.process(LanguageLevel.forElement((PsiElement)element));
        }
        return false;
      }

      @Override
      public boolean isClassAcceptable(Class hintClass) {
        return true;
      }
    });
  }
}
