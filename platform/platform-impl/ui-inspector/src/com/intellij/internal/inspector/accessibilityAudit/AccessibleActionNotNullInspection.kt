// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.internal.inspector.accessibilityAudit

import org.jetbrains.annotations.ApiStatus
import javax.accessibility.Accessible
import javax.accessibility.AccessibleRole

@ApiStatus.Internal
@ApiStatus.Experimental
class AccessibleActionNotNullInspection : UiInspectorAccessibilityInspection {
  override val propertyName: String = "AccessibleAction"
  override val severity: Severity = Severity.WARNING

  override fun passesInspection(accessible: Accessible?): Boolean {
    val context = accessible?.accessibleContext ?: return true
    if (context.accessibleRole in arrayOf(AccessibleRole.PUSH_BUTTON,
                                          AccessibleRole.TOGGLE_BUTTON,
                                          AccessibleRole.CHECK_BOX,
                                          AccessibleRole.RADIO_BUTTON,
                                          AccessibleRole.COMBO_BOX,
                                          AccessibleRole.HYPERLINK)) {
      return context.accessibleAction != null
    }
    return true
  }
}
