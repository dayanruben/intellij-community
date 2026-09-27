// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.impl.productInfo

import com.intellij.platform.buildData.productInfo.CustomCommandLaunchData
import com.intellij.platform.buildData.productInfo.CustomProperty
import com.intellij.platform.buildData.productInfo.ProductFlavorData
import com.intellij.platform.buildData.productInfo.ProductInfoData
import com.intellij.platform.buildData.productInfo.ProductInfoLaunchData
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.intellij.build.JvmArchitecture
import org.jetbrains.intellij.build.OsFamily
import org.jetbrains.intellij.build.impl.PlatformJarNames.PLATFORM_CORE_NIO_FS
import org.jetbrains.intellij.build.impl.moduleRepository.MODULE_DESCRIPTORS_COMPACT_PATH
import org.jetbrains.intellij.build.impl.openedPackagesArguments
import org.jetbrains.intellij.build.impl.openedPackagesFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val MAJOR_RELEASE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

/**
 * The four files of [model] for [os] and [arch], keyed by their distribution-relative path: `build.txt`,
 * `bin/idea.properties`, the vmoptions file and `bin/product-info.json`.
 *
 * [communityHome] holds `OpenedPackages.txt` and the base `idea.properties` of an IDE. [languageServerIdeaProperties] is
 * the base `idea.properties` of a language server. The Go renderer in `community/build/content-module-packer/product-files`
 * implements the same function, and the two must agree byte for byte.
 */
@ApiStatus.Internal
fun renderProductLaunchFiles(
  model: ProductLaunchModel,
  os: OsFamily,
  arch: JvmArchitecture,
  communityHome: Path,
  languageServerIdeaProperties: Path,
): Map<String, String> {
  val openedPackages = openedPackagesArguments(openedPackagesFile(communityHome), os)
  val ideaPropertiesBase = Files.readString(
    if (model.ideaProperties.languageServerBase) languageServerIdeaProperties else communityHome.resolve("bin/idea.properties")
  )
  val separator = if (os == OsFamily.WINDOWS) "\r\n" else "\n"
  return linkedMapOf(
    "build.txt" to "${model.productCode}-${model.buildNumber}",
    "bin/idea.properties" to renderIdeaProperties(model.ideaProperties, ideaPropertiesBase),
    "bin/${vmOptionsFileName(os, model.baseFileName, model.languageServer)}" to model.vmOptions.getValue(os.osName).joinToString(separator, postfix = separator),
    "bin/$PRODUCT_INFO_FILE_NAME" to renderProductInfo(model, os, arch, openedPackages),
  )
}

private fun renderIdeaProperties(ideaProperties: ProductLaunchIdeaProperties, base: String): String {
  val text = StringBuilder(base)
  for (addition in ideaProperties.additions) {
    text.append('\n').append(addition)
  }
  return text.toString().replace("@@settings_dir@@", ideaProperties.settingsDir) + ideaProperties.suffix
}

private fun renderProductInfo(model: ProductLaunchModel, os: OsFamily, arch: JvmArchitecture, openedPackages: List<String>): String {
  val toRoot = if (os == OsFamily.MACOS && !model.languageServer) "../" else ""
  val javaExecutablePath = when (os) {
    OsFamily.MACOS -> "${toRoot}jbr/Contents/Home/bin/java"
    OsFamily.LINUX -> "jbr/bin/java"
    OsFamily.WINDOWS -> "jbr/bin/java.exe"
  }
  val launch = ProductInfoLaunchData.create(
    os = os.osName,
    arch = arch.dirName,
    launcherPath = when (os) {
      OsFamily.MACOS -> "${if (model.languageServer) "bin" else "../MacOS"}/${model.baseFileName}"
      OsFamily.LINUX -> "bin/${model.baseFileName}"
      OsFamily.WINDOWS -> "bin/${add64IfNeeded(model.baseFileName, model.languageServer)}.exe"
    },
    javaExecutablePath = javaExecutablePath,
    vmOptionsFilePath = vmOptionsFilePath(os, model.baseFileName, model.languageServer),
    bootClassPathJarNames = model.launch.bootClassPathJarNames,
    additionalJvmArguments = renderAdditionalJvmArguments(model.launch.jvmArguments, os, arch, openedPackages),
    mainClass = model.launch.mainClass,
    stdioRedirectArg = model.launch.stdioRedirectArg,
    startupWmClass = if (os == OsFamily.LINUX) model.launch.linuxStartupWmClass else null,
    customCommands = model.customCommands.map { command ->
      CustomCommandLaunchData(
        commands = command.commands,
        vmOptionsFilePath = command.vmOptionsFilePath[os.osName],
        bootClassPathJarNames = command.bootClassPathJarNames,
        additionalJvmArguments = buildList {
          command.jvmArguments?.let { addAll(renderAdditionalJvmArguments(it, os, arch, openedPackages, isQodana = command.qodana)) }
          if (os == OsFamily.MACOS) {
            addAll(command.macJvmArguments)
          }
          addAll(command.extraJvmArguments)
        },
        mainClass = command.mainClass,
        envVarBaseName = command.envVarBaseName,
        dataDirectoryName = command.dataDirectoryName,
      )
    },
  )
  val json = ProductInfoData.create(
    name = model.productName,
    version = model.version,
    versionSuffix = model.versionSuffix,
    buildNumber = model.buildNumber,
    productCode = model.productCode,
    envVarBaseName = model.envVarBaseName,
    dataDirectoryName = model.dataDirectoryName,
    svgIconPath = if (model.svgIcon) "${toRoot}bin/${model.baseFileName}.svg" else null,
    productVendor = model.productVendor,
    majorVersionReleaseDate = LocalDate.parse(model.majorVersionReleaseDate, MAJOR_RELEASE_DATE_FORMAT),
    minRequiredJavaVersion = model.minRequiredJavaVersion,
    launch = listOf(launch),
    customProperties = model.customProperties.map { CustomProperty(it.key, it.value) },
    bundledPlugins = emptyList(),
    modules = emptyList(),
    fileExtensions = emptyList(),
    flavors = (if (model.jbr17) listOf(ProductFlavorData("jbr17")) else emptyList()) + model.flavors.map(::ProductFlavorData),
    layout = emptyList(),
  )
  return jsonEncoder.encodeToString(ProductInfoData.serializer(), json)
}

/**
 * The JVM arguments of [jvm] for [os] and [arch], as `BuildContext.getAdditionalJvmArguments` states them.
 *
 * [openedPackages] are the `--add-opens` lines of [os]. [isScript] quotes the arguments that hold a path macro.
 * [isPortableDist] drops `/Contents` from the macOS macro. [isQodana] leaves the multi-routing file system out.
 */
@ApiStatus.Internal
fun renderAdditionalJvmArguments(
  jvm: ProductJvmArguments,
  os: OsFamily,
  arch: JvmArchitecture,
  openedPackages: List<String>,
  isScript: Boolean = false,
  isPortableDist: Boolean = false,
  isQodana: Boolean = false,
): List<String> {
  fun String.quoteIfNeeded(): String = if (isScript) "\"$this\"" else this

  val result = ArrayList<String>()
  val macroName = when (os) {
    OsFamily.WINDOWS -> "%IDE_HOME%"
    OsFamily.MACOS -> $$"$APP_PACKAGE$${if (isPortableDist) "" else "/Contents"}"
    OsFamily.LINUX -> $$"$IDE_HOME"
  }

  val bootClassPathJarNames = jvm.xBootClassPathJarNames + if (!isQodana && jvm.multiRoutingFileSystem) listOf(PLATFORM_CORE_NIO_FS) else emptyList()
  if (bootClassPathJarNames.isNotEmpty()) {
    val (pathSeparator, dirSeparator) = if (os == OsFamily.WINDOWS) ";" to "\\" else ":" to "/"
    val bootClassPath = bootClassPathJarNames.joinToString(pathSeparator) { arrayOf(macroName, "lib", it).joinToString(dirSeparator) }
    result.add("-Xbootclasspath/a:$bootClassPath".quoteIfNeeded())
  }

  if (jvm.cdsArchiveFileName != null) {
    val cacheDir = if (os == OsFamily.WINDOWS) "%IDE_CACHE_DIR%\\" else $$"$IDE_CACHE_DIR/"
    result.add("-XX:SharedArchiveFile=$cacheDir${jvm.cdsArchiveFileName}")
    result.add("-XX:+AutoCreateSharedArchive")
  }
  else {
    jvm.classLoader?.let {
      result.add("-Djava.system.class.loader=$it")
    }
  }

  result.add("-Didea.vendor.name=${jvm.vendorName}")
  result.add("-Didea.paths.selector=${jvm.pathsSelector}")

  if (jvm.jna) {
    result.add("-Djna.boot.library.path=$macroName/lib/jna/${arch.dirName}".quoteIfNeeded())
    result.add("-Djna.nosys=true")
    result.add("-Djna.noclasspath=true")
  }
  if (jvm.pty4j) {
    result.add("-Dpty4j.preferred.native.folder=$macroName/lib/pty4j".quoteIfNeeded())
  }
  result.add("-Dio.netty.allocator.type=pooled")
  if (jvm.skiko) {
    result.add("-Dskiko.library.path=$macroName/lib/skiko-awt-runtime-all".quoteIfNeeded())
  }

  if (jvm.runtimeModuleRepository) {
    result.add("-Dintellij.platform.runtime.repository.path=$macroName/$MODULE_DESCRIPTORS_COMPACT_PATH".quoteIfNeeded())
  }
  if (jvm.rootModule != null) {
    result.add("-Dintellij.platform.root.module=${jvm.rootModule}")
    result.add("-Dintellij.platform.product.mode=${jvm.productMode}")
  }

  jvm.platformPrefix?.let {
    result.add("-Didea.platform.prefix=$it")
  }

  result.addAll(jvm.additional)

  if (jvm.splash) {
    @Suppress("SpellCheckingInspection", "RedundantSuppression")
    result.add("-Dsplash=true")
  }

  // https://youtrack.jetbrains.com/issue/IDEA-269280
  result.add("-Daether.connector.resumeDownloads=false")
  result.add("-Dcompose.swing.render.on.graphics=true")

  if (jvm.nativeAccess) {
    result.add("--enable-native-access=ALL-UNNAMED")
  }

  result.addAll(openedPackages)
  return result
}
