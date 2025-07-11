// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.refactoring

import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import com.intellij.util.IncorrectOperationException
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSyntheticJavaPropertySymbol
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.idea.base.analysis.api.utils.shortenReferences
import org.jetbrains.kotlin.idea.base.analysis.withRootPrefixIfNeeded
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinNameSuggester
import org.jetbrains.kotlin.idea.base.psi.imports.addImport
import org.jetbrains.kotlin.idea.base.psi.kotlinFqName
import org.jetbrains.kotlin.idea.base.psi.replaced
import org.jetbrains.kotlin.idea.base.util.quoteIfNeeded
import org.jetbrains.kotlin.idea.kdoc.KDocElementFactory
import org.jetbrains.kotlin.idea.refactoring.canMoveLambdaOutsideParentheses
import org.jetbrains.kotlin.idea.refactoring.nameDeterminant
import org.jetbrains.kotlin.idea.refactoring.rename.KtReferenceMutateServiceBase
import org.jetbrains.kotlin.idea.references.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.tail
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.contains
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedElementOrCallableRef
import org.jetbrains.kotlin.psi.psiUtil.isExtensionDeclaration
import org.jetbrains.kotlin.psi.psiUtil.isTopLevelKtOrJavaMember
import org.jetbrains.kotlin.resolve.ROOT_PREFIX_FOR_IDE_RESOLUTION_MODE
import org.jetbrains.kotlin.util.OperatorNameConventions

/**
 * At the moment, this implementation of [KtReferenceMutateService] is not able to do some of the
 * required operations. It is OK and on purpose - this functionality will be added later.
 */
@Suppress("UNCHECKED_CAST")
internal class K2ReferenceMutateService : KtReferenceMutateServiceBase() {
    companion object {
        private val ROOT_PREFIX_FOR_IDE_RESOLUTION_MODE_NAME: Name = Name.identifier(ROOT_PREFIX_FOR_IDE_RESOLUTION_MODE)
    }


    @OptIn(KaAllowAnalysisFromWriteAction::class, KaAllowAnalysisOnEdt::class)
    override fun bindToElement(ktReference: KtReference, element: PsiElement): PsiElement = allowAnalysisOnEdt {
        return allowAnalysisFromWriteAction {
            when (ktReference) {
                is KtSimpleNameReference -> bindToElement(ktReference, element, KtSimpleNameReference.ShorteningMode.FORCED_SHORTENING)
                is KDocReference -> bindToElement(ktReference, element, KtSimpleNameReference.ShorteningMode.FORCED_SHORTENING)
                is KtInvokeFunctionReference -> bindUnnamedReference(ktReference, element, OperatorNameConventions.INVOKE)
                is KtArrayAccessReference -> bindUnnamedReference(ktReference, element, OperatorNameConventions.GET)
                is KtForLoopInReference -> bindUnnamedReference(ktReference, element, OperatorNameConventions.ITERATOR)
                is KtDestructuringDeclarationReference -> bindUnnamedReference(ktReference, element, ktReference.resolvesByNames.singleOrNull() ?: return ktReference.element)
                else -> throw IncorrectOperationException("Unsupported reference type: $ktReference")
            }
        }
    }

    private fun bindUnnamedReference(reference: KtReference, targetElement: PsiElement?, resolvedName: Name): PsiElement {
        val expression = reference.element
        if (targetElement !is KtNamedFunction) return expression
        if (targetElement.nameAsName != resolvedName) return expression
        val fqName = targetElement.kotlinFqName ?: return targetElement
        return expression.containingKtFile.addImport(fqName)
    }

    @RequiresWriteLock
    private fun bindToElement(
        docReference: KDocReference,
        targetElement: PsiElement,
        shorteningMode: KtSimpleNameReference.ShorteningMode
    ): PsiElement {
        val docElement = docReference.element
        if (docReference.isReferenceTo(targetElement)) return docElement
        val targetFqn = targetElement.kotlinFqName ?: return docElement
        if (targetFqn.isRoot) return docElement
        val newDocReference = KDocElementFactory(targetElement.project).createNameFromText(targetFqn.asString())
        val replacedDocReference = docElement.replaced(newDocReference)
        return if (shorteningMode != KtSimpleNameReference.ShorteningMode.NO_SHORTENING) {
            shortenReferences(replacedDocReference) ?: replacedDocReference
        } else replacedDocReference
    }

    private class ReplaceResult(val replacedElement: KtElement, val isUnQualifiable: Boolean)

    private fun FqName.withoutRootPrefix(): FqName {
        return tail(FqName.topLevel(ROOT_PREFIX_FOR_IDE_RESOLUTION_MODE_NAME))
    }

    /**
     * Removes the [ROOT_PREFIX_FOR_IDE_RESOLUTION_MODE] from this expression if it exists.
     */
    private fun KtDotQualifiedExpression.removeRootPrefix(): PsiElement {
        val selectorExpression = selectorExpression
        val receiverExpression = receiverExpression
        return if (selectorExpression != null &&
            receiverExpression is KtReferenceExpression &&
            receiverExpression.text == ROOT_PREFIX_FOR_IDE_RESOLUTION_MODE
        ) {
            this.replace(selectorExpression)
        } else if (receiverExpression is KtDotQualifiedExpression) {
            receiverExpression.removeRootPrefix()
            this
        } else {
            this
        }
    }

    /**
     * Removes the [ROOT_PREFIX_FOR_IDE_RESOLUTION_MODE] from this type qualifier if it exists.
     */
    private fun KtUserType.removeRootPrefix(): PsiElement {
        val qualifier = qualifier

        if (qualifier != null && qualifier.qualifier == null && qualifier.text == ROOT_PREFIX_FOR_IDE_RESOLUTION_MODE) {
            deleteQualifier()
        } else {
            qualifier?.removeRootPrefix()
        }
        return this
    }

    /**
     * Removes the [ROOT_PREFIX_FOR_IDE_RESOLUTION_MODE] from this element, if it is prefixed with it.
     */
    private fun PsiElement.removeRootPrefix(): PsiElement = when (this) {
        is KtUserType -> removeRootPrefix()
        is KtDotQualifiedExpression -> removeRootPrefix()
        else -> this
    }

    @OptIn(KaAllowAnalysisOnEdt::class, KaAllowAnalysisFromWriteAction::class)
    @RequiresWriteLock
    override fun bindToFqName(
        simpleNameReference: KtSimpleNameReference,
        fqName: FqName,
        shorteningMode: KtSimpleNameReference.ShorteningMode, // delayed shortening is not supported
        targetElement: PsiElement?
    ): PsiElement = allowAnalysisOnEdt {
        allowAnalysisFromWriteAction {
            val expression = simpleNameReference.expression
            if (targetElement != null) { // if we are already referencing the target, there is no need to call bindToElement
                if (simpleNameReference.isReferenceTo(targetElement)) return expression

                // if reference will be unresolvable, don't retarget
                if (!simpleNameReference.element.resolveScope.contains(targetElement)) return expression
            } else {
                // Here we assume that the passed fqName uniquely identifies the new target element
                val oldTarget = simpleNameReference.resolve()
                if (oldTarget?.kotlinFqName == fqName) return expression
            }
            if (fqName.isRoot) return expression
            val elementToReplace = expression.getQualifiedElementOrCallableRef()
            // If we allow for shortening the references after, we want to add the ROOT_PREFIX_FOR_IDE_RESOLUTION_MODE,
            // which avoids local variables capturing package names of the FQN.
            // Note: When adding an import, we never want to add the root prefix, so we need to drop it before importing.
            val fqNameWithRootPrefix = if (shorteningMode != KtSimpleNameReference.ShorteningMode.NO_SHORTENING) {
                fqName.withRootPrefixIfNeeded()
            } else {
                fqName
            }
            val result = when (elementToReplace) {
                is KtUserType -> elementToReplace.replaceWith(fqNameWithRootPrefix, targetElement)
                is KtQualifiedExpression -> elementToReplace.replaceWith(fqNameWithRootPrefix, targetElement)
                is KtCallExpression -> elementToReplace.replaceWith(fqNameWithRootPrefix, targetElement)
                is KtCallableReferenceExpression -> elementToReplace.replaceWith(fqNameWithRootPrefix, targetElement)
                is KtSimpleNameExpression -> elementToReplace.replaceWith(fqNameWithRootPrefix, targetElement)
                else -> return expression
            } ?: return expression
            val shouldShorten = shorteningMode != KtSimpleNameReference.ShorteningMode.NO_SHORTENING && !result.isUnQualifiable
            val shortenResult = if (shouldShorten) {
                shortenReferences(result.replacedElement) ?: result.replacedElement
            } else {
                result.replacedElement
            }
            // There are some circumstances where the reference shortener does not shorten an expression we added the root prefix to.
            // This can happen if the reference shortener is unable to add an import because the declaration's name is already
            // being used in the target package.
            // In these cases, we need to manually remove the added root prefix.
            return if (shorteningMode != KtSimpleNameReference.ShorteningMode.NO_SHORTENING) {
                shortenResult.removeRootPrefix()
            } else {
                shortenResult
            }
        }
    }

    private fun KtUserType.replaceWith(fqName: FqName, targetElement: PsiElement?): ReplaceResult {
        val replacedElement = if (qualifier == null) {
            val typeArgText = typeArgumentList?.text ?: ""
            val newReference = KtPsiFactory(project).createType(fqName.quoteIfNeeded().asString() + typeArgText).typeElement as? KtUserType
                ?: error("Could not create type from $fqName")
            replaced(newReference)
        } else {
            val parentFqn = fqName.parent()
            if (parentFqn.isRoot) {
                deleteQualifier()
            } else {
                qualifier?.replaceWith(parentFqn, targetElement) // do recursive short name replacement to preserve type arguments
            }
            referenceExpression?.replaceShortName(fqName)?.parent as KtUserType
        }
        return ReplaceResult(replacedElement, false)
    }

    private fun PsiElement.isCallableAsExtensionFunction(): Boolean {
        if (isExtensionDeclaration()) return true
        return if (this is KtProperty) {
            analyze(this) {
                val returnType = returnType
                returnType is KaFunctionType && returnType.receiverType != null
            }
        } else false
    }

    private fun KtQualifiedExpression.replaceWith(fqName: FqName, targetElement: PsiElement?): ReplaceResult? {
        val isImport = parentOfType<KtImportDirective>(withSelf = false) != null
        if (isImport) return ReplaceResult(replaced(KtPsiFactory(project).createExpression(fqName.quoteIfNeeded().asString())), true)
        val selectorExpression = selectorExpression ?: return null
        val selectorReplacement = when (selectorExpression) {
            is KtSimpleNameExpression -> selectorExpression.replaceShortNameOrImport(fqName, targetElement)
            is KtCallExpression -> selectorExpression.replaceShortName(fqName, targetElement)
            else -> null
        } ?: return null
        if (selectorReplacement.isUnQualifiable) return selectorReplacement
        if (fqName.withoutRootPrefix().parent() == FqName.ROOT) return ReplaceResult(replaced(selectorReplacement.replacedElement), false)
        val newSelectorExpression = selectorReplacement.replacedElement as? KtExpression ?: return null
        return ReplaceResult(replaceWithQualified(fqName, newSelectorExpression), false)
    }

    private fun KtExpression.replaceWithQualified(fqName: FqName, selectorExpression: KtExpression): KtExpression {
        val parentFqName = fqName.parent()
        if (parentFqName.isRoot) return replaced(selectorExpression)
        val packageName = fqName.parent().quoteIfNeeded().asString()
        val newQualifiedExpression = KtPsiFactory(project).createExpression("$packageName.${selectorExpression.text}")
        return replaced(newQualifiedExpression)
    }

    private fun KtCallExpression.replaceWith(fqName: FqName, targetElement: PsiElement?): ReplaceResult? {
        val shortNameReplacement = replaceShortName(fqName, targetElement) ?: return null
        if (shortNameReplacement.isUnQualifiable) return shortNameReplacement
        val newCall = shortNameReplacement.replacedElement as KtExpression
        return ReplaceResult(newCall.replaceWithQualified(fqName, newCall), false)
    }

    private fun KtCallableReferenceExpression.replaceWith(fqName: FqName, targetElement: PsiElement?): ReplaceResult? {
        if (targetElement == null) return null
        val isUnQualifiable = targetElement.nameDeterminant().isTopLevelKtOrJavaMember()
        val callableReference = if (isUnQualifiable || fqName.withoutRootPrefix().parent() == FqName.ROOT) {
            containingKtFile.addImport(fqName.withoutRootPrefix())
            val receiverExpr = receiverExpression
            if (receiverExpr != null && targetElement.isCallableAsExtensionFunction()) {
                KtPsiFactory(project).createCallableReferenceExpression("${receiverExpr.text}::${fqName.shortName()}")
            } else {
                KtPsiFactory(project).createCallableReferenceExpression("::${fqName.shortName()}")
            }
        } else {
            KtPsiFactory(project).createCallableReferenceExpression("${fqName.parent().asString()}::${fqName.shortName()}")
        }
        if (callableReference == null) return null
        return ReplaceResult(replaced(callableReference), isUnQualifiable)
    }

    private fun KtCallExpression.replaceShortName(fqName: FqName, targetElement: PsiElement?): ReplaceResult? {
        val psiFactory = KtPsiFactory(project)
        val newName = psiFactory.createSimpleName(fqName.quoteIfNeeded().shortName().asString())
        val newCall = calleeExpression?.replaced(newName)?.parent as? KtCallExpression ?: return null
        val isUnQualifiable = targetElement?.isCallableAsExtensionFunction() == true
        return if (isUnQualifiable || fqName.withoutRootPrefix().parent() == FqName.ROOT) {
            newCall.containingKtFile.addImport(fqName.withoutRootPrefix())
            ReplaceResult(newCall, isUnQualifiable)
        } else ReplaceResult(newCall, false)
    }

    private fun KtSimpleNameExpression.replaceWith(fqName: FqName, targetElement: PsiElement?): ReplaceResult {
        val shortNameReplaceResult = if (this is KtOperationReferenceExpression && targetElement is KtNamedFunction) {
            replaceWith(fqName, targetElement)
        } else {
            replaceShortNameOrImport(fqName, targetElement)
        }
        if (shortNameReplaceResult.isUnQualifiable) return shortNameReplaceResult
        val newNameExpr = shortNameReplaceResult.replacedElement as KtExpression
        return ReplaceResult(newNameExpr.replaceWithQualified(fqName, newNameExpr), false)
    }

    private fun KtSimpleNameExpression.replaceShortNameOrImport(fqName: FqName, targetElement: PsiElement?): ReplaceResult {
        val replacedExpr = replaceShortName(fqName)
        val isUnQualifiable = targetElement?.isCallableAsExtensionFunction() == true
        return if (isUnQualifiable || fqName.withoutRootPrefix().parent() == FqName.ROOT) {
            replacedExpr.containingKtFile.addImport(fqName.withoutRootPrefix())
            ReplaceResult(replacedExpr, isUnQualifiable)
        } else ReplaceResult(replacedExpr, false)
    }

    private fun KtSimpleNameExpression.replaceShortName(fqName: FqName): KtElement {
        val shortName = fqName.quoteIfNeeded().shortName().asString()
        return replaced(KtPsiFactory(project).createSimpleName(shortName))
    }

    private fun KtOperationReferenceExpression.replaceWith(fqName: FqName, targetElement: KtNamedFunction): ReplaceResult {
        val psiFactory = KtPsiFactory(project)
        val shortName = fqName.quoteIfNeeded().shortName().asString()
        val isInfix = analyze(targetElement) { (targetElement.symbol as? KaNamedFunctionSymbol)?.isInfix == true }
        val isOperator = analyze(targetElement) { (targetElement.symbol as? KaNamedFunctionSymbol)?.isOperator == true }
        val replacedExpr = if (isOperator) {
            val identifier = Name.identifier(shortName)
            val isUnary = OperatorNameConventions.UNARY_OPERATION_NAMES.contains(identifier)
            val operator = OperatorNameConventions.TOKENS_BY_OPERATOR_NAME[identifier] ?: shortName
            val newOperator = if (isUnary) {
                (psiFactory.createExpression("${operator}0") as KtUnaryExpression).operationReference
            } else {
                psiFactory.createOperationName(operator)
            }
            replaced(newOperator)
        } else if (isInfix) {
            replaced(psiFactory.createOperationName(shortName))
        } else {
            // replacing infix or operator function call with regular call
            val binaryExpression = parentOfType<KtBinaryExpression>() ?: error("Binary expression expected")
            binaryExpression.replaced(psiFactory.createExpression("${binaryExpression.left?.text}.$shortName(${binaryExpression.right?.text})"))
        }
        val isUnQualifiable = targetElement.isCallableAsExtensionFunction() || replacedExpr is KtOperationReferenceExpression
        return if (isUnQualifiable || fqName.withoutRootPrefix().parent() == FqName.ROOT) {
            replacedExpr.containingKtFile.addImport(fqName.withoutRootPrefix())
            ReplaceResult(replacedExpr, isUnQualifiable)
        } else ReplaceResult(replacedExpr, false)
    }

    override fun KtSimpleReference<KtNameReferenceExpression>.suggestVariableName(
        expr: KtExpression,
        context: PsiElement
    ): String {
        @OptIn(KaAllowAnalysisOnEdt::class)
        allowAnalysisOnEdt {
            analyze(expr) {
                return KotlinNameSuggester(KotlinNameSuggester.Case.CAMEL).suggestExpressionNames(expr).first()
            }
        }
    }

    override fun handleElementRename(ktReference: KtReference, newElementName: String): PsiElement? {
        @OptIn(KaAllowAnalysisFromWriteAction::class)
        return allowAnalysisFromWriteAction {
            @OptIn(KaAllowAnalysisOnEdt::class)
            allowAnalysisOnEdt {
                analyze(ktReference.element) {
                    val symbol = ktReference.resolveToSymbol()
                    if (symbol is KaSyntheticJavaPropertySymbol) {
                        val newName = (ktReference as? KtSimpleReference<KtNameReferenceExpression>)?.getAdjustedNewName(newElementName)
                        if (newName == null) {
                            return (ktReference as? KtSimpleReference<KtNameReferenceExpression>)?.renameToOrdinaryMethod(newElementName)
                        } else {
                            return super.handleElementRename(ktReference, newName.asString())
                        }
                    }
                }

                super.handleElementRename(ktReference, newElementName)
            }
        }
    }

    @OptIn(KaAllowAnalysisOnEdt::class, KaAllowAnalysisFromWriteAction::class)
    override fun canMoveLambdaOutsideParentheses(callExpression: KtCallExpression?): Boolean =
        callExpression?.let {
            allowAnalysisOnEdt {
                allowAnalysisFromWriteAction {
                    analyze(it) {
                        it.canMoveLambdaOutsideParentheses()
                    }
                }
            }
        } == true
}