// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.completion

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CodeCompletionFeatures.EXCLAMATION_FINISH
import com.intellij.codeInsight.completion.JavaMethodCallInsertHandler.Companion.findCallAtOffset
import com.intellij.codeInsight.completion.JavaMethodCallInsertHandler.Companion.findInsertedCall
import com.intellij.codeInsight.completion.JavaMethodCallInsertHandler.Companion.showParameterHints
import com.intellij.codeInsight.completion.util.MethodParenthesesHandler
import com.intellij.codeInsight.hint.ParameterInfoControllerBase
import com.intellij.codeInsight.hint.ShowParameterInfoContext
import com.intellij.codeInsight.hint.api.impls.MethodParameterInfoHandler
import com.intellij.codeInsight.hints.ParameterHintsPass
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.featureStatistics.FeatureUsageTracker
import com.intellij.injected.editor.EditorWindow
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.*
import com.intellij.psi.impl.PsiImplUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ThreeState
import kotlin.math.min

public class JavaMethodCallInsertHandler(
  needExplicitTypeParameters: Boolean,

  /**
   * Called before insertion methods. Performs any necessary pre-processing or setup.
   */
  beforeHandler: InsertHandler<JavaMethodCallElement>? = null,

  /**
   * Called after insertion methods. Performs any necessary post-processing or cleanup.
   *
   * Use [findInsertedCall] to get PsiCallExpression representing the inserted code, or null if no code was inserted
   */
  afterHandler: InsertHandler<JavaMethodCallElement>? = null,

  /**
   * Determines if import or qualification is needed.
   *
   * @return true if import or qualification is needed, false otherwise
   */
  needImportOrQualify: Boolean = true,

  /**
   * Checks if the argument live template can be started.
   * see registry key java.completion.argument.live.template.description.
   * This option allows to prevent running templates if this key is enabled
   *
   * true if the argument live template can be started, otherwise false.
   */
  canStartArgumentLiveTemplate: Boolean = true,
) : InsertHandler<JavaMethodCallElement> {

  /**
   * tracks the start offset of the reference, needs movableToRight=true to correctly handle insertion of a qualifier
   */
  private val myHandlers: List<InsertHandler<in JavaMethodCallElement>> = listOfNotNull(
    RefStartInsertHandler(),
    DiamondInsertHandler(),
    ParenthInsertHandler(),
    beforeHandler,
    ImportQualifyAndInsertTypeParametersHandler(needImportOrQualify, needExplicitTypeParameters),
    MethodCallInstallerHandler(),
    MethodCallRegistrationHandler(),
    NegationInsertHandler(),
    afterHandler,
    ArgumentLiveTemplateInsertHandler(canStartArgumentLiveTemplate),
    ShowParameterInfoInsertHandler(),
  )

  override fun handleInsert(context: InsertionContext, item: JavaMethodCallElement) {
    myHandlers.forEach { it.handleInsert(context, item) }
  }

  public companion object {
    @JvmStatic
    public fun getReferenceStartOffset(context: InsertionContext, item: LookupElement): Int {
      val refStartKey = requireNotNull(item.getUserData(refStartKey)) { "refStartKey must have been set" }
      return context.offsetMap.getOffset(refStartKey)
    }

    @JvmStatic
    public fun findCallAtOffset(context: InsertionContext, offset: Int): PsiCallExpression? {
      context.commitDocument()
      return PsiTreeUtil.findElementOfClassAtOffset(context.file, offset, PsiCallExpression::class.java, false)
    }

    @JvmStatic
    public fun showParameterHints(
      element: LookupElement,
      context: InsertionContext,
      method: PsiMethod,
      methodCall: PsiCallExpression?,
    ) {
      if (!CodeInsightSettings.getInstance().SHOW_PARAMETER_NAME_HINTS_ON_COMPLETION ||
          context.completionChar == Lookup.COMPLETE_STATEMENT_SELECT_CHAR ||
          context.completionChar == Lookup.REPLACE_SELECT_CHAR ||
          methodCall == null ||
          methodCall.containingFile is PsiCodeFragment ||
          element.getUserData(JavaMethodMergingContributor.MERGED_ELEMENT) != null
      ) {
        return
      }
      val parameterList = method.parameterList
      val parametersCount = parameterList.parametersCount
      val parameterOwner = methodCall.argumentList
      if ((parameterOwner == null) || (parameterOwner.getText() != "()") || (parametersCount == 0)) {
        return
      }

      val editor = context.editor
      if (editor is EditorWindow) return

      val project = context.project
      val document = editor.getDocument()
      PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document)

      val limit = JavaMethodCallElement.getCompletionHintsLimit()

      val caretModel = editor.getCaretModel()
      val offset = caretModel.offset

      val afterParenOffset = offset + 1
      if (afterParenOffset < document.textLength && Character.isJavaIdentifierPart(document.getImmutableCharSequence()[afterParenOffset])) {
        return
      }

      val braceOffset = offset - 1
      val numberOfParametersToDisplay = if (parametersCount > 1 && PsiImplUtil.isVarArgs(method)) parametersCount - 1 else parametersCount
      val numberOfCommas = min(numberOfParametersToDisplay, limit) - 1
      val commas = if (Registry.`is`("editor.completion.hints.virtual.comma")) "" else StringUtil.repeat(", ", numberOfCommas)
      document.insertString(offset, commas)

      PsiDocumentManager.getInstance(project).commitDocument(document)
      val handler = MethodParameterInfoHandler()
      val infoContext = ShowParameterInfoContext(editor, project, context.file, offset, braceOffset)
      if (!methodCall.isValid() || handler.findElementForParameterInfo(infoContext) == null) {
        document.deleteString(offset, offset + commas.length)
        return
      }

      JavaMethodCallElement.setCompletionMode(methodCall, true)
      context.laterRunnable = Runnable {
        val itemsToShow = infoContext.itemsToShow
        val methodCallArgumentList = methodCall.getArgumentList()
        val controller =
          ParameterInfoControllerBase.createParameterInfoController(project, editor, braceOffset, itemsToShow, null,
                                                                    methodCallArgumentList, handler, false, false)
        val hintsDisposal = Disposable { JavaMethodCallElement.setCompletionMode(methodCall, false) }
        if (Disposer.isDisposed(controller)) {
          Disposer.dispose(hintsDisposal)
          document.deleteString(offset, offset + commas.length)
        }
        else {
          ParameterHintsPass.asyncUpdate(methodCall, editor)
          Disposer.register(controller, hintsDisposal)
        }
      }
    }

    /**
     * Use [findInsertedCall] to get PsiCallExpression representing the inserted code, or null if no code was inserted
     * Can be called in [afterHandler]
     */
    @JvmStatic
    public fun findInsertedCall(element: LookupElement, context: InsertionContext): PsiCallExpression? {
      return element.getUserData(callKey)
    }
  }
}

private class RefStartInsertHandler : InsertHandler<LookupElement> {
  override fun handleInsert(context: InsertionContext, item: LookupElement) {
    val refStart = OffsetKey.create("refStart", true)
    context.offsetMap.addOffset(refStart, context.startOffset)
    item.putUserData(refStartKey, refStart)
  }
}

private class DiamondInsertHandler : InsertHandler<JavaMethodCallElement> {
  override fun handleInsert(context: InsertionContext, item: JavaMethodCallElement) {
    val method = item.getObject().takeIf { it.isConstructor } ?: return
    if (method.containingClass?.typeParameters?.isNotEmpty() != true) return
    context.document.insertString(context.tailOffset, "<>")
  }
}

private class ParenthInsertHandler : InsertHandler<JavaMethodCallElement> {
  override fun handleInsert(context: InsertionContext, item: JavaMethodCallElement) {
    val method = item.getObject()
    val allItems = context.elements
    val hasParams = if (method.parameterList.isEmpty) ThreeState.NO else MethodParenthesesHandler.overloadsHaveParameters(allItems, method)
    JavaCompletionUtil.insertParentheses(context, item, false, hasParams, false)
  }
}

private class ImportQualifyAndInsertTypeParametersHandler(
  private val needImportOrQualify: Boolean,
  private val needExplicitTypeParameters: Boolean,
) : InsertHandler<JavaMethodCallElement> {
  override fun handleInsert(context: InsertionContext, item: JavaMethodCallElement) {
    val document = context.document
    val file = context.file
    val method = item.getObject()

    if (needExplicitTypeParameters) {
      qualifyMethodCall(item, file, JavaMethodCallInsertHandler.getReferenceStartOffset(context, item), document)
      insertExplicitTypeParameters(item, context)
    }
    else if (item.helper != null) {
      context.commitDocument()
      importOrQualify(item, document, file, method, JavaMethodCallInsertHandler.getReferenceStartOffset(context, item))
    }
  }

  private fun importOrQualify(
    item: JavaMethodCallElement,
    document: Document,
    file: PsiFile,
    method: PsiMethod,
    startOffset: Int,
  ) {
    if (!needImportOrQualify) {
      return
    }
    if (item.willBeImported()) {
      val containingClass = item.containingClass
      if (method.isConstructor()) {
        val newExpression = PsiTreeUtil.findElementOfClassAtOffset(file, startOffset, PsiNewExpression::class.java, false)
        if (newExpression != null) {
          val ref = newExpression.classReference
          if (ref != null && containingClass != null && !ref.isReferenceTo(containingClass)) {
            ref.bindToElement(containingClass)
            return
          }
        }
      }
      else {
        val ref = PsiTreeUtil.findElementOfClassAtOffset(file, startOffset, PsiReferenceExpression::class.java, false)
        if (ref != null && containingClass != null && !ref.isReferenceTo(method)) {
          ref.bindToElementViaStaticImport(containingClass)
        }
        return
      }
    }

    qualifyMethodCall(item, file, startOffset, document)
  }

  private fun qualifyMethodCall(item: JavaMethodCallElement, file: PsiFile, startOffset: Int, document: Document) {
    val reference = file.findReferenceAt(startOffset)
    if (reference is PsiReferenceExpression && reference.isQualified()) {
      return
    }

    val method = item.getObject()
    if (method.isConstructor()) return
    if (!method.hasModifierProperty(PsiModifier.STATIC)) {
      document.insertString(startOffset, "this.")
      return
    }

    val containingClass = item.containingClass ?: return

    document.insertString(startOffset, ".")
    JavaCompletionUtil.insertClassReference(containingClass, file, startOffset)
  }

  private fun insertExplicitTypeParameters(
    item: JavaMethodCallElement,
    context: InsertionContext,
  ) {
    context.commitDocument()

    val typeParams = JavaMethodCallElement.getTypeParamsText(false, item.getObject(), item.inferenceSubstitutor)
    if (typeParams != null) {
      context.document.insertString(JavaMethodCallInsertHandler.getReferenceStartOffset(context, item), typeParams)
      JavaCompletionUtil.shortenReference(context.file, JavaMethodCallInsertHandler.getReferenceStartOffset(context, item))
    }
  }
}

private class NegationInsertHandler : InsertHandler<JavaMethodCallElement> {
  override fun handleInsert(context: InsertionContext, item: JavaMethodCallElement) {
    if (context.completionChar != '!' || !item.isNegatable) return
    val methodCall = findInsertedCall(item, context) ?: return
    context.setAddCompletionChar(false)
    FeatureUsageTracker.getInstance().triggerFeatureUsed(EXCLAMATION_FINISH)
    context.document.insertString(methodCall.textRange.startOffset, "!")
  }
}

private class ArgumentLiveTemplateInsertHandler(
  private val canStartArgumentLiveTemplate: Boolean,
) : InsertHandler<JavaMethodCallElement> {
  override fun handleInsert(context: InsertionContext, item: JavaMethodCallElement) {
    if (!canStartArgumentLiveTemplate) return
    val method = item.getObject()
    JavaMethodCallElement.startArgumentLiveTemplate(context, method)
  }
}

private class ShowParameterInfoInsertHandler : InsertHandler<JavaMethodCallElement> {
  override fun handleInsert(context: InsertionContext, item: JavaMethodCallElement) {
    val method = item.getObject()
    val methodCall = findInsertedCall(item, context)
    showParameterHints(item, context, method, methodCall)
  }
}

private class MethodCallInstallerHandler : InsertHandler<LookupElement> {
  override fun handleInsert(context: InsertionContext, item: LookupElement) {
    val methodCall = findCallAtOffset(context, JavaMethodCallInsertHandler.getReferenceStartOffset(context, item)) ?: return

    // make sure this is the method call we've just added, not the enclosing one
    val completedElement = (methodCall as? PsiMethodCallExpression)?.methodExpression?.referenceNameElement
    val completedElementRange = completedElement?.textRange ?: return
    if (completedElementRange.startOffset != JavaMethodCallInsertHandler.getReferenceStartOffset(context, item)) return

    item.putUserData(callKey, methodCall)
  }
}

private class MethodCallRegistrationHandler : InsertHandler<JavaMethodCallElement> {
  override fun handleInsert(context: InsertionContext, item: JavaMethodCallElement) {
    val method = item.getObject()
    val methodCall = findInsertedCall(item, context) ?: return
    CompletionMemory.registerChosenMethod(method, methodCall)
  }
}

private val callKey = Key.create<PsiCallExpression>("JavaMethodCallInsertHandler.call")
private val refStartKey = Key.create<OffsetKey>("JavaMethodCallInsertHandler.refStart")

