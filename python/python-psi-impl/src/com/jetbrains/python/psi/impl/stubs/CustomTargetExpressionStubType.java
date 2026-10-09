// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.impl.stubs;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.psi.stubs.IndexSink;
import com.jetbrains.python.psi.PyTargetExpression;
import com.jetbrains.python.psi.stubs.PyTargetExpressionStub;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public abstract class CustomTargetExpressionStubType<T extends CustomTargetExpressionStub>
  implements PyCustomStubType<PyTargetExpression, T> {

  public static final ExtensionPointName<CustomTargetExpressionStubType<? extends CustomTargetExpressionStub>> EP_NAME =
    ExtensionPointName.create("Pythonid.customTargetExpressionStubType");

  public void indexStub(PyTargetExpressionStub stub, IndexSink sink) {
  }
}
