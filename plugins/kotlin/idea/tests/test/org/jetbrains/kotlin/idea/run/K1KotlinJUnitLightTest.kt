// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.run

import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode

class K1KotlinJUnitLightTest : KotlinJUnitLightTest() {
    override val pluginMode: KotlinPluginMode
        get() = KotlinPluginMode.K1
}