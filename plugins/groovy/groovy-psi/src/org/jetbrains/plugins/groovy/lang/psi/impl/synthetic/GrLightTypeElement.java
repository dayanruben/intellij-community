// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy.lang.psi.impl.synthetic;

import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiType;
import com.intellij.psi.impl.light.LightElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.GroovyLanguage;
import org.jetbrains.plugins.groovy.lang.psi.GroovyElementVisitor;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.annotation.GrAnnotation;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrTypeElement;

/**
 * @author Max Medvedev
 */
public class GrLightTypeElement extends LightElement implements GrTypeElement {
  private final @NotNull PsiType myType;

  public GrLightTypeElement(@NotNull PsiType type, PsiManager manager) {
    super(manager, GroovyLanguage.INSTANCE);
    myType = type;
  }

  @Override
  public @NotNull PsiType getType() {
    return myType;
  }

  @Override
  public void accept(@NotNull GroovyElementVisitor visitor) {
    visitor.visitTypeElement(this);
  }

  @Override
  public void acceptChildren(@NotNull GroovyElementVisitor visitor) {
  }

  @Override
  public String toString() {
    return "light type element";
  }

  @Override
  public GrAnnotation @NotNull [] getAnnotations() {
    return GrAnnotation.EMPTY_ARRAY;
  }
}
