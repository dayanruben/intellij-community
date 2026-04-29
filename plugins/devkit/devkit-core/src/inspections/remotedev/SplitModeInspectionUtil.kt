// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("HardCodedStringLiteral")

package org.jetbrains.idea.devkit.inspections.remotedev

import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.IntelliJProjectUtil
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.xml.XmlFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.annotations.Nls
import org.jetbrains.idea.devkit.DevKitBundle
import org.jetbrains.idea.devkit.dom.ContentDescriptor.ModuleDescriptor
import org.jetbrains.idea.devkit.dom.index.PluginIdDependenciesIndex
import org.jetbrains.idea.devkit.inspections.remotedev.analysis.ResolvedModuleKind
import org.jetbrains.idea.devkit.inspections.remotedev.analysis.SplitModeAnalysisFlags
import org.jetbrains.idea.devkit.inspections.remotedev.analysis.SplitModeApiRestrictionsService
import org.jetbrains.idea.devkit.module.PluginModuleType
import org.jetbrains.idea.devkit.util.DescriptorUtil
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal object SplitModeInspectionUtil {
  @Nls
  fun buildMixedModuleDependenciesMessage(reasoning: @NlsSafe String): String {
    val baseMessage = DevKitBundle.message("inspection.remote.dev.mixed.dependencies.message")
    val message = if (reasoning.isBlank()) {
      baseMessage
    }
    else {
      "$baseMessage\n\n$reasoning"
    }
    return replaceNewLinesWithHtmlBreaks(message)
  }

  @Nls
  fun buildModuleKindMismatchMessage(
    apiName: @NlsSafe String,
    expectedModuleKind: SplitModeApiRestrictionsService.ModuleKind,
    actualModuleKind: ResolvedModuleKind,
    hint: @Nls String? = null,
  ): String {
    val baseMessage = DevKitBundle.message(
      "inspection.api.usage.restricted.to.module.type.default.message",
      apiName,
      expectedModuleKind.id,
      actualModuleKind.kind.id,
    )
    val nonBlankHint = if (hint.isNullOrBlank()) null else hint
    val reasoningText = actualModuleKind.reasoning
    val reasoningMessage = if (reasoningText.isNotBlank()) {
      DevKitBundle.message("inspection.api.usage.restricted.to.module.type.reasoning.message.suffix", reasoningText)
    }
    else {
      null
    }
    if (nonBlankHint == null && reasoningMessage == null) {
      return replaceNewLinesWithHtmlBreaks(baseMessage)
    }

    val message = buildString {
      append(baseMessage)
      append(".\n\n")
      if (nonBlankHint != null) {
        append(nonBlankHint)
        if (reasoningMessage != null) {
          append("\n\n")
        }
      }
      if (reasoningMessage != null) {
        append(reasoningMessage)
      }
    }
    return replaceNewLinesWithHtmlBreaks(message)
  }

  private fun replaceNewLinesWithHtmlBreaks(message: String): String {
    return message.replace("\n", "<br>")
  }

  fun ensureRestrictionsServiceIsLoaded(restrictionsService: SplitModeApiRestrictionsService): Boolean {
    if (restrictionsService.isLoaded()) {
      return true
    }

    restrictionsService.scheduleLoadRestrictions()
    if (restrictionsService.isLoaded()) {
      return true
    }

    val loadedInTime = runBlockingCancellable {
      withTimeoutOrNull(1.seconds) {
        while (!restrictionsService.isLoaded()) {
          delay(10.milliseconds)
        }
        true
      }
    }
    return loadedInTime == true
  }

  fun isAllowedForSplitModeInspection(file: PsiFile): Boolean {
    val restrictionsService = SplitModeApiRestrictionsService.getInstance()
    if (shouldSuppressForSingleModuleExternalPlugin(file)) {
      return false
    }
    if (!ensureRestrictionsServiceIsLoaded(restrictionsService)) {
      return false
    }
    if (shouldSuppressForPredefinedModuleKind(file, restrictionsService)) {
      return false
    }
    return true
  }

  fun shouldSuppressForPredefinedModuleKind(file: PsiFile, restrictionsService: SplitModeApiRestrictionsService): Boolean {
    if (!SplitModeAnalysisFlags.isSkippingInspectionsForPredefinedModuleKindsEnabled()) {
      return false
    }

    val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return false
    val currentDescriptorFile = file as? XmlFile
    val predefinedModuleKind = if (currentDescriptorFile != null) {
      val currentIdeaPlugin = DescriptorUtil.getIdeaPlugin(currentDescriptorFile)
      restrictionsService.getPredefinedModuleKind(module, currentDescriptorFile, currentIdeaPlugin)
    }
    else {
      val pluginXml = PluginModuleType.getPluginXml(module)
      val ideaPlugin = if (pluginXml == null) null else DescriptorUtil.getIdeaPlugin(pluginXml)
      restrictionsService.getPredefinedModuleKind(module, ideaPlugin = ideaPlugin)
    }

    return predefinedModuleKind != null
  }

  fun shouldSuppressForSingleModuleExternalPlugin(file: PsiFile): Boolean {
    val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return false
    val project = file.project
    return CachedValuesManager.getManager(project).getCachedValue(module) {
      CachedValueProvider.Result.create(
        shouldSuppressForSingleModuleExternalPlugin(module),
        ProjectRootModificationTracker.getInstance(project),
        PsiManager.getInstance(project).modificationTracker.forLanguage(XMLLanguage.INSTANCE),
      )
    }
  }

  internal fun findDependingContentModuleEntriesInFile(contentModuleDescriptor: XmlFile): Sequence<ModuleDescriptor> {
    val moduleVirtualFile = contentModuleDescriptor.virtualFile ?: return emptySequence()
    val moduleName = moduleVirtualFile.nameWithoutExtension
    val psiManager = contentModuleDescriptor.manager
    @Suppress("UNCHECKED_CAST")
    return PluginIdDependenciesIndex.findFilesIncludingContentModule(contentModuleDescriptor.project, moduleVirtualFile).asSequence()
      .flatMap { dependingFile ->
        val psiFile = psiManager.findFile(dependingFile) as? XmlFile ?: return@flatMap emptySequence<PsiElement>()
        val plugin = DescriptorUtil.getIdeaPlugin(psiFile) ?: return@flatMap emptySequence<PsiElement>()
        val modules = plugin.content.flatMap { it.moduleEntry }
        modules.filter { it.name.stringValue == moduleName }.asSequence()
      } as? Sequence<ModuleDescriptor> ?: emptySequence()
  }

  private fun shouldSuppressForSingleModuleExternalPlugin(module: Module): Boolean {
    if (IntelliJProjectUtil.isIntelliJPlatformProject(module.project)) {
      return false
    }

    val pluginXml = PluginModuleType.getPluginXml(module) ?: return false
    if (PluginModuleType.getContentModuleDescriptorXml(module) != null) {
      return false
    }

    val ideaPlugin = DescriptorUtil.getIdeaPlugin(pluginXml) ?: return false
    return ideaPlugin.content.isEmpty() || ideaPlugin.content.all { it.moduleEntry.isEmpty() }
  }
}
