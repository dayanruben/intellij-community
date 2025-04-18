// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.refactoring

import com.intellij.CommonBundle
import com.intellij.ide.IdeBundle
import com.intellij.lang.findUsages.DescriptiveNameUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.NlsContexts
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.util.CommonRefactoringUtil
import org.jetbrains.kotlin.asJava.namedUnwrappedElement
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor.Kind.*
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.core.getDeepestSuperDeclarations
import org.jetbrains.kotlin.idea.core.getDirectlyOverriddenDeclarations
import org.jetbrains.kotlin.idea.search.declarationsSearch.forEachOverridingElement
import org.jetbrains.kotlin.idea.util.actualsForExpected
import org.jetbrains.kotlin.idea.util.application.isUnitTestMode
import org.jetbrains.kotlin.idea.util.getResolutionScope
import org.jetbrains.kotlin.idea.util.liftToExpected
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.utils.memberScopeAsImportingScope

abstract class CallableRefactoring<out T : CallableDescriptor>(
    val project: Project,
    val editor: Editor?,
    val callableDescriptor: T,
    @NlsContexts.Command val commandName: String
) {
    companion object  {
        private val LOG = Logger.getInstance(CallableRefactoring::class.java)
    }

    private val kind = (callableDescriptor as? CallableMemberDescriptor)?.kind ?: DECLARATION

    protected open fun forcePerformForSelectedFunctionOnly(): Boolean {
        return false
    }

    private fun getClosestModifiableDescriptors(): Collection<CallableDescriptor> {
        return when (kind) {
            DECLARATION -> {
                setOf(callableDescriptor)
            }
            DELEGATION, FAKE_OVERRIDE -> {
                (callableDescriptor as CallableMemberDescriptor).getDirectlyOverriddenDeclarations()
            }
            else -> {
                throw IllegalStateException("Unexpected callable kind: $kind")
            }
        }.map { it.liftToExpected() as? CallableDescriptor ?: it }
    }

    private fun showSuperFunctionWarningDialog(
        superCallables: Collection<CallableDescriptor>,
        callableFromEditor: CallableDescriptor,
        options: List<String>
    ): Int {
        val superString = superCallables.joinToString(prefix = "\n    ", separator = ",\n    ", postfix = ".\n\n") {
            it.containingDeclaration.name.asString()
        }
        val message = KotlinBundle.message(
            "override.declaration.x.overrides.y.in.class.list",
            DescriptorRenderer.COMPACT.render(callableFromEditor),
            superString,
            "refactor"
        )
        val title = IdeBundle.message("title.warning")!!
        val icon = Messages.getQuestionIcon()
        return Messages.showDialog(message, title, options.toTypedArray(), 0, icon)
    }

    protected fun checkModifiable(element: PsiElement): Boolean {
        if (element.canRefactor()) {
            return true
        }

        val unmodifiableFileName = element.containingFile?.name
        if (unmodifiableFileName != null) {
            val message = RefactoringBundle.message("refactoring.cannot.be.performed") + "\n" +
                    KotlinBundle.message(
                        "error.hint.cannot.modify.0.declaration.from.1.file",
                        DescriptiveNameUtil.getDescriptiveName(element),
                        unmodifiableFileName,
                    )

            CommonRefactoringUtil.showErrorHint(project, editor, message, CommonBundle.getErrorTitle(), null)
        } else {
            LOG.error("Could not find file for Psi element: " + element.text)
        }

        return false
    }

    protected abstract fun performRefactoring(descriptorsForChange: Collection<CallableDescriptor>)

    fun run(): Boolean {
        if (kind == SYNTHESIZED) {
            LOG.error("Change signature refactoring should not be called for synthesized member $callableDescriptor")
            return false
        }

        val closestModifiableDescriptors = getClosestModifiableDescriptors()
        if (forcePerformForSelectedFunctionOnly()) {
            performRefactoring(closestModifiableDescriptors)
            return true
        }

        assert(!closestModifiableDescriptors.isEmpty()) { "Should contain original declaration or some of its super declarations" }
        val deepestSuperDeclarations =
            (callableDescriptor as? CallableMemberDescriptor)?.getDeepestSuperDeclarations()
                ?: listOf(callableDescriptor)
        if (isUnitTestMode()) {
            performRefactoring(deepestSuperDeclarations)
            return true
        }

        if (closestModifiableDescriptors.size == 1 && deepestSuperDeclarations.subtract(closestModifiableDescriptors).isEmpty()) {
            performRefactoring(closestModifiableDescriptors)
            return true
        }

        fun buttonPressed(code: Int, dialogButtons: List<String>, button: String): Boolean {
            return code == dialogButtons.indexOf(button) && button in dialogButtons
        }

        fun performForWholeHierarchy(dialogButtons: List<String>, code: Int): Boolean {
            return buttonPressed(code, dialogButtons, Messages.getYesButton()) || buttonPressed(code, dialogButtons, Messages.getOkButton())
        }

        fun performForSelectedFunctionOnly(dialogButtons: List<String>, code: Int): Boolean {
            return buttonPressed(code, dialogButtons, Messages.getNoButton())
        }

        fun buildDialogOptions(isSingleFunctionSelected: Boolean): List<String> {
            return if (isSingleFunctionSelected) {
                arrayListOf(Messages.getYesButton(), Messages.getNoButton(), Messages.getCancelButton())
            } else {
                arrayListOf(Messages.getOkButton(), Messages.getCancelButton())
            }
        }

        val isSingleFunctionSelected = closestModifiableDescriptors.size == 1
        val selectedFunction = if (isSingleFunctionSelected) closestModifiableDescriptors.first() else callableDescriptor
        val optionsForDialog = buildDialogOptions(isSingleFunctionSelected)
        val code = showSuperFunctionWarningDialog(deepestSuperDeclarations, selectedFunction, optionsForDialog)
        when {
            performForWholeHierarchy(optionsForDialog, code) -> {
                performRefactoring(deepestSuperDeclarations)
            }
            performForSelectedFunctionOnly(optionsForDialog, code) -> {
                performRefactoring(closestModifiableDescriptors)
            }
            else -> {
                //do nothing
                return false
            }
        }
        return true
    }
}

fun getAffectedCallables(project: Project, descriptorsForChange: Collection<CallableDescriptor>): Collection<PsiElement> {
    val results = hashSetOf<PsiElement>()
    for (descriptor in descriptorsForChange) {
        val declaration = DescriptorToSourceUtilsIde.getAnyDeclaration(project, descriptor) ?: continue
        collectAffectedCallables(declaration, results)
    }

    return results
}

private fun collectAffectedCallables(declaration: PsiElement, results: MutableCollection<PsiElement>) {
    if (!results.add(declaration)) return
    if (declaration is KtDeclaration) {
        for (it in declaration.actualsForExpected()) {
            collectAffectedCallables(it, results)
        }

        declaration.liftToExpected()?.let { collectAffectedCallables(it, results) }

        if (declaration !is KtCallableDeclaration) return
        declaration.forEachOverridingElement { _, overridingElement ->
            results += overridingElement.namedUnwrappedElement ?: overridingElement
            true
        }
    } else {
        for (psiMethod in declaration.toLightMethods()) {
            OverridingMethodsSearch.search(psiMethod).asIterable().forEach {
                results += it.namedUnwrappedElement ?: it
            }
        }
    }
}

fun DeclarationDescriptor.getContainingScope(): LexicalScope? {
    val declaration = DescriptorToSourceUtils.descriptorToDeclaration(this)
    val block = declaration?.parent as? KtBlockExpression
    return if (block != null) {
        val lastStatement = block.statements.last()
        val bindingContext = lastStatement.analyze()
        lastStatement.getResolutionScope(bindingContext, lastStatement.getResolutionFacade())
    } else {
        when (val containingDescriptor = containingDeclaration ?: return null) {
            is ClassDescriptorWithResolutionScopes -> containingDescriptor.scopeForInitializerResolution
            is PackageFragmentDescriptor -> LexicalScope.Base(containingDescriptor.getMemberScope().memberScopeAsImportingScope(), this)
            else -> null
        }
    }
}

fun KtDeclarationWithBody.getBodyScope(bindingContext: BindingContext): LexicalScope? {
    val expression = bodyExpression?.children?.firstOrNull { it is KtExpression } ?: return null
    return expression.getResolutionScope(bindingContext, getResolutionFacade())
}
