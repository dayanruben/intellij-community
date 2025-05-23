// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.refactoring

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.lang.ElementsHandler
import com.intellij.refactoring.util.CommonRefactoringUtil
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import org.jetbrains.kotlin.idea.base.psi.isConstructorDeclaredProperty
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

@ApiStatus.Internal
abstract class AbstractPullPushMembersHandler(
    @Nls private val refactoringName: String,
    private val helpId: String,
    @NlsContexts.DialogMessage private val wrongPositionMessage: String
) : RefactoringActionHandler, ElementsHandler {
    private fun reportWrongPosition(project: Project, editor: Editor?) {
        val message = RefactoringBundle.getCannotRefactorMessage(wrongPositionMessage)
        CommonRefactoringUtil.showErrorHint(project, editor, message, refactoringName, helpId)
    }

    private fun KtParameter.getContainingClass() =
        if (hasValOrVar()) (ownerFunction as? KtPrimaryConstructor)?.containingClassOrObject else null

    protected fun reportWrongContext(project: Project, editor: Editor?) {
        val message = RefactoringBundle.getCannotRefactorMessage(
            RefactoringBundle.message("is.not.supported.in.the.current.context", refactoringName)
        )
        CommonRefactoringUtil.showErrorHint(project, editor, message, refactoringName, helpId)
    }

    protected abstract operator fun invoke(
        project: Project,
        editor: Editor?,
        classOrObject: KtClassOrObject?,
        member: KtNamedDeclaration?,
        dataContext: DataContext?
    )

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        val offset = editor.caretModel.offset
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)

        val target = (file.findElementAt(offset) ?: return).parentsWithSelf.firstOrNull {
            it is KtClassOrObject
                    || ((it is KtNamedFunction || it is KtProperty) && it.parent is KtClassBody)
                    || it.safeAs<KtParameter>()?.isConstructorDeclaredProperty() == true
        }

        if (target == null) {
            reportWrongPosition(project, editor)
            return
        }
        if (!target.canRefactorElement()) return

        invoke(project, arrayOf(target), dataContext)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        val element = elements.singleOrNull() ?: return

        val editor = dataContext?.let { CommonDataKeys.EDITOR.getData(it) }

        val (classOrObject, member) = when (element) {
            is KtNamedFunction, is KtProperty -> element.getStrictParentOfType<KtClassOrObject>() to element as KtNamedDeclaration?
            is KtParameter -> element.getContainingClass() to element
            is KtClassOrObject -> element to null
            else -> {
                reportWrongPosition(project, editor)
                return
            }
        }

        invoke(project, editor, classOrObject, member, dataContext)
    }

    override fun isEnabledOnElements(elements: Array<out PsiElement>): Boolean {
        return elements.mapTo(HashSet<PsiElement>()) {
            when (it) {
                is KtNamedFunction, is KtProperty -> (it.parent as? KtClassBody)?.parent as? KtClassOrObject
                is KtParameter -> it.getContainingClass()
                is KtClassOrObject -> it
                else -> null
            } ?: return false
        }.size == 1
    }
}
