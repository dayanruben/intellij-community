// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.startup.importSettings.transfer.backend.providers.vswin.utilities

import com.intellij.ide.startup.importSettings.TransferableIdeVersionId
import com.intellij.ide.startup.importSettings.providers.vswin.utilities.Version2
import com.intellij.ide.startup.importSettings.transfer.backend.providers.vswin.parsers.VSIsolationIniParser
import com.intellij.ide.startup.importSettings.transfer.backend.providers.vswin.parsers.VSRegistryParserNew
import com.intellij.openapi.diagnostic.logger
import java.util.*
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime

class VSHiveDetourFileNotFoundException : Exception()

private val logger = logger<VSHive>()
// Example: "15.0_a0848a47Exp", where VS version = "15.0", Instance Id = "a0848a47", Root Suffix = "Exp".
class VSHive(val version: Version2, val instanceId: String? = null, val rootSuffix: String? = null) {
  enum class Types {
    Old, New, All
  }
  val hiveString: String
    get() = "$version${if (instanceId == null) "" else "_$instanceId"}${rootSuffix ?: ""}"
  val presentationString: String
    get() = "Visual Studio ${productVersionTextRepresentation()}"

  val registry: VSRegistryParserNew? by lazy { VSRegistryParserNew.create(this) }
  val isolation: VSIsolationIniParser? by lazy { VSIsolationIniParser.create(this) }

  val isInstalled: Boolean by lazy { registry?.envPath?.second.let { it != null && it.exists() } }
  val lastUsage: Date by lazy { registry?.settingsFile?.getLastModifiedTime().let { Date(it?.toMillis() ?: 0L) } }
  val edition: String? by lazy { isolation?.edition }

  init {
    if (version.major >= 15 && instanceId?.length != 8) {
      throw IllegalArgumentException("Since Visual Studio 15 instanceId is required or bad instanceId is supplied (${instanceId})")
    }
  }

  override fun toString(): String {
    return "Visual Studio ${productVersionTextRepresentation()} ($hiveString)"
  }

  private fun productVersionTextRepresentation(): String {
    return when (version.major) {
      17 -> "2022"
      16 -> "2019"
      15 -> "2017"
      14 -> "2015"
      12 -> "2013"
      11 -> "2012"
      else -> "Ver. ${version.major}.${version.minor}"
    }
  }

  fun transferableVersion(): TransferableIdeVersionId {
    return when (version.major) {
      17 -> TransferableIdeVersionId.V2022
      16 -> TransferableIdeVersionId.V2019
      15 -> TransferableIdeVersionId.V2017
      14 -> TransferableIdeVersionId.V2015
      12 -> TransferableIdeVersionId.V2013
      11 -> TransferableIdeVersionId.V2012
      else -> TransferableIdeVersionId.Unknown
    }
  }

  companion object {
    val regex: Regex = Regex("\\b([0-9]{1,2})\\.([0-9])(?:_([a-fA-F0-9]{8}))?([a-zA-Z0-9]*)\\b")

    fun parse(hive: String, type: Types = Types.All): VSHive? {
      logger.trace("Starting $hive on type $type")

      val spl = regex.find(hive) ?: return null
      val (maj, min, instId, rtSuf) = spl.destructured

      val ver = try {
        Version2(maj.toInt(), min.toInt())
      }
      catch (_: NumberFormatException) {
        logger.info("Bad major or minor version number ($hive)")
        return null
      }

      if (type == Types.Old && (ver.major != 11 && ver.major != 12 && ver.major != 14)) {
        logger.trace("Wanted to access only old versions, returning ($hive)")
        return null
      }

      if (type == Types.New && instId.length != 8) {
        logger.trace("Requested only new vs, but got something other ($hive)")
        return null
      }

      if (maj.toInt() < 14) { // 2015
        logger.trace("Unsupported version ($hive)")
        return null
      }

      /*val newVsProp = System.getProperty("rider.transfer.newVS")?.toBoolean()
      if (maj.toInt() > LATEST_VS_VERSION && (newVsProp == false || newVsProp == null)) {
          logger.info("New Visual Studio $maj found, but we don't know it yet. Enable them with -Drider.transfer.newVS=true")
          return null
      }
       */

      logger.trace("Parsed $hive")

      return VSHive(ver, instId.ifEmpty { null }, rtSuf.ifEmpty { null }).apply {
        logger.assertTrue(hive == this.hiveString, "different hive string")
      }
    }
  }
}