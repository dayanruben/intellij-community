// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.kdoc

import com.intellij.openapi.components.serviceOrNull
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.caches.resolve.KotlinCacheService
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.FrontendInternals
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.util.CallType
import org.jetbrains.kotlin.idea.util.getFileResolutionScope
import org.jetbrains.kotlin.idea.util.substituteExtensionIfCallable
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.kdoc.parser.KDocKnownTag
import org.jetbrains.kotlin.kdoc.psi.impl.KDocTag
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.FunctionDescriptorUtil
import org.jetbrains.kotlin.resolve.QualifiedExpressionResolver
import org.jetbrains.kotlin.resolve.descriptorUtil.isExtension
import org.jetbrains.kotlin.resolve.scopes.*
import org.jetbrains.kotlin.resolve.scopes.utils.*
import org.jetbrains.kotlin.resolve.source.PsiSourceElement
import org.jetbrains.kotlin.utils.Printer
import org.jetbrains.kotlin.utils.SmartList
import org.jetbrains.kotlin.utils.addIfNotNull

fun resolveKDocLink(
    context: BindingContext,
    resolutionFacade: ResolutionFacade,
    fromDescriptor: DeclarationDescriptor,
    contextElement: KtElement,
    fromSubjectOfTag: KDocTag?,
    qualifiedName: List<String>
): Collection<DeclarationDescriptor> {
    val tag = fromSubjectOfTag?.knownTag
    if (tag == KDocKnownTag.PARAM) {
        return resolveParamLink(fromDescriptor, qualifiedName)
    }
    val contextScope = getKDocLinkResolutionScope(resolutionFacade, fromDescriptor)
    if (qualifiedName.size == 1) {
        val localDescriptors = resolveLocal(qualifiedName.single(), contextScope, fromDescriptor)
        if (localDescriptors.isNotEmpty()) {
            return localDescriptors
        }
    }
    val isMarkdownLink = tag == null
    if (tag == KDocKnownTag.SAMPLE || tag == KDocKnownTag.SEE || isMarkdownLink) {
        val kdocService = resolutionFacade.project.serviceOrNull<KDocLinkResolutionService>()
        val declarationDescriptors = kdocService?.resolveKDocLink(context, fromDescriptor, resolutionFacade, qualifiedName) ?: emptyList()
        if (declarationDescriptors.isNotEmpty()) {
            return declarationDescriptors
        }
    }

    return resolveDefaultKDocLink(context, resolutionFacade, contextElement, qualifiedName, contextScope)
}

fun getParamDescriptors(fromDescriptor: DeclarationDescriptor): List<DeclarationDescriptor> {
    // TODO resolve parameters of functions passed as parameters
    when (fromDescriptor) {
        is CallableDescriptor -> {
            return fromDescriptor.valueParameters + fromDescriptor.typeParameters
        }

        is ClassifierDescriptor -> {
            val typeParams = fromDescriptor.typeConstructor.parameters
            if (fromDescriptor is ClassDescriptor) {
                val constructorDescriptor = fromDescriptor.unsubstitutedPrimaryConstructor
                if (constructorDescriptor != null) {
                    return typeParams + constructorDescriptor.valueParameters
                }
            }
            return typeParams
        }

        else -> {
            return emptyList()
        }
    }
}

private fun resolveParamLink(fromDescriptor: DeclarationDescriptor, qualifiedName: List<String>): List<DeclarationDescriptor> {
    val name = qualifiedName.singleOrNull() ?: return emptyList()
    return getParamDescriptors(fromDescriptor).filter { it.name.asString() == name }
}

private fun resolveLocal(
    nameToResolve: String,
    contextScope: LexicalScope,
    fromDescriptor: DeclarationDescriptor
): List<DeclarationDescriptor> {
    val shortName = Name.identifier(nameToResolve)

    val descriptorsByName = SmartList<DeclarationDescriptor>()
    descriptorsByName.addIfNotNull(contextScope.findClassifier(shortName, NoLookupLocation.FROM_IDE))
    descriptorsByName.addIfNotNull(contextScope.findPackage(shortName))
    descriptorsByName.addAll(contextScope.collectFunctions(shortName, NoLookupLocation.FROM_IDE))
    descriptorsByName.addAll(contextScope.collectVariables(shortName, NoLookupLocation.FROM_IDE))

    if (fromDescriptor is FunctionDescriptor && fromDescriptor.isExtension && shortName.asString() == "this") {
        return listOfNotNull(fromDescriptor.extensionReceiverParameter)
    }

    // Try to find a matching local descriptor (parameter or type parameter) first
    val localDescriptors = descriptorsByName.filter { it.containingDeclaration == fromDescriptor }
    if (localDescriptors.isNotEmpty()) return localDescriptors

    return descriptorsByName
}

private fun resolveDefaultKDocLink(
    context: BindingContext,
    resolutionFacade: ResolutionFacade,
    contextElement: PsiElement,
    qualifiedName: List<String>,
    contextScope: LexicalScope
): Collection<DeclarationDescriptor> {
    @OptIn(FrontendInternals::class)
    val qualifiedExpressionResolver = resolutionFacade.getFrontendService(QualifiedExpressionResolver::class.java)

    val factory = KtPsiFactory(resolutionFacade.project)
    // TODO escape identifiers
    val codeFragment = factory.createExpressionCodeFragment(qualifiedName.joinToString("."), contextElement)
    val qualifiedExpression =
        codeFragment.findElementAt(codeFragment.textLength - 1)?.getStrictParentOfType<KtQualifiedExpression>() ?: return emptyList()
    val (descriptor, memberName) = qualifiedExpressionResolver.resolveClassOrPackageInQualifiedExpression(
        qualifiedExpression,
        contextScope,
        context
    )
    if (descriptor == null) return emptyList()
    if (memberName != null) {
        val memberScope = getKDocLinkMemberScope(descriptor, contextScope)
        return memberScope.getContributedFunctions(memberName, NoLookupLocation.FROM_IDE) +
                memberScope.getContributedVariables(memberName, NoLookupLocation.FROM_IDE) +
                listOfNotNull(memberScope.getContributedClassifier(memberName, NoLookupLocation.FROM_IDE))
    }
    return listOf(descriptor)
}

private fun getPackageInnerScope(descriptor: PackageFragmentDescriptor): MemberScope {
    return descriptor.containingDeclaration.getPackage(descriptor.fqName).memberScope
}

private fun getClassInnerScope(outerScope: LexicalScope, descriptor: ClassDescriptor): LexicalScope {

    val headerScope = LexicalScopeImpl(
        outerScope, descriptor, false, descriptor.thisAsReceiverParameter,
        descriptor.contextReceivers, LexicalScopeKind.SYNTHETIC
    ) {
        descriptor.declaredTypeParameters.forEach { addClassifierDescriptor(it) }
        descriptor.constructors.forEach { addFunctionDescriptor(it) }
    }

    return LexicalChainedScope.create(
        headerScope, descriptor, false, null, emptyList(), LexicalScopeKind.SYNTHETIC,
        descriptor.defaultType.memberScope,
        descriptor.staticScope,
        descriptor.companionObjectDescriptor?.defaultType?.memberScope
    )
}

fun getKDocLinkResolutionScope(resolutionFacade: ResolutionFacade, contextDescriptor: DeclarationDescriptor): LexicalScope {
    return when (contextDescriptor) {
        is PackageFragmentDescriptor ->
            LexicalScope.Base(getPackageInnerScope(contextDescriptor).memberScopeAsImportingScope(), contextDescriptor)

        is PackageViewDescriptor ->
            LexicalScope.Base(contextDescriptor.memberScope.memberScopeAsImportingScope(), contextDescriptor)

        is ClassDescriptor ->
            getClassInnerScope(getOuterScope(contextDescriptor, resolutionFacade), contextDescriptor)

        is FunctionDescriptor -> FunctionDescriptorUtil.getFunctionInnerScope(
            getOuterScope(contextDescriptor, resolutionFacade),
            contextDescriptor, LocalRedeclarationChecker.DO_NOTHING
        )

        is PropertyDescriptor ->
            ScopeUtils.makeScopeForPropertyHeader(getOuterScope(contextDescriptor, resolutionFacade), contextDescriptor)

        is DeclarationDescriptorNonRoot ->
            getOuterScope(contextDescriptor, resolutionFacade)

        else -> throw IllegalArgumentException("Cannot find resolution scope for root $contextDescriptor")
    }
}

private fun getOuterScope(descriptor: DeclarationDescriptorWithSource, resolutionFacade: ResolutionFacade): LexicalScope {
    val parent = descriptor.containingDeclaration!!
    if (parent is PackageFragmentDescriptor) {
        val containingFile = (descriptor.source as? PsiSourceElement)?.psi?.containingFile as? KtFile ?: return LexicalScope.Base(
            ImportingScope.Empty,
            parent
        )
        val kotlinCacheService = containingFile.project.serviceOrNull<KotlinCacheService>()
        val facadeToUse = kotlinCacheService?.getResolutionFacade(containingFile) ?: resolutionFacade
        return facadeToUse.getFileResolutionScope(containingFile)
    } else {
        return getKDocLinkResolutionScope(resolutionFacade, parent)
    }
}

fun getKDocLinkMemberScope(descriptor: DeclarationDescriptor, contextScope: LexicalScope): MemberScope {
    return when (descriptor) {
        is PackageFragmentDescriptor -> getPackageInnerScope(descriptor)

        is PackageViewDescriptor -> descriptor.memberScope

        is ClassDescriptor -> {
            ChainedMemberScope.create(
                "Member scope for KDoc resolve", listOfNotNull(
                    descriptor.unsubstitutedMemberScope,
                    descriptor.staticScope,
                    descriptor.companionObjectDescriptor?.unsubstitutedMemberScope,
                    ExtensionsScope(descriptor, contextScope)
                )
            )
        }

        else -> MemberScope.Empty
    }
}

private class ExtensionsScope(
    private val receiverClass: ClassDescriptor,
    private val contextScope: LexicalScope
) : MemberScope {
    private val receiverTypes = listOf(receiverClass.defaultType)

    override fun getContributedFunctions(name: Name, location: LookupLocation): Collection<SimpleFunctionDescriptor> {
        return contextScope.collectFunctions(name, location).flatMap {
            if (it is SimpleFunctionDescriptor && it.isExtension) {
                it.substituteExtensionIfCallable(
                    receiverTypes = receiverTypes,
                    callType = CallType.DOT,
                    ignoreTypeParameters = true,
                )
            } else {
                emptyList()
            }
        }
    }

    override fun getContributedVariables(name: Name, location: LookupLocation): Collection<PropertyDescriptor> {
        return contextScope.collectVariables(name, location).flatMap {
            if (it is PropertyDescriptor && it.isExtension) {
                it.substituteExtensionIfCallable(
                    receiverTypes = receiverTypes,
                    callType = CallType.DOT,
                    ignoreTypeParameters = true,
                )
            } else {
                emptyList()
            }
        }
    }

    override fun getContributedClassifier(name: Name, location: LookupLocation): ClassifierDescriptor? = null

    override fun getContributedDescriptors(
        kindFilter: DescriptorKindFilter,
        nameFilter: (Name) -> Boolean
    ): Collection<DeclarationDescriptor> {
        if (DescriptorKindExclude.Extensions in kindFilter.excludes) return emptyList()
        return contextScope.collectDescriptorsFiltered(
            kindFilter exclude DescriptorKindExclude.NonExtensions,
            nameFilter,
            changeNamesForAliased = true
        ).flatMap {
            if (it is CallableDescriptor && it.isExtension) {
                it.substituteExtensionIfCallable(
                    receiverTypes = receiverTypes,
                    callType = CallType.DOT,
                    ignoreTypeParameters = true,
                )
            } else {
                emptyList()
            }
        }
    }

    override fun getFunctionNames(): Set<Name> =
        getContributedDescriptors(kindFilter = DescriptorKindFilter.FUNCTIONS).map { it.name }.toSet()

    override fun getVariableNames(): Set<Name> =
        getContributedDescriptors(kindFilter = DescriptorKindFilter.VARIABLES).map { it.name }.toSet()

    override fun getClassifierNames() = null

    override fun printScopeStructure(p: Printer) {
        p.println("Extensions for ${receiverClass.name} in:")
        contextScope.printStructure(p)
    }
}
