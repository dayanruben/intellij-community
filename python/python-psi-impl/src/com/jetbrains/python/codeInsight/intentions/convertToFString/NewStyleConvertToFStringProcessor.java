// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.intentions.convertToFString;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.PyNewStyleStringFormatParser;
import com.jetbrains.python.PyNewStyleStringFormatParser.Field;
import com.jetbrains.python.codeInsight.PySubstitutionChunkReference;
import com.jetbrains.python.psi.PyBinaryExpression;
import com.jetbrains.python.psi.PyCallExpression;
import com.jetbrains.python.psi.PyExpression;
import com.jetbrains.python.psi.PyNumericLiteralExpression;
import com.jetbrains.python.psi.PyPrefixExpression;
import com.jetbrains.python.psi.PyStringLiteralExpression;
import com.jetbrains.python.psi.PyStringLiteralUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Mikhail Golubev
 */
public class NewStyleConvertToFStringProcessor extends BaseConvertToFStringProcessor<Field> {
  public NewStyleConvertToFStringProcessor(@NotNull PyStringLiteralExpression pyString) {
    super(pyString);
  }

  @Override
  protected @NotNull List<Field> extractAllSubstitutionChunks() {
    return PyNewStyleStringFormatParser.parse(myPyString.getText()).getAllFields();
  }

  @Override
  protected @NotNull List<Field> extractTopLevelSubstitutionChunks() {
    return PyNewStyleStringFormatParser.parse(myPyString.getText()).getFields();
  }

  @Override
  protected @NotNull PySubstitutionChunkReference createReference(@NotNull Field field) {
    return new PySubstitutionChunkReference(myPyString, field);
  }

  @Override
  protected boolean checkChunk(@NotNull Field chunk) {
    return true;
  }

  @Override
  public @NotNull PyExpression getWholeExpressionToReplace() {
    //noinspection ConstantConditions
    return PsiTreeUtil.getParentOfType(myPyString, PyCallExpression.class);
  }

  @Override
  protected @Nullable PsiElement getValuesSource() {
    final PyCallExpression callExpression = PsiTreeUtil.getParentOfType(myPyString, PyCallExpression.class);
    assert callExpression != null;
    return callExpression.getArgumentList();
  }

  @Override
  protected @Nullable PsiElement prepareExpressionToInject(@NotNull PyExpression expression, @NotNull Field field) {
    final PsiElement prepared = super.prepareExpressionToInject(expression, field);
    if (prepared == null) return null;

    // You cannot access attributes on numeric literals without wrapping them in parentheses
    if (!field.getAttributesAndLookups().isEmpty() && (prepared instanceof PyBinaryExpression ||
                                                       prepared instanceof PyPrefixExpression ||
                                                       prepared instanceof PyNumericLiteralExpression)) {
      return wrapExpressionInParentheses(prepared);
    }
    return prepared;
  }

  @Override
  protected boolean processSubstitutionChunk(@NotNull Field field, @NotNull StringBuilder fStringText) {

    final String stringText = myPyString.getText();

    // Actual format field
    fStringText.append("{");
    final PySubstitutionChunkReference reference = createReference(field);
    final PyExpression resolveResult = adjustResolveResult(reference.resolve());
    if (resolveResult == null) return false;

    final PsiElement adjusted = prepareExpressionToInject(resolveResult, field);
    if (adjusted == null) return false;

    fStringText.append(adjusted.getText());
    final String quotedAttrsAndItems = quoteItemsInFragments(field);
    if (quotedAttrsAndItems == null) return false;

    fStringText.append(quotedAttrsAndItems);

    // Conversion is copied as is if it's present
    final String conversion = field.getConversion();
    if (conversion != null) {
      fStringText.append(conversion);
    }

    // Format spec is copied if present handling nested fields
    final TextRange specRange = field.getFormatSpecRange();
    if (specRange != null) {
      int specOffset = specRange.getStartOffset();
      // Do not proceed too nested fields
      if (field.getDepth() == 1) {
        for (Field nestedField : field.getNestedFields()) {
          // Copy text of the format spec between nested fragments
          fStringText.append(stringText, specOffset, nestedField.getLeftBraceOffset());
          specOffset = nestedField.getFieldEnd();

          // recursively format nested field
          if (!processSubstitutionChunk(nestedField, fStringText)) {
            return false;
          }
        }
      }
      if (specOffset < specRange.getEndOffset()) {
        fStringText.append(stringText, specOffset, specRange.getEndOffset());
      }
    }

    fStringText.append("}");
    return true;
  }

  @Override
  protected void processLiteralChunk(@NotNull String chunk, @NotNull StringBuilder fStringText) {
    fStringText.append(chunk);
  }

  private @Nullable String quoteItemsInFragments(@NotNull Field field) {
    final List<String> escaped = new ArrayList<>();
    for (String part : field.getAttributesAndLookups()) {
      if (part.startsWith(".")) {
        escaped.add(part);
      }
      else if (part.startsWith("[")) {
        if (part.contains("\\")) {
          return null;
        }
        final String indexText = part.substring(1, part.length() - 1);
        if (indexText.matches("\\d+")) {
          escaped.add(part);
          continue;
        }
        final char originalQuote = myNodeInfo.getSingleQuote();
        char targetQuote = PyStringLiteralUtil.flipQuote(originalQuote);
        // there are no escapes inside the fragment, so the lookup key cannot contain 
        // the host string quote unless it's a multiline string literal
        if (indexText.indexOf(targetQuote) >= 0) {
          if (!myNodeInfo.isTripleQuoted() || indexText.indexOf(originalQuote) >= 0) {
            return null;
          }
          targetQuote = originalQuote;
        }
        escaped.add("[" + targetQuote + indexText + targetQuote + "]");
      }
    }
    return StringUtil.join(escaped, "");
  }
}
