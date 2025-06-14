// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.base.plugin.artifacts

import com.intellij.openapi.application.PathManager
import com.intellij.platform.testFramework.io.ExternalResourcesChecker
import org.jetbrains.kotlin.idea.artifacts.KotlinNativeHostSupportDetector
import org.jetbrains.kotlin.idea.artifacts.KotlinNativePrebuiltDownloader.downloadFile
import org.jetbrains.kotlin.idea.artifacts.KotlinNativePrebuiltDownloader.unpackPrebuildArchive
import org.jetbrains.kotlin.idea.artifacts.KotlinNativeVersion
import org.jetbrains.kotlin.idea.artifacts.NATIVE_PREBUILT_DEV_CDN_URL
import org.jetbrains.kotlin.idea.artifacts.NATIVE_PREBUILT_RELEASE_CDN_URL
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinArtifactsDownloader
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinMavenUtils
import org.jetbrains.kotlin.konan.file.unzipTo
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.TargetSupportException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TestKotlinArtifacts {
    private val kotlinCLibrariesVersion by lazy {
        val libraryNameToGetVersionFrom = "kotlinc_analysis_api.xml"
        val findLibraryVersion = KotlinMavenUtils.findLibraryVersion(libraryNameToGetVersionFrom)
        findLibraryVersion
    }

    private fun getLibraryFile(groupId: String, artifactId: String, libraryFileName: String): File {
        val version = KotlinMavenUtils.findLibraryVersion(libraryFileName)
        return KotlinMavenUtils.findArtifactOrFail(groupId, artifactId, version).toFile()
    }

    private fun getJar(artifactId: String): File =
        downloadKotlinArtifactOrReportUnavailability(artifactId, kotlinCLibrariesVersion)

    private fun getKlib(artifactId: String): File =
        downloadKotlinArtifactOrReportUnavailability(artifactId, kotlinCLibrariesVersion, suffix = ".klib")

    private fun getSourcesJar(artifactId: String, extraSuffix: String = ""): File {
        return downloadKotlinArtifactOrReportUnavailability(artifactId, kotlinCLibrariesVersion, suffix = "$extraSuffix-sources.jar")
            .copyTo(                                       // Some tests hardcode jar names in their test data
                File(PathManager.getCommunityHomePath())   // (KotlinReferenceTypeHintsProviderTestGenerated).
                    .resolve("out")                        // That's why we need to strip version from the jar name
                    .resolve("kotlin-from-sources-deps-renamed")
                    .resolve("$artifactId$extraSuffix-sources.jar"),
                overwrite = true
            )
    }

    @JvmStatic val kotlinAnnotationsJvm: File by lazy { getJar("kotlin-annotations-jvm") }
    @JvmStatic val kotlinCompiler: File by lazy { getJar("kotlin-compiler") }
    @JvmStatic val kotlinDaemon: File by lazy { getJar("kotlin-daemon") }
    @JvmStatic val kotlinReflect: File by lazy { getJar("kotlin-reflect") }
    @JvmStatic val kotlinReflectSources: File by lazy { getSourcesJar("kotlin-reflect") }
    @JvmStatic val kotlinScriptRuntime: File by lazy { getJar("kotlin-script-runtime") }
    @JvmStatic val kotlinScriptingCommon: File by lazy { getJar("kotlin-scripting-common") }
    @JvmStatic val kotlinScriptingCompiler: File by lazy { getJar("kotlin-scripting-compiler") }
    @JvmStatic val kotlinScriptingCompilerImpl: File by lazy { getJar("kotlin-scripting-compiler-impl") }
    @JvmStatic val kotlinScriptingJvm: File by lazy { getJar("kotlin-scripting-jvm") }
    @JvmStatic val kotlinStdlib: File by lazy { getJar("kotlin-stdlib") }
    @JvmStatic val kotlinStdlibCommon: File by lazy {

        val baseDir = File(PathManager.getCommunityHomePath()).resolve("out")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        val artefactName = "kotlin-stdlib-common"
        val libFile = baseDir.resolve("$artefactName.klib")

        if (!libFile.exists()) {
            val archiveFile = baseDir.resolve(artefactName)
            val archiveFilePath = archiveFile.toPath()
            archiveFile.deleteRecursively()
            val stdlibAllJar = downloadKotlinArtifactOrReportUnavailability("kotlin-stdlib", kotlinCLibrariesVersion, suffix = "-all.jar")
            stdlibAllJar.toPath().unzipTo(archiveFilePath)

            val unpackedCommonMain = archiveFilePath.resolve("commonMain").toFile()
            unpackedCommonMain.compressDirectoryToZip(libFile)
        }

        libFile
    }
    @JvmStatic val kotlinStdlibCommonSources: File by lazy { getSourcesJar("kotlin-stdlib", extraSuffix = "-common") }
    @JvmStatic val kotlinStdlibJdk7: File by lazy { getJar("kotlin-stdlib-jdk7") }
    @JvmStatic val kotlinStdlibJdk7Sources: File by lazy { getSourcesJar("kotlin-stdlib-jdk7") }
    @JvmStatic val kotlinStdlibJdk8: File by lazy { getJar("kotlin-stdlib-jdk8") }
    @JvmStatic val kotlinStdlibJdk8Sources: File by lazy { getSourcesJar("kotlin-stdlib-jdk8") }
    @JvmStatic val kotlinStdlibJs: File by lazy { getKlib("kotlin-stdlib-js") }
    // The latest published kotlin-stdlib-js with both .knm and .kjsm roots
    @JvmStatic val kotlinStdlibJsLegacyJar: File by lazy { downloadKotlinArtifactOrReportUnavailability("kotlin-stdlib-js", "1.9.22") }
    @JvmStatic val kotlinDomApiCompat: File by lazy { getKlib("kotlin-dom-api-compat") }
    @JvmStatic val kotlinStdlibWasmJs: File by lazy { getKlib("kotlin-stdlib-wasm-js") }
    @JvmStatic val kotlinStdlibWasmWasi: File by lazy { getKlib("kotlin-stdlib-wasm-wasi") }
    @JvmStatic val kotlinStdlibSources: File by lazy { getSourcesJar("kotlin-stdlib") }
    @JvmStatic val kotlinStdlibLegacy1922: File by lazy { downloadKotlinArtifactOrReportUnavailability("kotlin-stdlib", "1.9.22") }
    // In 1.x, `kotlin-stdlib-common` still contained `.kotlin_metadata` files. 2.x versions of the library contain `.knm` files, since it's
    // now a klib.
    @JvmStatic val kotlinStdlibCommonLegacy1922: File by lazy { downloadKotlinArtifactOrReportUnavailability("kotlin-stdlib-common", "1.9.22") }
    @JvmStatic val kotlinTest: File by lazy { getJar("kotlin-test") }
    @JvmStatic val kotlinTestJs: File by lazy { getKlib("kotlin-test-js") }
    @JvmStatic val kotlinTestJunit: File by lazy { getJar("kotlin-test-junit") }
    @JvmStatic val parcelizeRuntime: File by lazy { getJar("parcelize-compiler-plugin-for-ide") }

    @JvmStatic val trove4j: File by lazy {
        PathManager.getJarForClass(TestKotlinArtifacts::class.java.classLoader.loadClass("gnu.trove.THashMap"))!!.toFile()
    }
    @JvmStatic val jetbrainsAnnotations: File by lazy { getLibraryFile("org.jetbrains", "annotations", "jetbrains_annotations.xml") }
    @JvmStatic val jsr305: File by lazy { getLibraryFile("com.google.code.findbugs", "jsr305", "jsr305.xml") }
    @JvmStatic val junit3: File by lazy { KotlinArtifactsDownloader.downloadMavenArtifact("junit","junit", "3.8.2") ?: error("cannot download junit3") }
    @JvmStatic val kotlinxCoroutines: File by lazy {
        PathManager.getJarForClass(TestKotlinArtifacts::class.java.classLoader.loadClass("kotlinx.coroutines.CoroutineScope"))!!.toFile()
    }
    @JvmStatic val coroutineContext: File by lazy {
        PathManager.getJarForClass(TestKotlinArtifacts::class.java.classLoader.loadClass("kotlin.coroutines.CoroutineContext"))!!.toFile()
    }

    /**
     * @throws org.jetbrains.kotlin.konan.target.TargetSupportException on access from an inappropriate host.
     * See KT-36871, KTIJ-28066.
     */
    @JvmStatic val kotlinStdlibNative: File by lazy { getNativeLib(library = "klib/common/stdlib") }

    @JvmStatic
    val compilerTestDataDir: File by lazy {
        downloadAndUnpack(
            libraryFileName = "kotlinc_kotlin_compiler_cli.xml",
            artifactId = "kotlin-compiler-testdata-for-ide",
            dirName = "kotlinc-testdata-2",
        )
    }

    @JvmStatic
    fun compilerTestData(compilerTestDataPath: String): String {
        return compilerTestDataDir.resolve(compilerTestDataPath).canonicalPath
    }

    @JvmStatic
    val jpsPluginTestDataDir: File by lazy {
        downloadAndUnpack(
            libraryFileName = "kotlinc_kotlin_jps_plugin_tests.xml",
            artifactId = "kotlin-jps-plugin-testdata-for-ide",
            dirName = "kotlinc-jps-testdata",
        )
    }

    @JvmStatic
    val jsIrRuntimeDir: File by lazy {
        downloadKotlinArtifactOrReportUnavailability(
            "js-ir-runtime-for-ide",
            KotlinMavenUtils.findLibraryVersion("kotlinc_kotlin_jps_plugin_tests.xml"),
            suffix = ".klib"
        )
    }

    @JvmStatic
    fun jpsPluginTestData(jpsTestDataPath: String): String {
        return jpsPluginTestDataDir.resolve(jpsTestDataPath).canonicalPath
    }

    private fun downloadAndUnpack(libraryFileName: String, artifactId: String, dirName: String): File {
        val jar = downloadKotlinArtifactOrReportUnavailability(artifactId, KotlinMavenUtils.findLibraryVersion(libraryFileName))
        return LazyZipUnpacker(File(PathManager.getCommunityHomePath()).resolve("out").resolve(dirName)).lazyUnpack(jar)
    }

    @Throws(TargetSupportException::class)
    fun getNativeLib(
        version: String = KotlinNativeVersion.resolvedKotlinNativeVersion,
        platform: String = HostManager.platformName(),
        library: String
    ): File {
        if (!KotlinNativeHostSupportDetector.isNativeHostSupported() && platform == HostManager.platformName())
            throw TargetSupportException("kotlin-native-prebuilt can't be downloaded as it doesn't exist for the host: ${platform}")

        val baseDir = File(PathManager.getCommunityHomePath()).resolve("out")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val prebuilt = "kotlin-native-prebuilt-$platform-$version"
        val archiveName =  if (HostManager.hostIsMingw) "$prebuilt.zip" else "$prebuilt.tar.gz"
        val cdnUrl = if ("dev" in version) NATIVE_PREBUILT_DEV_CDN_URL else NATIVE_PREBUILT_RELEASE_CDN_URL
        val downloadUrl = "$cdnUrl/$version/$platform/$archiveName"
        val downloadOut = "${baseDir.absolutePath}/$archiveName"
        val libPath = "${baseDir.absolutePath}/$prebuilt/$prebuilt/$library"
        val libFile = File(libPath)

        if (!libFile.exists()) {
            val archiveFilePath = Paths.get(downloadOut)
            Files.deleteIfExists(archiveFilePath)
            downloadFile(downloadUrl, archiveFilePath)
            unpackPrebuildArchive(archiveFilePath, Paths.get("$baseDir/$prebuilt"))
            Files.deleteIfExists(archiveFilePath)
        }
        return if (libFile.exists()) libFile else
            throw IOException("Library doesn't exist: $libPath")
    }

    private fun File.compressDirectoryToZip(targetZipFile: File) {
        targetZipFile.parentFile.mkdirs()
        targetZipFile.createNewFile()

        val sourceFolder = this

        ZipOutputStream(targetZipFile.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            sourceFolder
                .walkTopDown()
                .filter { file -> !file.isDirectory }
                .forEach { file ->
                    val suffix = if (file.isDirectory) "/" else ""
                    val entry = ZipEntry(file.relativeTo(sourceFolder).invariantSeparatorsPath + suffix)
                    zip.putNextEntry(entry)
                    if (!file.isDirectory) {
                        file.inputStream().buffered().use { it.copyTo(zip) }
                    }
                    zip.closeEntry()
                }
            zip.flush()
        }
    }
}

/**
 * See [org.jetbrains.kotlin.idea.test.ExpectedPluginModeProvider.setUpWithKotlinPlugin] where we unwrap the exception if it happens during class initialization
 */
@JvmOverloads
fun downloadKotlinArtifactOrReportUnavailability(artifactId: String, version: String, suffix: String = ".jar"): File =
    KotlinArtifactsDownloader.downloadArtifactForIdeFromSources(artifactId, version, suffix)
        ?: ExternalResourcesChecker.reportUnavailability<Nothing>(
            KotlinArtifactsDownloader::downloadArtifactForIdeFromSources.name + ": $artifactId-$version$suffix",
            null
        )
