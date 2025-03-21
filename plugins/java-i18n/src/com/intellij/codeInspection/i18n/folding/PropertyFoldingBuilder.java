// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.i18n.folding;

import com.intellij.codeInsight.folding.JavaCodeFoldingSettings;
import com.intellij.codeInspection.i18n.JavaI18nUtil;
import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.lang.properties.IProperty;
import com.intellij.lang.properties.parsing.PropertiesElementTypes;
import com.intellij.lang.properties.psi.Property;
import com.intellij.lang.properties.psi.impl.PropertyImpl;
import com.intellij.lang.properties.psi.impl.PropertyStubImpl;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Couple;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.jsp.JspLanguage;
import com.intellij.psi.jsp.JspxLanguage;
import com.intellij.psi.stubs.PsiFileStubImpl;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.uast.*;
import org.jetbrains.uast.expressions.UInjectionHost;

import java.util.*;

/**
 * @author Konstantin Bulenkov
 */
public final class PropertyFoldingBuilder extends FoldingBuilderEx {
  private static final int FOLD_MAX_LENGTH = 50;
  private static final Key<IProperty> CACHE = Key.create("i18n.property.cache");
  public static final IProperty NULL = new PropertyImpl(new PropertyStubImpl(new PsiFileStubImpl<>(null), null), PropertiesElementTypes.PROPERTY_TYPE);

  @Override
  public FoldingDescriptor @NotNull [] buildFoldRegions(@NotNull PsiElement element, @NotNull Document document, boolean quick) {
    if (!(element instanceof PsiFile file) || quick || !isFoldingsOn()) {
      return FoldingDescriptor.EMPTY_ARRAY;
    }
    final List<FoldingDescriptor> result = new ArrayList<>();
    boolean hasJsp = ContainerUtil.exists(file.getViewProvider().getLanguages(), (l) -> l instanceof JspLanguage || l instanceof JspxLanguage);
    //hack here because JspFile PSI elements are not threaded correctly via nextSibling/prevSibling
    file.accept(hasJsp ? new JavaRecursiveElementWalkingVisitor() {
      @Override
      public void visitLiteralExpression(@NotNull PsiLiteralExpression expression) {
        ProgressManager.checkCanceled();
        UInjectionHost injectionHost = UastContextKt.toUElement(expression, UInjectionHost.class);
        if (injectionHost != null) {
          checkLiteral(document, injectionHost, result);
        }
      }
    } : new PsiRecursiveElementWalkingVisitor() {

      @Override
      public void visitElement(@NotNull PsiElement element) {
        ProgressManager.checkCanceled();
        UInjectionHost injectionHost = UastContextKt.toUElement(element, UInjectionHost.class);
        if (injectionHost != null) {
          checkLiteral(document, injectionHost, result);
        }
        super.visitElement(element);
      }
    });

    return result.toArray(FoldingDescriptor.EMPTY_ARRAY);
  }

  private static boolean isFoldingsOn() {
    return JavaCodeFoldingSettings.getInstance().isCollapseI18nMessages();
  }

  private static void checkLiteral(Document document,
                                   UInjectionHost injectionHost,
                                   List<? super FoldingDescriptor> result) {
    PsiElement sourcePsi = injectionHost.getSourcePsi();
    if (sourcePsi == null) return;
    if (!isI18nProperty(injectionHost)) return;
    final IProperty property = getI18nProperty(injectionHost);
    final HashSet<Object> set = new HashSet<>();
    set.add(property != null ? property : PsiModificationTracker.MODIFICATION_COUNT);
    final String msg = formatI18nProperty(injectionHost, property);

    final UElement parent = injectionHost.getUastParent();
    if (!msg.equals(UastLiteralUtils.getValueIfStringLiteral(injectionHost)) &&
        parent instanceof UCallExpression expressions &&
        expressions.getValueArguments().get(0).getSourcePsi() == injectionHost.getSourcePsi()) {
      PsiElement callSourcePsi = expressions.getSourcePsi();
      if (callSourcePsi == null) return;
      final int count = JavaI18nUtil.getPropertyValueParamsMaxCount(injectionHost);
      final List<UExpression> args = expressions.getValueArguments();
      if (args.size() == 1 + count) {
        boolean ok = true;
        for (int i = 1; i < count + 1; i++) {
          Object value = args.get(i).evaluate();
          if (value == null) {
            if (!(args.get(i) instanceof UReferenceExpression)) {
              ok = false;
              break;
            }
          }
        }
        if (ok) {
          UExpression receiver = expressions.getReceiver();
          PsiElement receiverSourcePsi = receiver != null ? receiver.getSourcePsi() : null;
          PsiElement elementToFold = null;
          if (receiverSourcePsi != null) {
            elementToFold = PsiTreeUtil.findCommonParent(callSourcePsi, receiverSourcePsi);
          }
          if (elementToFold == null) {
            elementToFold = callSourcePsi;
          }
          result.add(
            new FoldingDescriptor(Objects.requireNonNull(elementToFold.getNode()), elementToFold.getTextRange(), null,
                                  formatMethodCallExpression(expressions), isFoldingsOn(), set));
          if (property != null) {
            EditPropertyValueAction.registerFoldedElement(elementToFold, document);
          }
          return;
        }
      }
      else {
        return;
      }
    }
    result.add(new FoldingDescriptor(Objects.requireNonNull(sourcePsi.getNode()), sourcePsi.getTextRange(), null,
                                     getI18nMessage(injectionHost), isFoldingsOn(), set));
    if (property != null) {
      EditPropertyValueAction.registerFoldedElement(sourcePsi, document);
    }
  }


  @Override
  public String getPlaceholderText(@NotNull ASTNode node) {
    return null;
  }

  private static @NotNull String formatMethodCallExpression(@NotNull UCallExpression methodCallExpression) {
    return format(methodCallExpression).first;
  }

  /**
   * A list of offset pairs returned along with the formatted string allows to map positions in the resulting string to the positions
   * in the original property value. First offset in each couple is the offset in the original string, and the second one - corresponding
   * offset in the formatted string. For each placeholder value substituted in the property value, two couples of offsets are returned -
   * one for the start of the placeholder, and one for the end.
   */
  public static @NotNull Pair<String, List<Couple<Integer>>> format(@NotNull UCallExpression methodCallExpression) {
    final List<UExpression> args = methodCallExpression.getValueArguments();
    PsiElement callSourcePsi = methodCallExpression.getSourcePsi();
    if (!args.isEmpty() && args.get(0) instanceof UInjectionHost injectionHost && isI18nProperty(injectionHost)) {
      final int count = JavaI18nUtil.getPropertyValueParamsMaxCount(args.get(0));
      if (args.size() == 1 + count) {
        String text = getI18nMessage((UInjectionHost)args.get(0));
        List<Couple<Integer>> replacementPositions = new ArrayList<>();
        for (int i = 1; i < count + 1; i++) {
          Object value = args.get(i).evaluate();
          if (value == null) {
            if (args.get(i) instanceof UReferenceExpression) {
              PsiElement sourcePsi = args.get(i).getSourcePsi();
              value = "{" + (sourcePsi != null ? sourcePsi.getText() : "<error>") + "}";
            }
            else {
              text = null;
              break;
            }
          }
          text = replacePlaceholder(text, "{" + (i - 1) + "}", value.toString(), replacementPositions);
        }
        if (text != null) {
          return Pair.create(text.length() > FOLD_MAX_LENGTH ? text.substring(0, FOLD_MAX_LENGTH - 3) + "...\"" : text,
                             replacementPositions);
        }
      }
    }

    return Pair.create(callSourcePsi != null ? callSourcePsi.getText() : "<error>", null);
  }

  private static String replacePlaceholder(String text, String placeholder, String replacement,
                                           List<Couple<Integer>> replacementPositions) {
    int curPos = 0;
    do {
      int placeholderPos = text.indexOf(placeholder, curPos);
      if (placeholderPos < 0) break;
      text = text.substring(0, placeholderPos) + replacement + text.substring(placeholderPos + placeholder.length());

      ListIterator<Couple<Integer>> it = replacementPositions.listIterator();
      int diff = 0;
      while (it.hasNext()) {
        Couple<Integer> next = it.next();
        if (next.second > placeholderPos) {
          it.previous();
          break;
        }
        diff = next.second - next.first;
      }
      it.add(Couple.of(placeholderPos - diff, placeholderPos));
      it.add(Couple.of(placeholderPos - diff + placeholder.length(), placeholderPos + replacement.length()));
      while (it.hasNext()) {
        Couple<Integer> next = it.next();
        it.set(Couple.of(next.first, next.second + replacement.length() - placeholder.length()));
      }

      curPos = placeholderPos + replacement.length();
    }
    while (true);
    return text;
  }

  private static @NotNull String getI18nMessage(@NotNull UInjectionHost injectionHost) {
    final IProperty property = getI18nProperty(injectionHost);
    return property == null ? injectionHost.evaluateToString() : formatI18nProperty(injectionHost, property);
  }

  public static @Nullable IProperty getI18nProperty(@NotNull UInjectionHost injectionHost) {
    PsiElement sourcePsi = injectionHost.getSourcePsi();
    if (sourcePsi == null) return null;
    final Property property = (Property)sourcePsi.getUserData(CACHE);
    if (property == NULL) return null;
    if (property != null && isValid(property, injectionHost)) return property;
    if (isI18nProperty(injectionHost)) {
      final Iterable<PsiReference> references = UastLiteralUtils.getInjectedReferences(injectionHost);
      for (PsiReference reference : references) {
        if (reference instanceof PsiPolyVariantReference) {
          final ResolveResult[] results = ((PsiPolyVariantReference)reference).multiResolve(false);
          for (ResolveResult result : results) {
            final PsiElement element = result.getElement();
            if (element instanceof IProperty p) {
              sourcePsi.putUserData(CACHE, p);
              return p;
            }
          }
        }
        else {
          final PsiElement element = reference.resolve();
          if (element instanceof IProperty p) {
            sourcePsi.putUserData(CACHE, p);
            return p;
          }
        }
      }
    }
    return null;
  }

  private static boolean isValid(Property property, UInjectionHost injectionHost) {
    if (injectionHost == null || property == null || !property.isValid()) return false;
    Object result = injectionHost.evaluate();
    if (!(result instanceof String)) return false;
    return StringUtil.unquoteString(((String)result)).equals(property.getKey());
  }

  private static @NotNull String formatI18nProperty(@NotNull UInjectionHost injectionHost, IProperty property) {
    Object evaluated = injectionHost.evaluate();
    return property == null ?
           evaluated != null ? evaluated.toString() : "null" : "\"" + property.getValue() + "\"";
  }

  @Override
  public boolean isCollapsedByDefault(@NotNull ASTNode node) {
    return isFoldingsOn();
  }

  public static boolean isI18nProperty(@NotNull PsiLiteralExpression expr) {
    UInjectionHost uLiteralExpression = UastContextKt.toUElement(expr, UInjectionHost.class);
    if (uLiteralExpression == null) return false;
    return isI18nProperty(uLiteralExpression);
  }

  public static boolean isI18nProperty(@NotNull UInjectionHost expr) {
    if (!expr.isString()) return false;
    PsiElement sourcePsi = expr.getSourcePsi();
    if (sourcePsi == null) return false;
    final IProperty property = sourcePsi.getUserData(CACHE);
    if (property == NULL) return false;
    if (property != null) return true;

    final boolean isI18n = JavaI18nUtil.mustBePropertyKey(expr, null);
    if (!isI18n) {
      sourcePsi.putUserData(CACHE, NULL);
    }
    return isI18n;
  }
}
