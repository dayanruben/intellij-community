// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.magicLiteral;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.psi.PsiElement;
import com.jetbrains.python.psi.StringLiteralExpression;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Any magic literal extension point should implement this interface and be installed as extension point
 * using {@link #EP_NAME}
 *
 * @author Ilya.Kazakevich
 */
@ApiStatus.Internal
public interface PyMagicLiteralExtensionPoint {

  ExtensionPointName<PyMagicLiteralExtensionPoint> EP_NAME = ExtensionPointName.create("Pythonid.magicLiteral");


  /**
   * Checks if literal is magic and supported by this extension point.
   * @param element element to check
   * @return true if magic.
   */
  boolean isMagicLiteral(@NotNull StringLiteralExpression element);


  /**
   * @return human-readable type of this literal. Actually, that is extension point name
   */
  @NotNull
  String getLiteralType();

  boolean isEnabled(final @NotNull PsiElement anchor);
}
