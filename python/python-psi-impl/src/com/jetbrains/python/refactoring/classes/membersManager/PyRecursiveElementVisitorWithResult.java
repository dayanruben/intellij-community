// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.refactoring.classes.membersManager;

import com.intellij.util.containers.MultiMap;
import com.jetbrains.python.psi.PyClass;
import com.jetbrains.python.psi.PyElement;
import com.jetbrains.python.psi.PyRecursiveElementVisitor;
import org.jetbrains.annotations.NotNull;

/**
 * Recursive visitor with multimap, to be used for {@link MembersManager#getDependencies(PyElement)}
 */
class PyRecursiveElementVisitorWithResult extends PyRecursiveElementVisitor {
  protected final @NotNull MultiMap<PyClass, PyElement> myResult;

  PyRecursiveElementVisitorWithResult() {
    myResult = new MultiMap<>();
  }
}
