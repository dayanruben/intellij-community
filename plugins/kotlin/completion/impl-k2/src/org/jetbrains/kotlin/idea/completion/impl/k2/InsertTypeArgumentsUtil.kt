// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.completion.impl.k2

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.resolveToCall
import org.jetbrains.kotlin.analysis.api.resolution.KaErrorCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.idea.codeinsight.utils.addTypeArguments
import org.jetbrains.kotlin.idea.codeinsight.utils.getRenderedTypeArguments
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtTypeArgumentList
import org.jetbrains.kotlin.psi.UserDataProperty
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedExpressionForSelector
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.util.match

// Note: this file is ported to K2 from `org.jetbrains.kotlin.idea.completion.KotlinInsertTypeArgument.kt`

// Debugging tip: use 'PsiTreeUtilsKt.printTree' to see PSI trees in the runtime. See fun documentation for details.

internal data class TypeArgsWithOffset(val args: KtTypeArgumentList, val offset: Int)
internal var UserDataHolder.argList: TypeArgsWithOffset? by UserDataProperty(Key("KotlinInsertTypeArgument.ARG_LIST"))

context(_: KaSession)
internal fun addParamTypesIfNeeded(position: PsiElement): PsiElement? {
    val callBeforeDot = position.getCallBeforeDot() ?: return null

    // Type arguments are already specified, no need to add them
    if (callBeforeDot.typeArguments.isNotEmpty()) return null

    val resolvedCall = callBeforeDot.resolveToCall()
    // The call is already successfully resolved, no type arguments are needed
    if (resolvedCall !is KaErrorCallInfo) return null
    // If there is no call with an error type, then we cannot fix it by adding explicit types
    if (!resolvedCall.hasCandidateWithErrorTypes()) return null

    // We attempt to fix the unresolved call by restoring the type arguments it had before adding the `.`
    val fixedPosition = addParamTypes(position)
    val newCallBeforeDot = fixedPosition.getCallBeforeDot() ?: return null

    // If the call now resolves successfully, then we return the new position
    analyze(newCallBeforeDot) {
        return fixedPosition.takeIf {
            newCallBeforeDot.resolveToCall()?.successfulFunctionCallOrNull() != null
        }
    }
}

context(_: KaSession)
private fun addParamTypes(position: PsiElement): PsiElement {

    data class CallAndDiff(
        // callExpression is a child node of dotExprWithoutCaret in general case (in simple case they are equal)
        val callExpression: KtCallExpression,  // like call()
        val dotExprWithoutCaret: KtExpression, // like smth.call()
        val dotExprWithCaret: KtQualifiedExpression // initial expression like smth.call().IntellijIdeaRulezzz (now separate synthetic tree)
    )

    fun getCallWithParamTypesToAdd(positionInCopy: PsiElement): CallAndDiff? {
        /*
        ............KtDotQualifiedExpression [call().IntellijIdeaRulezzz]
        ...............KtCallExpression [call()]
        ............................................................
        ...............KtNameReferenceExpression [IntellijIdeaRulezzz]
        ..................LeafPsiElement [IntellijIdeaRulezzz] (*) <= positionInCopy

        Replacing KtQualifiedExpression with its nested KtCallExpression we're getting "non-broken" tree.
        */

        val dotExprWithCaret =
            positionInCopy.parents.match(KtNameReferenceExpression::class, last = KtQualifiedExpression::class) ?: return null
        val dotExprWithCaretCopy = dotExprWithCaret.copy() as KtQualifiedExpression

        val beforeDotExpr = dotExprWithCaret.receiverExpression // smth.call()
        val dotExpressionWithoutCaret = dotExprWithCaret.replace(beforeDotExpr) as KtExpression // dotExprWithCaret = beforeDotExpr + '.[?]' + caret
        val targetCall = dotExpressionWithoutCaret.findLastCallExpression() ?: return null // call()

        return CallAndDiff(targetCall, dotExpressionWithoutCaret, dotExprWithCaretCopy)
    }

    context(_: KaSession)
    fun applyTypeArguments(callAndDiff: CallAndDiff): Pair<KtTypeArgumentList, PsiElement>? {
        val (callExpression, dotExprWithoutCaret, dotExprWithCaret) = callAndDiff

        // KtCallExpression [call()]
        val renderedTypes = getRenderedTypeArguments(callExpression) ?: return null
        addTypeArguments(callExpression, renderedTypes, callExpression.project)
        // KtCallExpression [call<TypeA, TypeB>()]

        val dotExprWithoutCaretCopy = dotExprWithoutCaret.copy() as KtExpression

        // Now we're restoring original smth.call().IntellijIdeaRulezzz on its place and
        // replace call() with call<TypeA, TypeB>().

        // smth.call() -> smth.call().IntellijIdeaRulezzz
        val originalDotExpr = dotExprWithoutCaret.replace(dotExprWithCaret) as KtQualifiedExpression
        val originalNestedDotExpr = originalDotExpr.receiverExpression // smth.call()
        originalNestedDotExpr.replace(dotExprWithoutCaretCopy) // smth.call() -> smth.call<TypeA, TYpeB>

        // IntellijIdeaRulezzz as before
        val newPosition = (originalDotExpr.selectorExpression as? KtNameReferenceExpression)?.getReferencedNameElement() ?: return null
        val newTypeArgumentList = callExpression.typeArgumentList ?: return null

        return newTypeArgumentList to newPosition
    }

    val fileCopy = position.containingFile.copy() as KtFile
    val positionInCopy = PsiTreeUtil.findSameElementInCopy(position, fileCopy)
    val callAndDiff = getCallWithParamTypesToAdd(positionInCopy) ?: return position
    val (callExpression, _, _) = callAndDiff
    analyze(fileCopy) {
        if (getRenderedTypeArguments(callExpression) == null) return position

        // We need to fix expression offset so that later 'typeArguments' could be inserted into the editor.
        // See usages of `argList` -> JustTypingLookupElementDecorator#handleInsert.

        val exprOffset = callExpression.endOffset // applyTypeArguments modifies PSI, offset is to be calculated before
        val (typeArguments, newPosition) = applyTypeArguments(callAndDiff) ?: return position

        return newPosition.also { it.argList = TypeArgsWithOffset(typeArguments, exprOffset) }
    }
}

private fun PsiElement.getCallBeforeDot(): KtCallExpression? {
    /*
     Case: call().IntellijIdeaRulezzz or call()?.IntellijIdeaRulezzz or smth.call()?.IntellijIdeaRulezzz
     'position' points to the caret - IntellijIdeaRulezzz and on PSI level it looks as follows:
     ............KtDotQualifiedExpression [call().IntellijIdeaRulezzz]
     ..............KtCallExpression [call()]
     .............................................................
     ..............KtNameReferenceExpression [IntellijIdeaRulezzz]
     ..................LeafPsiElement [IntellijIdeaRulezzz] (*)
     */
    val afterDotExprWithCaret = parent as? KtNameReferenceExpression ?: return null
    return afterDotExprWithCaret.getPreviousInQualifiedChain() as? KtCallExpression
}

private fun KaFunctionCall<*>.hasErrorTypeArgument(): Boolean {
    return typeArgumentsMapping.any { (_, type) -> type is KaErrorType }
}

private fun KaErrorCallInfo.hasCandidateWithErrorTypes(): Boolean {
    val candidates = candidateCalls.filterIsInstance<KaFunctionCall<*>>()
    return candidates.any { it.hasErrorTypeArgument() }
}

private fun KtExpression.getPreviousInQualifiedChain(): KtExpression? {
    val receiverExpression = getQualifiedExpressionForSelector()?.receiverExpression
    return (receiverExpression as? KtQualifiedExpression)?.selectorExpression ?: receiverExpression
}

private fun KtExpression.findLastCallExpression() =
    ((this as? KtQualifiedExpression)?.selectorExpression ?: this) as? KtCallExpression
