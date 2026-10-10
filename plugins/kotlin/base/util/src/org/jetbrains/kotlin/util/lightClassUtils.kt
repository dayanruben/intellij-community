// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.util

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.javaInterop.asPsiMethods
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.asJava.KotlinAsJavaSupport
import org.jetbrains.kotlin.asJava.classes.KtFakeLightClass
import org.jetbrains.kotlin.builtins.jvm.JavaToKotlinClassMap
import org.jetbrains.kotlin.psi.KtClassOrObject

fun KtClassOrObject.toLightClassWithBuiltinMapping(): PsiClass? {
    // Don't replace with `analyze(this) { classSymbol?.asPsiClass() }`: it views the class from the declaration's own module
    // and returns `null` for non-JVM (e.g., common) ones, so inheritor searches fall back to slow fake light classes (KTIJ-40299).
    // `KotlinAsJavaSupport.getLightClass` uses a dependent JVM module as context instead.
    KotlinAsJavaSupport.getInstance(project).getLightClass(this)?.let { return it }

    val fqName = fqName ?: return null
    val javaClassFqName = JavaToKotlinClassMap.mapKotlinToJava(fqName.toUnsafe())?.asSingleFqName() ?: return null
    val searchScope = useScope as? GlobalSearchScope ?: return null
    return JavaPsiFacade.getInstance(project).findClass(javaClassFqName.asString(), searchScope)
}

fun KtClassOrObject.asFakePsiClass(): KtFakeLightClass {
    return KotlinAsJavaSupport.getInstance(project).getFakeLightClass(this)
}

context(_: KaSession)
fun KaCallableSymbol.getPsiMethods(): List<PsiMethod> =
    when (this) {
        is KaFunctionSymbol -> asPsiMethods()
        is KaPropertySymbol -> listOfNotNull(getter?.asPsiMethods(), setter?.asPsiMethods()).flatten()
        else -> emptyList()
    }
