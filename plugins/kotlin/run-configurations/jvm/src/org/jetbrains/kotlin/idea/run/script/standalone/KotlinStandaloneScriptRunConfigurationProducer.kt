// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.run.script.standalone

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.extensions.InternalIgnoreDependencyViolation
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.idea.core.script.v1.isRunnableKotlinScript
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

@InternalIgnoreDependencyViolation
class KotlinStandaloneScriptRunConfigurationProducer :
    LazyRunConfigurationProducer<KotlinStandaloneScriptRunConfiguration>() {
    override fun setupConfigurationFromContext(
        configuration: KotlinStandaloneScriptRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        configuration.setupFilePath(pathFromContext(context) ?: return false)
        configuration.setGeneratedName()
        return true
    }

    private fun pathFromContext(context: ConfigurationContext?): String? {
        val location = context?.location ?: return null
        return pathFromPsiElement(location.psiElement)
    }

    override fun isConfigurationFromContext(configuration: KotlinStandaloneScriptRunConfiguration, context: ConfigurationContext): Boolean {
        val filePath = configuration.filePath
        return filePath != null && filePath == pathFromContext(context)
    }

    companion object {
        fun pathFromPsiElement(element: PsiElement): String? {
            val file = element.getParentOfType<KtFile>(false) ?: return null
            val script = file.script ?: return null
            return script.containingKtFile.virtualFile.takeIf { it.isRunnableKotlinScript(file.project) }?.canonicalPath
        }
    }

    override fun getConfigurationFactory(): ConfigurationFactory = kotlinStandaloneScriptRunConfigurationType()
}
