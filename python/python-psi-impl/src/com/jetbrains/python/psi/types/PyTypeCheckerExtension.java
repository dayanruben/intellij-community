// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.types;

import com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Extension to make additional check of {@link PyType} in {@link PyTypeChecker}
 */
@ApiStatus.Experimental
public interface PyTypeCheckerExtension {

  ExtensionPointName<PyTypeCheckerExtension> EP_NAME = ExtensionPointName.create("Pythonid.typeCheckerExtension");

  @NotNull Optional<Boolean> match(@Nullable PyType expected,
                                   @Nullable PyType actual,
                                   @NotNull TypeEvalContext context,
                                   @NotNull PyTypeChecker.GenericSubstitutions substitutions);
}