// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.hints

import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.codeInsight.hints.filtering.MatcherConstructor
import com.intellij.codeInsight.hints.parameters.ParameterHintsExcludeListConfig
import com.intellij.codeInsight.hints.settings.ParameterNameHintsSettings
import com.intellij.lang.Language
import com.intellij.lang.LanguageExtensionPoint
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.text.StringUtil
import org.jetbrains.annotations.ApiStatus

internal object HintUtils {
  fun getLanguagesWithNewInlayHints(): Set<Language> {
    val languages = HashSet<Language>()
    InlayHintsProviderFactory.EP.extensionList.flatMapTo(languages) { it.getProvidersInfo().map { info -> info.language } }
    return languages
  }

  fun getHintProvidersForLanguage(language: Language): List<ProviderWithSettings<*>> {
    val config = InlayHintsSettings.instance()
    return InlayHintsProviderFactory.EP.extensionList
      .asSequence()
      .flatMap { it.getProvidersInfoForLanguage(language) }
      .filter { it.isLanguageSupported(language) }
      .map { it.withSettings(it.getSettingsLanguage(language), config) }
      .toList()
  }
}

// region InlayParameterHintsProvider settings-related utils

fun getHintProviders(): List<Pair<Language, InlayParameterHintsProvider>> {
  return doGetHintProviderLanguages().toList().map { it to InlayParameterHintsExtension.forLanguage(it) }.toList()
}

private fun doGetHintProviderLanguages(): Sequence<Language> {
  return ExtensionPointName<LanguageExtensionPoint<InlayParameterHintsProvider>>("com.intellij.codeInsight.parameterNameHints")
    .extensionList.asSequence()
    .mapNotNull { Language.findLanguageByID(it.language) }
}

fun getExcludeListInvalidLineNumbers(text: String): List<Int> {
  val rules = StringUtil.split(text, "\n", true, false)
  return rules
    .asSequence()
    .mapIndexedNotNull { index, s -> index to s }
    .filter { it.second.isNotEmpty() }
    .map { it.first to MatcherConstructor.createMatcher(it.second) }
    .filter { it.second == null }
    .map { it.first }
    .toList()
}

fun getLanguageForSettingKey(language: Language): Language {
  val supportedLanguages = getBaseLanguagesWithProviders()
  var languageForSettings: Language? = language
  while (languageForSettings != null && !supportedLanguages.contains(languageForSettings)) {
    languageForSettings = languageForSettings.baseLanguage
  }
  return languageForSettings ?: language
}

fun getBaseLanguagesWithProviders(): List<Language> {
  return doGetHintProviderLanguages()
    .sortedBy { l -> l.displayName }
    .toList()
}

fun isParameterHintsEnabledForLanguage(language: Language): Boolean {
  @Suppress("DEPRECATION")
  if (!InlayHintsSettings.instance().hintsShouldBeShown(language)) return false
  return ParameterNameHintsSettings.getInstance().isEnabledForLanguage(getLanguageForSettingKey(language))
}

fun setShowParameterHintsForLanguage(value: Boolean, language: Language) {
  ParameterNameHintsSettings.getInstance().setIsEnabledForLanguage(value, getLanguageForSettingKey(language))
}

internal fun refreshParameterHintsOnNextPass() {
  ParameterHintsPassFactory.forceHintsUpdateOnNextPass()
  DeclarativeInlayHintsPassFactory.resetModificationStamp()
}

@ApiStatus.Internal
fun getExcludeList(settings: ParameterNameHintsSettings, config: ParameterHintsExcludeListConfig): Set<String> {
  val diff = settings.getExcludeListDiff(config.language)
  return diff.applyOn(config.defaultExcludeList)
}

// endregion