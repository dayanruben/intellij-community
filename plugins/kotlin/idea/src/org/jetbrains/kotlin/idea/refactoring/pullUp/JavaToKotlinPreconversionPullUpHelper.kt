// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.refactoring.pullUp

import com.intellij.openapi.util.Key
import com.intellij.psi.*
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.encapsulateFields.*
import com.intellij.refactoring.memberPullUp.JavaPullUpHelper
import com.intellij.refactoring.memberPullUp.PullUpData
import com.intellij.refactoring.memberPullUp.PullUpHelper
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.refactoring.util.RefactoringUtil
import com.intellij.refactoring.util.classMembers.MemberInfo
import com.intellij.util.VisibilityUtil
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.idea.base.psi.getOrCreateCompanionObject
import org.jetbrains.kotlin.idea.codeInsight.shorten.addToShorteningWaitSet
import org.jetbrains.kotlin.idea.core.setVisibility
import org.jetbrains.kotlin.idea.j2k.j2k
import org.jetbrains.kotlin.idea.j2k.j2kText
import org.jetbrains.kotlin.idea.refactoring.removeOverrideModifier
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.psi.*

class JavaToKotlinPreconversionPullUpHelper(
    private val data: PullUpData,
    private val dummyTargetClass: PsiClass,
    private val javaHelper: JavaPullUpHelper
) : PullUpHelper<MemberInfo> by javaHelper {
    private val membersToDummyDeclarations = HashMap<PsiMember, KtElement>()

    private val encapsulateFieldsDescriptor = object : EncapsulateFieldsDescriptor {
        override fun getSelectedFields(): Array<out FieldDescriptor> = arrayOf()
        override fun isToEncapsulateGet() = true
        override fun isToEncapsulateSet() = true
        override fun isToUseAccessorsWhenAccessible() = true
        override fun getFieldsVisibility() = null
        override fun getAccessorsVisibility() = PsiModifier.PUBLIC
        override fun getTargetClass() = dummyTargetClass
        override fun getJavadocPolicy() = DocCommentPolicy.ASIS
    }

    private val fieldsToUsages = HashMap<PsiField, List<EncapsulateFieldUsageInfo>>()
    private val dummyAccessorByName = HashMap<String, PsiMethod>()

    private val jvmStaticAnnotation = KtPsiFactory(data.sourceClass.project).createAnnotationEntry("@kotlin.jvm.JvmStatic")

    companion object {
        private var PsiMember.originalMember: PsiMember? by CopyablePsiUserDataProperty(Key.create("ORIGINAL_MEMBER"))
    }

    private fun collectFieldReferencesToEncapsulate(member: PsiField) {
        val helper = EncapsulateFieldHelper.getHelper(member.language) ?: return
        val fieldName = member.name
        val getterName = JvmAbi.getterName(fieldName)
        val setterName = JvmAbi.setterName(fieldName)
        val getter = helper.generateMethodPrototype(member, getterName, true)
        val setter = helper.generateMethodPrototype(member, setterName, false)
        val fieldDescriptor = FieldDescriptorImpl(member, getterName, setterName, getter, setter)
        getter?.let { dummyAccessorByName[getterName] = dummyTargetClass.add(it) as PsiMethod }
        setter?.let { dummyAccessorByName[setterName] = dummyTargetClass.add(it) as PsiMethod }
        fieldsToUsages[member] =
            ReferencesSearch.search(member).asIterable().mapNotNull { helper.createUsage(encapsulateFieldsDescriptor, fieldDescriptor, it) }
    }

    override fun move(info: MemberInfo, substitutor: PsiSubstitutor) {
        val member = info.member
        val movingSuperInterface = member is PsiClass && info.overrides == false

        if (!movingSuperInterface) {
            member.originalMember = member
        }

        if (info.isStatic) {
            info.isToAbstract = false
        }

        if (member is PsiField && !info.isStatic) {
            collectFieldReferencesToEncapsulate(member)
        }

        val superInterfaceCount = getCurrentSuperInterfaceCount()

        val adjustedSubstitutor = substitutor.substitutionMap.entries.fold(substitutor) { subst, (typeParameter, type) ->
            if (type == null) {
                val substitutedUpperBound = substitutor.substitute(PsiIntersectionType.createIntersection(*typeParameter.superTypes))
                subst.put(typeParameter, substitutedUpperBound)
            } else
                subst
        }

        javaHelper.move(info, adjustedSubstitutor)

        if (info.isStatic) {
            member.removeOverrideModifier()
        }

        val targetClass = data.targetClass.unwrapped as KtClass
        if (member.hasModifierProperty(PsiModifier.ABSTRACT) && !movingSuperInterface) targetClass.makeAbstract()

        val psiFactory = KtPsiFactory(member.project)

        if (movingSuperInterface) {
            if (getCurrentSuperInterfaceCount() == superInterfaceCount) return

            val typeText = RefactoringUtil.findReferenceToClass(dummyTargetClass.implementsList, member as PsiClass)?.j2kText() ?: return
            targetClass.addSuperTypeListEntry(psiFactory.createSuperTypeEntry(typeText))
            return
        }

        val memberOwner = when {
            member.hasModifierProperty(PsiModifier.STATIC) && member !is PsiClass -> targetClass.getOrCreateCompanionObject()
            else -> targetClass
        }
        val dummyDeclaration: KtNamedDeclaration = when (member) {
            is PsiField -> psiFactory.createProperty("val foo = 0")
            is PsiMethod -> psiFactory.createFunction("fun foo() = 0")
            is PsiClass -> psiFactory.createClass("class Foo")
            else -> return
        }
        // postProcessMember() call order is unstable so in order to stabilize resulting member order we add dummies to target class
        // and replace them after postprocessing
        membersToDummyDeclarations[member] = addMemberToTarget(dummyDeclaration, memberOwner)
    }

    private fun getCurrentSuperInterfaceCount() = dummyTargetClass.implementsList?.referenceElements?.size ?: 0

    override fun postProcessMember(member: PsiMember) {
        javaHelper.postProcessMember(member)

        val originalMember = member.originalMember ?: return
        originalMember.originalMember = null

        val targetClass = data.targetClass.unwrapped as? KtClass ?: return
        val convertedDeclaration = member.j2k() ?: return
        if (member is PsiField || member is PsiMethod) {
            val visibilityModifier = VisibilityUtil.getVisibilityModifier(member.modifierList)
            if (visibilityModifier == PsiModifier.PROTECTED || visibilityModifier == PsiModifier.PACKAGE_LOCAL) {
                convertedDeclaration.setVisibility(KtTokens.PUBLIC_KEYWORD)
            }
        }
        val newDeclaration = membersToDummyDeclarations[originalMember]?.replace(convertedDeclaration) as KtNamedDeclaration
        if (targetClass.isInterface()) {
            newDeclaration.removeModifier(KtTokens.ABSTRACT_KEYWORD)
        }
        if (member.hasModifierProperty(PsiModifier.STATIC) && newDeclaration is KtNamedFunction) {
            newDeclaration.addAnnotationWithSpace(jvmStaticAnnotation).addToShorteningWaitSet()
        }

        if (originalMember is PsiField) {
            val usages = fieldsToUsages[originalMember] ?: return
            for (usage in usages) {
                val fieldDescriptor = usage.fieldDescriptor
                val targetLightClass = (usage.reference?.resolve() as? PsiField)?.containingClass ?: return
                val getter = targetLightClass.findMethodBySignature(fieldDescriptor.getterPrototype!!, false)
                val setter = targetLightClass.findMethodBySignature(fieldDescriptor.setterPrototype!!, false)

                EncapsulateFieldHelper.getHelper(usage.element!!.language)?.processUsage(usage, encapsulateFieldsDescriptor, setter, getter)
            }
        }
    }
}