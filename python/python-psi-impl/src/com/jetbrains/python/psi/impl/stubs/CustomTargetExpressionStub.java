// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.impl.stubs;

import com.intellij.psi.util.QualifiedName;
import org.jetbrains.annotations.Nullable;


public interface CustomTargetExpressionStub extends PyCustomStub {

  @Nullable
  QualifiedName getCalleeName();
}
