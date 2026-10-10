// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.impl.stubs

import com.intellij.openapi.extensions.ExtensionPointName
import com.jetbrains.python.psi.PyDecorator
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
interface PyCustomDecoratorStubType<T : PyCustomDecoratorStub> : PyCustomStubType<PyDecorator, T> {
  companion object {
    @JvmField
    val EP_NAME = ExtensionPointName.create<PyCustomDecoratorStubType<out PyCustomDecoratorStub>>("Pythonid.customDecoratorStubType")
  }
}