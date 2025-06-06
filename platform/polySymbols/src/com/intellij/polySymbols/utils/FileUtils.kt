// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:JvmName("FileUtils")
// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.polySymbols.utils

import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFileBase

fun findOriginalFile(file: VirtualFile?): VirtualFile? =
  file?.let {
    var f: VirtualFile? = it
    while (f is LightVirtualFileBase) {
      f = (f as? VirtualFileWindow)?.delegate ?: f.originalFile
    }
    f
  }