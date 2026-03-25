// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.base.fir.analysisApiPlatform.modificationEvents

import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.vfs.writeText
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationEventKind
import org.jetbrains.kotlin.idea.test.addDependency
import org.jetbrains.kotlin.idea.test.addEmptyClassesRoot
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.junit.Assert

/**
 * [FirIdeModuleStateModificationService][org.jetbrains.kotlin.idea.base.fir.analysisApiPlatform.FirIdeModuleStateModificationService] has
 * limits for the number of modification events it publishes (per write action). This test verifies these limitations.
 */
class KotlinEventLimitGlobalModuleStateModificationTest : AbstractKotlinModificationEventTest() {
    fun `test event limits when changing multiple files with no document`() {
        val moduleA = createModuleInTmpDir("a") {
            listOf(
                FileWithText("a.kt", "fun foo() = 1"),
                FileWithText("b.kt", "fun bar() = 2"),
                FileWithText("c.kt", "fun baz() = 3"),
                FileWithText("d.kt", "fun qux() = 4"),
            )
        }

        val fileA = moduleA.findSourceVirtualFile("a.kt")
        val fileB = moduleA.findSourceVirtualFile("b.kt")
        val fileC = moduleA.findSourceVirtualFile("c.kt")
        val fileD = moduleA.findSourceVirtualFile("d.kt")

        val globalTracker = createGlobalModuleStateTracker(
            // `FirIdeModuleStateModificationService` might publish a global event due to the changed file content.
            additionalAllowedEventKinds = setOf(KotlinModificationEventKind.GLOBAL_SOURCE_OUT_OF_BLOCK_MODIFICATION),
        )

        // We have to run in a single write action because otherwise event limits will be reset.
        runUndoTransparentWriteAction {
            fileA.writeText("fun foo() = 10")
            globalTracker.assertModifiedOnce()

            fileB.writeText("fun bar() = 20")
            globalTracker.assertModifiedOnce()

            fileC.writeText("fun baz() = 30")
            globalTracker.assertModifiedOnce()
        }

        // Ensure that event limits are reset in a new write action.
        runUndoTransparentWriteAction {
            fileD.writeText("fun qux() = 40")
            globalTracker.assertModifiedExactly(times = 2)
        }
    }

    @OptIn(KaAllowAnalysisFromWriteAction::class, KaAllowAnalysisOnEdt::class)
    fun `test write-action analysis resets event limits when changing multiple files with no document`() {
        val moduleA = createModuleInTmpDir("a") {
            listOf(
                FileWithText("a.kt", "fun foo() = 1"),
                FileWithText("b.kt", "fun bar() = 2"),
                FileWithText("c.kt", "fun baz() = 3"),
                FileWithText("d.kt", "fun qux() = 4"),
            )
        }

        val fileA = moduleA.findSourceVirtualFile("a.kt")
        val fileB = moduleA.findSourceVirtualFile("b.kt")
        val fileC = moduleA.findSourceVirtualFile("c.kt")
        val fileD = moduleA.findSourceVirtualFile("d.kt")

        val globalTracker = createGlobalModuleStateTracker(
            // `FirIdeModuleStateModificationService` might publish a global event due to the changed file content.
            additionalAllowedEventKinds = setOf(KotlinModificationEventKind.GLOBAL_SOURCE_OUT_OF_BLOCK_MODIFICATION),
        )

        runUndoTransparentWriteAction {
            fileA.writeText("fun foo() = 10")
            globalTracker.assertModifiedOnce()

            fileB.writeText("fun bar() = 20")
            globalTracker.assertModifiedOnce()

            fileC.writeText("fun baz() = 30")
            globalTracker.assertModifiedOnce()

            // `analyze` should reset the event publishing limits as it might populate caches.
            allowAnalysisOnEdt {
                allowAnalysisFromWriteAction {
                    val ktFileA = moduleA.findSourceKtFile("a.kt")
                    analyze(ktFileA) {
                        Assert.assertEquals(builtinTypes.int, ktFileA.firstTopLevelFunction.returnType)
                    }
                }
            }

            // After the reset, a new event should be published.
            fileD.writeText("fun qux() = 40")
            globalTracker.assertModifiedExactly(times = 2)
        }
    }

    fun `test event limits when adding several source module dependencies`() {
        val moduleA = createModuleInTmpDir("a")
        val moduleB = createModuleInTmpDir("b")
        val moduleC = createModuleInTmpDir("c")
        val moduleD = createModuleInTmpDir("d")

        val globalTracker = createGlobalSourceModuleStateTracker()

        // There are currently no event limits for module changes through the workspace model, so we expect multiple modification events.
        runUndoTransparentWriteAction {
            moduleA.addDependency(moduleB)
            globalTracker.assertModifiedOnce()

            moduleA.addDependency(moduleC)
            globalTracker.assertModifiedExactly(times = 2)

            moduleA.addDependency(moduleD)
            globalTracker.assertModifiedExactly(times = 3)
        }
    }

    @OptIn(KaAllowAnalysisFromWriteAction::class, KaAllowAnalysisOnEdt::class)
    fun `test write-action analysis resets event limits when adding several source module dependencies`() {
        val moduleA = createModuleInTmpDir("a") {
            listOf(FileWithText("a.kt", "fun foo() = 10"))
        }
        val moduleB = createModuleInTmpDir("b")
        val moduleC = createModuleInTmpDir("c")
        val moduleD = createModuleInTmpDir("d")

        val fileA = moduleA.findSourceKtFile("a.kt")
        val globalTracker = createGlobalSourceModuleStateTracker()

        // There are currently no event limits for module changes through the workspace model, so we expect multiple modification events.
        runUndoTransparentWriteAction {
            moduleA.addDependency(moduleB)
            globalTracker.assertModifiedOnce()

            moduleA.addDependency(moduleC)
            globalTracker.assertModifiedExactly(times = 2)

            // `analyze` should reset the event publishing limits as it might populate caches.
            allowAnalysisOnEdt {
                allowAnalysisFromWriteAction {
                    analyze(fileA) {
                        Assert.assertEquals(builtinTypes.int, fileA.firstTopLevelFunction.returnType)
                    }
                }
            }

            moduleA.addDependency(moduleD)
            globalTracker.assertModifiedExactly(times = 3)
        }
    }

    fun `test event limits when replacing the roots of several libraries`() {
        val libraryA = createProjectLibrary("a")
        val libraryB = createProjectLibrary("b")
        val libraryC = createProjectLibrary("c")
        val moduleD = createModuleInTmpDir("d")
        createModuleInTmpDir("e")

        moduleD.addDependency(libraryA)
        moduleD.addDependency(libraryB)
        moduleD.addDependency(libraryC)

        val globalTracker = createGlobalModuleStateTracker()

        // There are currently no event limits for module changes through the workspace model, so we expect multiple modification events.
        runUndoTransparentWriteAction {
            libraryA.swapRoot()
            globalTracker.assertModifiedOnce()

            libraryB.swapRoot()
            globalTracker.assertModifiedExactly(times = 2)

            libraryC.swapRoot()
            globalTracker.assertModifiedExactly(times = 3)
        }
    }

    @OptIn(KaAllowAnalysisFromWriteAction::class, KaAllowAnalysisOnEdt::class)
    fun `test write-action analysis resets event limits when replacing the roots of several libraries`() {
        val libraryA = createProjectLibrary("a")
        val libraryB = createProjectLibrary("b")
        val libraryC = createProjectLibrary("c")
        val moduleD = createModuleInTmpDir("d") {
            listOf(FileWithText("d.kt", "fun foo() = 10"))
        }
        createModuleInTmpDir("e")

        moduleD.addDependency(libraryA)
        moduleD.addDependency(libraryB)
        moduleD.addDependency(libraryC)

        val fileD = moduleD.findSourceKtFile("d.kt")
        val globalTracker = createGlobalModuleStateTracker()

        // There are currently no event limits for module changes through the workspace model, so we expect multiple modification events.
        runUndoTransparentWriteAction {
            libraryA.swapRoot()
            globalTracker.assertModifiedOnce()

            libraryB.swapRoot()
            globalTracker.assertModifiedExactly(times = 2)

            // `analyze` should reset the event publishing limits as it might populate caches.
            allowAnalysisOnEdt {
                allowAnalysisFromWriteAction {
                    analyze(fileD) {
                        Assert.assertEquals(builtinTypes.int, fileD.firstTopLevelFunction.returnType)
                    }
                }
            }

            libraryC.swapRoot()
            globalTracker.assertModifiedExactly(times = 3)
        }
    }

    private fun createGlobalSourceModuleStateTracker(
        additionalAllowedEventKinds: Set<KotlinModificationEventKind> = emptySet(),
    ): ModificationEventTracker =
        createGlobalTracker(
            "global source module state tracker",
            expectedEventKind = KotlinModificationEventKind.GLOBAL_SOURCE_MODULE_STATE_MODIFICATION,
            additionalAllowedEventKinds = additionalAllowedEventKinds,
        )

    private fun createGlobalModuleStateTracker(
        additionalAllowedEventKinds: Set<KotlinModificationEventKind> = emptySet(),
    ): ModificationEventTracker =
        createGlobalTracker(
            "global module state tracker",
            expectedEventKind = KotlinModificationEventKind.GLOBAL_MODULE_STATE_MODIFICATION,
            additionalAllowedEventKinds = additionalAllowedEventKinds,
        )

    private fun Library.swapRoot() {
        // We're already in a write action at this point.
        val existingRootUrl = rootProvider.getUrls(OrderRootType.CLASSES)[0]!!
        modifiableModel.apply {
            removeRoot(existingRootUrl, OrderRootType.CLASSES)
            addEmptyClassesRoot()
            commit()
        }
    }

    private val KtFile.firstTopLevelFunction: KtNamedFunction
        get() = declarations.first() as KtNamedFunction
}
