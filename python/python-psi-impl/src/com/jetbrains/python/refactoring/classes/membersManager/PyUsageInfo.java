// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.refactoring.classes.membersManager;

import com.intellij.usageView.UsageInfo;
import com.jetbrains.python.psi.PyClass;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * TODO: Make it generic to allow to reuse in another projects?
 * Usage info that displays destination (where should member be moved)
 *
 * @author Ilya.Kazakevich
 */
@ApiStatus.Internal
public final class PyUsageInfo extends UsageInfo {
  private final @NotNull PyClass myTo;

  PyUsageInfo(final @NotNull PyClass to) {
    super(to, true); //TODO: Make super generic and get rid of field?
    myTo = to;
  }

  public @NotNull PyClass getTo() {
    return myTo;
  }
}
