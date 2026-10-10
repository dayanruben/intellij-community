// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi;

import com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;


public interface PyKnownDecoratorProvider {
  ExtensionPointName<PyKnownDecoratorProvider> EP_NAME = ExtensionPointName.create("Pythonid.knownDecoratorProvider");

  default @NotNull Collection<PyKnownDecorator> getKnownDecorators() {
    return Collections.emptyList();
  }

  /**
   * @deprecated Use {@link #getKnownDecorators()} instead to provide a new {@link PyKnownDecorator} using {@link PyKnownDecorator.Builder}
   */
  @Deprecated(forRemoval = true)
  @Nullable
  default String toKnownDecorator(String decoratorName) {
    return null;
  }
}
