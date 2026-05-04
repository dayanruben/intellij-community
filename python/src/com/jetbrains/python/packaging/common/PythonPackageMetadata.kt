// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.packaging.common

import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.python.community.execService.Args
import com.intellij.python.community.execService.ExecService
import com.jetbrains.python.errorProcessing.PyResult
import com.jetbrains.python.packaging.PyPackageName
import com.jetbrains.python.sdk.executeHelper
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.annotations.ApiStatus

/**
 * Core Metadata (PEP 643) for an installed distribution, read from `<dist-info>/METADATA` by the
 * `read_package_metadata.py` helper. Field names mirror the Core Metadata header names so the
 * JSON the helper emits maps directly onto this class via `kotlinx.serialization`.
 *
 * Cached per package by [com.jetbrains.python.packaging.management.PythonPackageManager];
 * invalidated when the installed version changes.
 */
@ApiStatus.Internal
@Serializable
data class PythonPackageMetadata(
  val name: @NlsSafe String = "",
  val version: @NlsSafe String = "",
  val summary: @NlsSafe String = "",
  val description: @NlsSafe String = "",
  @SerialName("description_content_type") val descriptionContentType: @NlsSafe String = "",
  @SerialName("home_page") val homePage: @NlsSafe String = "",
  val author: @NlsSafe String = "",
  @SerialName("author_email") val authorEmail: @NlsSafe String = "",
  val license: @NlsSafe String = "",
  @SerialName("requires_python") val requiresPython: @NlsSafe String = "",
  val keywords: @NlsSafe String = "",
  @SerialName("project_urls") val projectUrls: Map<@NlsSafe String, @NlsSafe String> = emptyMap(),
  @SerialName("requires_dist") val requiresDist: List<@NlsSafe String> = emptyList(),
  val classifiers: List<@NlsSafe String> = emptyList(),
)

/**
 * Parses the JSON emitted by `read_package_metadata.py` into a PEP 503-normalized
 * [PyPackageName] → [PythonPackageMetadata] map. The helper already emits
 * normalized string keys, so wrapping them with [PyPackageName.from] is idempotent — the
 * value-class wrapper just lifts the "key is normalized" contract into the type system.
 *
 * Surfaces parse failures as a localized error so the caller can decide whether to log / retry;
 * the helper always emits at least `{}` for a healthy run, so blank or malformed output
 * indicates something went wrong (process crash, missing helper, …) rather than "no installed
 * packages".
 */
internal object PythonPackageMetadataParser {
  private val JSON: Json = Json { ignoreUnknownKeys = true }

  /** File name of the helper script under `community/python/helpers/`. */
  const val HELPER_SCRIPT: String = "read_package_metadata.py"

  internal fun parse(output: String): PyResult<Map<PyPackageName, PythonPackageMetadata>> = try {
    val raw = JSON.decodeFromString<Map<String, PythonPackageMetadata>>(output)
    PyResult.success(raw.mapKeys { PyPackageName.from(it.key) })
  }
  catch (e: SerializationException) {
    PyResult.localizedError(e.localizedMessage)
  }
}

/**
 * Runs the [PythonPackageMetadataParser.HELPER_SCRIPT] helper against this SDK and
 * returns the parsed METADATA map. Single entry point so callers don't have to spell out the
 * exec-service plumbing or remember to pipe stdout through the parser.
 */
@ApiStatus.Internal
suspend fun Sdk.loadInstalledPackagesMetadata(): PyResult<Map<PyPackageName, PythonPackageMetadata>> {
  val output = ExecService()
    .executeHelper(this, PythonPackageMetadataParser.HELPER_SCRIPT, Args())
    .getOr { return it }
  return PythonPackageMetadataParser.parse(output)
}
