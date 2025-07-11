// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.resolve

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.analyzer.ResolverForProject
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.diagnostics.DiagnosticSink
import org.jetbrains.kotlin.idea.FrontendInternals
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowValueFactory
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode

interface ResolutionFacade {
    val project: Project

    fun analyze(element: KtElement, bodyResolveMode: BodyResolveMode = BodyResolveMode.FULL): BindingContext
    fun analyze(elements: Collection<KtElement>, bodyResolveMode: BodyResolveMode): BindingContext

    fun analyzeWithAllCompilerChecks(element: KtElement, callback: DiagnosticSink.DiagnosticsCallback? = null): AnalysisResult
        = analyzeWithAllCompilerChecks(listOf(element), callback)

    fun analyzeWithAllCompilerChecks(elements: Collection<KtElement>, callback: DiagnosticSink.DiagnosticsCallback? = null): AnalysisResult

    fun fetchWithAllCompilerChecks(element: KtElement): AnalysisResult? = null

    fun resolveToDescriptor(declaration: KtDeclaration, bodyResolveMode: BodyResolveMode = BodyResolveMode.FULL): DeclarationDescriptor

    val moduleDescriptor: ModuleDescriptor

    // get service for the module this resolution was created for
    @FrontendInternals
    fun <T : Any> getFrontendService(serviceClass: Class<T>): T

    fun <T : Any> getIdeService(serviceClass: Class<T>): T

    // get service for the module defined by PsiElement/ModuleDescriptor passed as parameter
    @FrontendInternals
    fun <T : Any> getFrontendService(element: PsiElement, serviceClass: Class<T>): T

    @FrontendInternals
    fun <T : Any> tryGetFrontendService(element: PsiElement, serviceClass: Class<T>): T?

    fun getResolverForProject(): ResolverForProject<out ModuleInfo>
}

@FrontendInternals
inline fun <reified T : Any> ResolutionFacade.frontendService(): T = this.getFrontendService(T::class.java)

inline fun <reified T : Any> ResolutionFacade.ideService(): T = this.getIdeService(T::class.java)

val ResolutionFacade.languageVersionSettings: LanguageVersionSettings
    get() = @OptIn(FrontendInternals::class) frontendService()

val ResolutionFacade.dataFlowValueFactory: DataFlowValueFactory
    get() = @OptIn(FrontendInternals::class) frontendService()