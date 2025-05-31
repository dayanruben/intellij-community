// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.polySymbols.documentation.impl

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiElement
import com.intellij.psi.createSmartPointer
import com.intellij.ui.scale.ScaleContext
import com.intellij.ui.scale.ScaleType
import com.intellij.util.IconUtil
import com.intellij.util.ui.UIUtil
import com.intellij.polySymbols.PolySymbol
import com.intellij.polySymbols.PolySymbolApiStatus
import com.intellij.polySymbols.PolySymbolOrigin
import com.intellij.polySymbols.PolySymbolsBundle
import com.intellij.polySymbols.documentation.PolySymbolDocumentation
import com.intellij.polySymbols.documentation.PolySymbolDocumentationTarget
import com.intellij.polySymbols.impl.scaleToHeight
import java.awt.Image
import java.awt.image.BufferedImage
import javax.swing.Icon

internal class PolySymbolDocumentationTargetImpl(
  override val symbol: PolySymbol,
  override val location: PsiElement?,
)
  : PolySymbolDocumentationTarget {

  override fun createPointer(): Pointer<out DocumentationTarget> {
    val pointer = symbol.createPointer()
    val locationPtr = location?.createSmartPointer()
    return Pointer<DocumentationTarget> {
      pointer.dereference()?.let { PolySymbolDocumentationTargetImpl(it, locationPtr?.dereference()) }
    }
  }

  companion object {

    fun buildDocumentation(origin: PolySymbolOrigin, doc: PolySymbolDocumentation): DocumentationResult {
      val url2ImageMap = mutableMapOf<String, Image>()

      @Suppress("HardCodedStringLiteral")
      val contents = StringBuilder()
        .appendHeader(doc)
        .appendDefinition(doc, url2ImageMap)
        .appendDescription(doc)
        .appendSections(doc)
        .appendFootnote(doc)
        .toString()
        .loadLocalImages(origin, url2ImageMap)
      return DocumentationResult.documentation(contents).images(url2ImageMap).externalUrl(doc.docUrl)
        .definitionDetails(doc.definitionDetails)
    }

    private fun StringBuilder.appendDefinition(doc: PolySymbolDocumentation, url2ImageMap: MutableMap<String, Image>): StringBuilder =
      append(DocumentationMarkup.DEFINITION_START)
        .also {
          doc.icon?.let { appendIcon(it, url2ImageMap).append("&nbsp;&nbsp;") }
        }
        .append(doc.definition)
        .append(DocumentationMarkup.DEFINITION_END)
        .append('\n')

    private fun StringBuilder.appendDescription(doc: PolySymbolDocumentation): StringBuilder =
      doc.description?.let {
        append(DocumentationMarkup.CONTENT_START).append('\n')
          .append(it).append('\n')
          .append(DocumentationMarkup.CONTENT_END)
      }
      ?: this

    private fun StringBuilder.appendSections(doc: PolySymbolDocumentation): StringBuilder =
      buildSections(doc).let { sections ->
        if (sections.isNotEmpty()) {
          append(DocumentationMarkup.SECTIONS_START)
            .append('\n')
          sections.entries.forEach { (name, value) ->
            append(DocumentationMarkup.SECTION_HEADER_START)
              .append(StringUtil.capitalize(name))
            if (value.isNotBlank()) {
              if (!name.endsWith(":"))
                append(':')
              append(DocumentationMarkup.SECTION_SEPARATOR)
                .append(value)
            }
            append(DocumentationMarkup.SECTION_END)
              .append('\n')
          }
          append(DocumentationMarkup.SECTIONS_END)
            .append('\n')
        }
        this
      }

    private fun StringBuilder.appendFootnote(doc: PolySymbolDocumentation): StringBuilder =
      doc.footnote?.let {
        append(DocumentationMarkup.CONTENT_START)
          .append(it)
          .append(DocumentationMarkup.CONTENT_END)
          .append('\n')
      } ?: this

    private fun StringBuilder.appendHeader(doc: PolySymbolDocumentation): StringBuilder =
      doc.header?.let {
        append("<div class='" + DocumentationMarkup.CLASS_TOP + "'>")
          .append(it)
          .append("</div>\n")
      } ?: this

    private fun buildSections(doc: PolySymbolDocumentation): Map<String, String> =
      LinkedHashMap(doc.descriptionSections).also { sections ->
        if (doc.required) sections[PolySymbolsBundle.message("mdn.documentation.section.isRequired")] = ""
        doc.apiStatus?.let { status ->
          when (status) {
            is PolySymbolApiStatus.Deprecated -> {
              sections[PolySymbolsBundle.message("mdn.documentation.section.status.Deprecated")] = status.message ?: ""
              status.since?.let { sections[PolySymbolsBundle.message("mdn.documentation.section.status.DeprecatedSince")] = it }
            }
            is PolySymbolApiStatus.Obsolete -> {
              sections[PolySymbolsBundle.message("mdn.documentation.section.status.Obsolete")] = status.message ?: ""
              status.since?.let { sections[PolySymbolsBundle.message("mdn.documentation.section.status.ObsoleteSince")] = it }
            }
            is PolySymbolApiStatus.Experimental -> {
              sections[PolySymbolsBundle.message("mdn.documentation.section.status.Experimental")] = status.message ?: ""
              status.since?.let { sections[PolySymbolsBundle.message("mdn.documentation.section.status.Since")] = it }
            }
            is PolySymbolApiStatus.Stable -> {
              status.since?.let { sections[PolySymbolsBundle.message("mdn.documentation.section.status.Since")] = it }
            }
          }
        }
        doc.defaultValue?.let { sections[PolySymbolsBundle.message("mdn.documentation.section.defaultValue")] = "<p><code>$it</code>" }
        doc.library?.let { sections[PolySymbolsBundle.message("mdn.documentation.section.library")] = "<p>$it" }
      }

    private fun StringBuilder.appendIcon(icon: Icon, url2ImageMap: MutableMap<String, Image>): StringBuilder {
      // TODO adjust it to the actual component being used
      @Suppress("UndesirableClassUsage")
      val bufferedImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
      val g = bufferedImage.createGraphics()
      g.font = UIUtil.getToolTipFont()
      val height = (g.fontMetrics.getStringBounds("a", g).height / ScaleContext.create().getScale(ScaleType.USR_SCALE)).toInt()
      g.dispose()
      val image = try {
        IconUtil.toBufferedImage(icon.scaleToHeight(height))
      }
      catch (e: Exception) {
        // ignore
        return this
      }
      val url = "https://img${url2ImageMap.size}"
      url2ImageMap[url] = image
      val screenHeight = height * ScaleContext.create().getScale(ScaleType.SYS_SCALE)
      append("<img src='$url' height=\"$screenHeight\" width=\"${(screenHeight * icon.iconWidth) / icon.iconHeight}\" border=0 />")
      return this
    }

    private val imgSrcRegex = Regex("<img [^>]*src\\s*=\\s*['\"]([^'\"]+)['\"]")

    private fun String.loadLocalImages(origin: PolySymbolOrigin, url2ImageMap: MutableMap<String, Image>): String {
      val replaces = imgSrcRegex.findAll(this)
        .mapNotNull { it.groups[1] }
        .filter { !it.value.contains(':') }
        .mapNotNull { group ->
          origin.loadIcon(group.value)
            ?.let { IconUtil.toBufferedImage(it, true) }
            ?.let {
              val url = "https://img${url2ImageMap.size}"
              url2ImageMap[url] = it
              Pair(group.range, url)
            }
        }
        .sortedBy { it.first.first }
        .toList()
      if (replaces.isEmpty()) return this
      val result = StringBuilder()
      var lastIndex = 0
      for (replace in replaces) {
        result.appendRange(this, lastIndex, replace.first.first)
        result.append(replace.second)
        lastIndex = replace.first.last + 1
      }
      if (lastIndex < this.length) {
        result.appendRange(this, lastIndex, this.length)
      }
      return result.toString()
    }

  }

}