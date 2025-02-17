// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.psi.impl

import com.intellij.codeInsight.JavaModuleSystemEx
import com.intellij.codeInsight.JavaModuleSystemEx.ErrorWithFixes
import com.intellij.codeInsight.daemon.JavaErrorBundle
import com.intellij.codeInsight.daemon.QuickFixBundle
import com.intellij.codeInsight.daemon.impl.analysis.JavaModuleGraphUtil
import com.intellij.codeInsight.daemon.impl.quickfix.AddExportsDirectiveFix
import com.intellij.codeInsight.daemon.impl.quickfix.AddRequiresDirectiveFix
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.QuickFixFactory
import com.intellij.codeInspection.util.IntentionName
import com.intellij.java.JavaBundle
import com.intellij.java.codeserver.core.JavaPsiModuleUtil
import com.intellij.java.codeserver.core.JavaPsiModuleUtil.findDescriptorByElement
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
import com.intellij.modcommand.Presentation
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.pom.java.JavaFeature
import com.intellij.pom.java.LanguageLevel
import com.intellij.psi.*
import com.intellij.psi.JavaModuleSystem.*
import com.intellij.psi.impl.light.LightJavaModule
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.JavaMultiReleaseUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.util.indexing.DumbModeAccessType
import org.jetbrains.annotations.NonNls

/**
 * Checks package accessibility according to JLS 7 "Packages and Modules".
 *
 * @see <a href="https://docs.oracle.com/javase/specs/jls/se9/html/jls-7.html">JLS 7 "Packages and Modules"</a>
 * @see <a href="http://openjdk.org/jeps/261">JEP 261: Module System</a>
 */
internal class JavaPlatformModuleSystem : JavaModuleSystemEx {
  override fun getName(): String = JavaBundle.message("java.platform.module.system.name")

  override fun isAccessible(targetPackageName: String, targetFile: PsiFile?, place: PsiElement): Boolean {
    return getProblem(targetPackageName, targetFile, place, true) { (current, target) -> isExported(current, target) } == null
  }

  override fun checkAccess(targetPackageName: String, targetFile: PsiFile?, place: PsiElement): ErrorWithFixes? {
    return getProblem(targetPackageName, targetFile, place, false) { (current, target) ->
      val currentModule = current.module ?: return@getProblem false
      val targetModule = target.module ?: return@getProblem false
      return@getProblem JavaPsiModuleUtil.reads(currentModule, targetModule)
    }
  }

  override fun isAccessible(targetModule: PsiJavaModule, place: PsiElement): Boolean {
    return getProblem(targetModule, place, true) { (current, target) ->
      if (current.module == null || target.module == null) return@getProblem false
      return@getProblem JavaPsiModuleUtil.reads(current.module, target.module!!)
    } == null
  }

  override fun checkAccess(targetModule: PsiJavaModule, place: PsiElement): ErrorWithFixes? {
    return getProblem(targetModule, place, false) { (current, target) ->
      if (current.module == null || target.module == null) return@getProblem false
      return@getProblem JavaPsiModuleUtil.reads(current.module, target.module!!)
    }
  }

  private fun isExported(current: CurrentModuleInfo, target: TargetModuleInfo): Boolean {
    val targetModule = target.module ?: return false
    if (!targetModule.isPhysical || JavaModuleGraphUtil.exports(targetModule, target.packageName, current.module)) return true
    val currentJpsModule = current.jpsModule ?: return false
    return inAddedExports(currentJpsModule, targetModule.name, target.packageName, current.name)
  }

  private fun getProblem(targetModule: PsiJavaModule, place: PsiElement, quick: Boolean,
                         isAccessible: (ModuleAccessInfo) -> Boolean): ErrorWithFixes? {
    val target = TargetModuleInfo(targetModule, "")
    return checkModuleAccess(target, place, quick, isAccessible)
  }

  private fun getProblem(targetPackageName: String, targetFile: PsiFile?, place: PsiElement, quick: Boolean,
                         isAccessible: (ModuleAccessInfo) -> Boolean): ErrorWithFixes? {
    val originalTargetFile = targetFile?.originalFile
    val useFile = place.containingFile?.originalFile ?: return null
    if (!PsiUtil.isAvailable(JavaFeature.MODULES, useFile)) return null

    val useVFile = useFile.virtualFile
    val index = ProjectFileIndex.getInstance(useFile.project)
    if (useVFile != null && index.isInLibrarySource(useVFile)) return null
    if (originalTargetFile != null && originalTargetFile.isPhysical) {
      val target = TargetModuleInfo(originalTargetFile, targetPackageName)
      return checkAccess(target, useFile, quick, isAccessible)
    }
    if (useVFile == null) return null

    val target = JavaPsiFacade.getInstance(useFile.project).findPackage(targetPackageName) ?: return null
    val module = index.getModuleForFile(useVFile) ?: return null
    val test = index.isInTestSourceContent(useVFile)
    val moduleScope = module.getModuleWithDependenciesAndLibrariesScope(test)
    val dirs = target.getDirectories(moduleScope)
    if (dirs.isEmpty()) {
      return if (target.getFiles(moduleScope).isEmpty()) {
        ErrorWithFixes(JavaErrorBundle.message("package.not.found", target.qualifiedName))
      }
      else {
        null
      }
    }

    val error = checkAccess(TargetModuleInfo(dirs[0], target.qualifiedName), useFile, quick, isAccessible) ?: return null
    return when {
      dirs.size == 1 -> error
      dirs.asSequence().drop(1).any { checkAccess(TargetModuleInfo(it, target.qualifiedName), useFile, true, isAccessible) == null } -> null
      else -> error
    }
  }

  private val ERR = ErrorWithFixes("-")

  private fun checkModuleAccess(
    target: TargetModuleInfo, place: PsiElement, quick: Boolean,
    isAccessible: (ModuleAccessInfo) -> Boolean,
  ): ErrorWithFixes? {
    val useFile = place.containingFile?.originalFile ?: return null
    val useModule = findDescriptorByElement(useFile).let { if (it is LightJavaModule) null else it }
    val current = CurrentModuleInfo(useModule, place)

    val targetModule = target.module
    if (targetModule != null) {
      if (targetModule == current.module) {
        return null
      }

      val currentJpsModule = current.jpsModule
      if (current.module == null) {
        var origin = targetModule.containingFile?.virtualFile
        if (origin == null && targetModule is LightJavaModule) origin = targetModule.rootVirtualFile
        if (origin == null || currentJpsModule == null) return null

        if (ModuleRootManager.getInstance(currentJpsModule).fileIndex.getOrderEntryForFile(origin) !is JdkOrderEntry) {
          val searchScope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(currentJpsModule)
          if (searchScope.contains(origin)) return null
          return if (quick) ERR
          else if (place is PsiJavaModuleReferenceElement) {
            val reference: PsiJavaModuleReference = place.reference ?: return null
            val registrar: MutableList<IntentionAction> = ArrayList()
            QuickFixFactory.getInstance().registerOrderEntryFixes(reference, registrar)
            ErrorWithFixes("-", registrar)
          }
          else null
        }

        if (!accessibleFromJdkModules(current, target, place, isAccessible) &&
            !inAddedModules(currentJpsModule, targetModule.name)) {
          return if (quick) ERR
          else ErrorWithFixes(JavaErrorBundle.message("module.not.in.graph", targetModule.name),
                              listOf(AddModulesOptionFix(currentJpsModule, targetModule.name).asIntention()))
        }
      }

      if (current.module != null &&
          targetModule.name != PsiJavaModule.JAVA_BASE &&
          !isAccessible(ModuleAccessInfo(current, target)) &&
          !inAddedReads(current.module, targetModule)) {
        return when {
          quick -> ERR
          PsiNameHelper.isValidModuleName(targetModule.name, current.module) -> ErrorWithFixes(JavaErrorBundle.message("module.does.not.read", targetModule.name, current.name),
                                                                                               listOf(AddRequiresDirectiveFix(current.module, targetModule.name).asIntention()))
          else -> ErrorWithFixes(JavaErrorBundle.message("module.bad.name", targetModule.name))
        }
      }
    }
    else if (current.module != null) {
      val autoModule = TargetModuleInfo(detectAutomaticModule(target), target.packageName)
      if (autoModule.module != null &&
          !isAccessible(ModuleAccessInfo(current, autoModule)) &&
          !inAddedReads(current.module, null) &&
          !inSameMultiReleaseModule(current, target)) {
        return if (quick) ERR else ErrorWithFixes(JavaErrorBundle.message("module.access.to.unnamed", target.packageName, current.name))
      }
    }

    return null
  }

  private fun checkAccess(target: TargetModuleInfo, place: PsiFileSystemItem, quick: Boolean,
                          isAccessible: (ModuleAccessInfo) -> Boolean): ErrorWithFixes? {
    val useModule = findDescriptorByElement(place).let { if (it is LightJavaModule) null else it }
    val current = CurrentModuleInfo(useModule, place)

    val targetModule = target.module
    if (targetModule != null) {
      if (targetModule == current.module) {
        return null
      }

      val currentJpsModule = current.jpsModule
      if (current.module == null) {
        val origin = targetModule.containingFile?.virtualFile
        if (origin == null || currentJpsModule == null ||
            ModuleRootManager.getInstance(currentJpsModule).fileIndex.getOrderEntryForFile(origin) !is JdkOrderEntry) {
          return null  // a target is not on the mandatory module path
        }

        if (!accessibleFromJdkModules(current, target, place, isAccessible) &&
            !inAddedModules(currentJpsModule, targetModule.name) &&
            !hasUpgrade(currentJpsModule, targetModule.name, target.packageName, place)) {
          return if (quick) ERR
          else ErrorWithFixes(
            JavaErrorBundle.message("module.access.not.in.graph", target.packageName, targetModule.name),
            listOf(AddModulesOptionFix(currentJpsModule, targetModule.name).asIntention()))
        }
      }

      if (targetModule !is LightJavaModule &&
          !JavaModuleGraphUtil.exports(targetModule, target.packageName, current.module) &&
          (currentJpsModule == null || !inAddedExports(currentJpsModule, targetModule.name, target.packageName, current.name)) &&
          (currentJpsModule == null || !isPatchedModule(targetModule.name, currentJpsModule, place))) {
        if (quick) return ERR
        val fixes = when {
          target.packageName.isEmpty() -> emptyList()
          targetModule is PsiCompiledElement && currentJpsModule != null ->
            listOf(AddExportsOptionFix(currentJpsModule, targetModule.name, target.packageName, current.name).asIntention())
          targetModule !is PsiCompiledElement && current.module != null ->
            listOf(AddExportsDirectiveFix(targetModule, target.packageName, current.name).asIntention())
          else -> emptyList()
        }
        return when (current.module) {
          null -> ErrorWithFixes(JavaErrorBundle.message("module.access.from.unnamed", target.packageName, targetModule.name), fixes)
          else -> ErrorWithFixes(JavaErrorBundle.message("module.access.from.named", target.packageName, targetModule.name, current.name), fixes)
        }
      }

      if (current.module != null &&
          targetModule.name != PsiJavaModule.JAVA_BASE &&
          !isAccessible(ModuleAccessInfo(current, target)) &&
          !inAddedReads(current.module, targetModule)) {
        return when {
          quick -> ERR
          PsiNameHelper.isValidModuleName(targetModule.name, current.module) -> ErrorWithFixes(
            JavaErrorBundle.message("module.access.does.not.read", target.packageName, targetModule.name, current.name),
            listOf(AddRequiresDirectiveFix(current.module, targetModule.name).asIntention()))
          else -> ErrorWithFixes(JavaErrorBundle.message("module.access.bad.name", target.packageName, targetModule.name))
        }
      }
    }
    else if (current.module != null) {
      val autoModule = TargetModuleInfo(detectAutomaticModule(target), target.packageName)
      if (autoModule.module == null) {
        return if (quick) ERR else ErrorWithFixes(JavaErrorBundle.message("module.access.to.unnamed", target.packageName, current.name))
      }
      else if (!isAccessible(ModuleAccessInfo(current, autoModule)) &&
               !inAddedReads(current.module, null) &&
               !inSameMultiReleaseModule(current, target)) {
        return if (quick) ERR else ErrorWithFixes(JavaErrorBundle.message("module.access.to.unnamed", target.packageName, current.name))
      }
    }

    return null
  }

  private fun accessibleFromJdkModules(
    current: CurrentModuleInfo,
    target: TargetModuleInfo,
    place: PsiElement,
    isAccessible: (ModuleAccessInfo) -> Boolean,
  ): Boolean {
    val jpsModule = current.jpsModule ?: return false
    val targetModule = target.module ?: return false
    if(targetModule.name == PsiJavaModule.JAVA_BASE) return true

    if(!isJdkModule(jpsModule, targetModule)) return false
    val languageLevel = PsiUtil.getLanguageLevel(place)
    // https://bugs.openjdk.org/browse/JDK-8197532
    val jdkModulePred: (PsiJavaModule) -> Boolean = if (languageLevel >= LanguageLevel.JDK_11) {
      { module -> module.exports.any { e -> e.moduleNames.isEmpty() } }
    }
    else {
      val javaSE = DumbModeAccessType.RELIABLE_DATA_ONLY.ignoreDumbMode<PsiJavaModule, Throwable> {
        JavaPsiFacade.getInstance(place.project).findModule("java.se", jpsModule.moduleWithLibrariesScope)
      }

      if(javaSE != null) {
        { module ->
          (!module.name.startsWith("java.") && module.exports.any { e -> e.moduleNames.isEmpty() }) ||
          isAccessible(ModuleAccessInfo(CurrentModuleInfo(javaSE, current.name) { jpsModule }, target))
        }
      } else {
        {_ -> true}
      }
    }
    val noIncubatorPred: (PsiJavaModule) -> Boolean = {module -> !module.doNotResolveByDefault()}
    return jdkModulePred(targetModule) && noIncubatorPred(targetModule)
  }

  private fun isJdkModule(jpsModule: Module, psiModule: PsiJavaModule): Boolean {
    val sdkHomePath = ModuleRootManager.getInstance(jpsModule).getSdk()?.homePath?.replace('\\', '/')
    val moduleFilePath = psiModule.containingFile?.virtualFile?.path?.replace('\\', '/')
    if (sdkHomePath != null && moduleFilePath != null) {
      return moduleFilePath.startsWith("$sdkHomePath!") ||
             moduleFilePath.startsWith(if(sdkHomePath.last() == '/') sdkHomePath else "$sdkHomePath/")
    } else {
      return psiModule.name.startsWith("java.") ||
             psiModule.name.startsWith("jdk.")
    }
  }

  private fun inSameMultiReleaseModule(current: ModuleInfo, target: ModuleInfo): Boolean {
    val placeModule = current.jpsModule ?: return false
    val targetModule = target.jpsModule ?: return false
    return JavaMultiReleaseUtil.areMainAndAdditionalMultiReleaseModules(targetModule, placeModule)
  }

  private fun detectAutomaticModule(current: ModuleInfo): PsiJavaModule? {
    val module = current.jpsModule ?: return null
    return JavaPsiFacade.getInstance(module.project)
      .findModule(LightJavaModule.moduleName(module.name),
                  GlobalSearchScope.moduleScope(module))
  }

  private fun hasUpgrade(module: Module, targetName: String, packageName: String, place: PsiFileSystemItem): Boolean {
    if (PsiJavaModule.UPGRADEABLE.contains(targetName)) {
      val target = JavaPsiFacade.getInstance(module.project).findPackage(packageName)
      if (target != null) {
        val useVFile = place.virtualFile
        if (useVFile != null) {
          val index = ModuleRootManager.getInstance(module).fileIndex
          val test = index.isInTestSourceContent(useVFile)
          val dirs = target.getDirectories(module.getModuleWithDependenciesAndLibrariesScope(test))
          return dirs.any { index.getOrderEntryForFile(it.virtualFile) !is JdkOrderEntry }
        }
      }
    }

    return false
  }

  private fun isPatchedModule(targetModuleName: String, module: Module, place: PsiFileSystemItem): Boolean {
    val virtualFile = place.virtualFile ?: return false
    val rootForFile = ProjectRootManager.getInstance(place.project).fileIndex.getSourceRootForFile(virtualFile) ?: return false
    return JavaCompilerConfigurationProxy.isPatchedModuleRoot(targetModuleName, module, rootForFile)
  }

  private fun inAddedExports(module: Module, targetName: String, packageName: String, useName: String): Boolean {
    val options = JavaCompilerConfigurationProxy.getAdditionalOptions(module.project, module)
    if (options.isEmpty()) return false
    val prefix = "${targetName}/${packageName}="
    return JavaCompilerConfigurationProxy.optionValues(options, ADD_EXPORTS_OPTION)
      .filter { it.startsWith(prefix) }
      .map { it.substring(prefix.length) }
      .flatMap { it.splitToSequence(",") }
      .any { it == useName }
  }

  private fun inAddedModules(module: Module, moduleName: String): Boolean {
    val options = JavaCompilerConfigurationProxy.getAdditionalOptions(module.project, module)
    return JavaCompilerConfigurationProxy.optionValues(options, ADD_MODULES_OPTION)
      .flatMap { it.splitToSequence(",") }
      .any { it == moduleName || it == ALL_SYSTEM || it == ALL_MODULE_PATH }
  }

  private fun inAddedReads(fromJavaModule: PsiJavaModule, toJavaModule: PsiJavaModule?): Boolean {
    val fromModule = ModuleUtilCore.findModuleForPsiElement(fromJavaModule) ?: return false
    val options = JavaCompilerConfigurationProxy.getAdditionalOptions(fromModule.project, fromModule)
    return JavaCompilerConfigurationProxy.optionValues(options, ADD_READS_OPTION)
      .flatMap { it.splitToSequence(",") }
      .any {
        val (optFromModuleName, optToModuleName) = it.split("=").apply { it.first() to it.last() }
        fromJavaModule.name == optFromModuleName &&
        (toJavaModule?.name == optToModuleName || (optToModuleName == ALL_UNNAMED && isUnnamedModule(toJavaModule)))
      }
  }

  private fun isUnnamedModule(module: PsiJavaModule?) = module == null || module is LightJavaModule

  private abstract class CompilerOptionFix(private val module: Module) : ModCommandAction {
    @NonNls
    override fun getFamilyName() = "Fix compiler option" // not visible

    override fun getPresentation(context: ActionContext): Presentation? {
      if (module.isDisposed) return null
      return Presentation.of(getText())
    }

    override fun perform(context: ActionContext): ModCommand {
      return ModCommand.updateOptionList(context.file, "JavaCompilerConfiguration.additionalOptions", ::update)
    }

    protected abstract fun update(options: MutableList<String>)

    @IntentionName
    protected abstract fun getText(): String
  }

  private class AddExportsOptionFix(module: Module,
                                    targetName: String,
                                    packageName: String,
                                    private val useName: String) : CompilerOptionFix(module) {
    private val qualifier = "${targetName}/${packageName}"

    override fun getText() = QuickFixBundle.message("add.compiler.option.fix.name", "${ADD_EXPORTS_OPTION} ${qualifier}=${useName}")

    override fun update(options: MutableList<String>) {
      var idx = -1
      var candidate = -1
      var offset = 0
      for ((i, option) in options.withIndex()) {
        if (option.startsWith(ADD_EXPORTS_OPTION)) {
          if (option.length == ADD_EXPORTS_OPTION.length) {
            candidate = i + 1; offset = 0
          }
          else if (option[ADD_EXPORTS_OPTION.length] == '=') {
            candidate = i; offset = ADD_EXPORTS_OPTION.length + 1
          }
        }
        if (i == candidate && option.startsWith(qualifier, offset)) {
          val qualifierEnd = qualifier.length + offset
          if (option.length == qualifierEnd || option[qualifierEnd] == '=') {
            idx = i
          }
        }
      }
      when (idx) {
        -1 -> options += listOf(ADD_EXPORTS_OPTION, "${qualifier}=${useName}")
        else -> options[idx] = "${options[idx].trimEnd(',')},${useName}"
      }
    }
  }

  private class AddModulesOptionFix(module: Module, private val moduleName: String) : CompilerOptionFix(module) {
    override fun getText() = QuickFixBundle.message("add.compiler.option.fix.name", "${ADD_MODULES_OPTION} ${moduleName}")

    override fun update(options: MutableList<String>) {
      var idx = -1
      for ((i, option) in options.withIndex()) {
        if (option.startsWith(ADD_MODULES_OPTION)) {
          if (option.length == ADD_MODULES_OPTION.length) idx = i + 1
          else if (option[ADD_MODULES_OPTION.length] == '=') idx = i
        }
      }
      when (idx) {
        -1 -> options += listOf(ADD_MODULES_OPTION, moduleName)
        options.size -> options += moduleName
        else -> {
          val value = options[idx]
          options[idx] = if (value.endsWith('=') || value.endsWith(',')) value + moduleName else "${value},${moduleName}"
        }
      }
    }

  }

  /**
   * Represents the access details between the current module and the target module.
   *
   * @property current The current module.
   * @property target The target module.
   */
  private data class ModuleAccessInfo(val current: CurrentModuleInfo, val target: TargetModuleInfo)

  private interface ModuleInfo {
    val module: PsiJavaModule?
    val jpsModule: Module?
  }

  /**
   * Represents the details of a current module.
   *
   * Note: "name" is not always possible to get from "module".
   *       For example, "module" can be "java.se", but the name is from the original module.
   *
   * @property module The PsiJavaModule instance representing the module.
   * @property name original module name
   * @property jpsModule JPS module initialization.
   */
  private class CurrentModuleInfo(override val module: PsiJavaModule?, val name: String, jps: () -> Module? = { null }) : ModuleInfo {
    constructor(use: PsiJavaModule?, element: PsiElement) : this(use, use?.name ?: ALL_UNNAMED, {
      ModuleUtilCore.findModuleForPsiElement(element)
    })

    override val jpsModule: Module? by lazy { jps() }
  }

  private class TargetModuleInfo(element: PsiElement?, val packageName: String) : ModuleInfo {
    override val jpsModule: Module? by lazy {
      if (element == null) return@lazy null
      ModuleUtilCore.findModuleForPsiElement(element)
    }
    override val module: PsiJavaModule? by lazy {
      findDescriptorByElement(element)
    }
  }
}