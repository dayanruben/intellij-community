// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.completion.impl.k2

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.UserDataHolderBase
import org.jetbrains.annotations.NonNls
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaCompletionExtensionCandidateChecker
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.base.analysis.api.utils.KtSymbolFromIndexProvider
import org.jetbrains.kotlin.idea.base.codeInsight.contributorClass
import org.jetbrains.kotlin.idea.completion.KotlinFirCompletionParameters
import org.jetbrains.kotlin.idea.completion.checkers.CompletionVisibilityChecker
import org.jetbrains.kotlin.idea.completion.impl.k2.contributors.evaluateRuntimeKaType
import org.jetbrains.kotlin.idea.completion.implCommon.handlers.CompletionCharInsertHandler
import org.jetbrains.kotlin.idea.completion.implCommon.stringTemplates.InsertStringTemplateBracesInsertHandler
import org.jetbrains.kotlin.idea.completion.isAtFunctionLiteralStart
import org.jetbrains.kotlin.idea.completion.suppressItemSelectionByCharsOnTyping
import org.jetbrains.kotlin.idea.completion.weighers.CompletionContributorGroupWeigher.groupPriority
import org.jetbrains.kotlin.idea.completion.weighers.WeighingContext
import org.jetbrains.kotlin.idea.util.positionContext.KotlinRawPositionContext
import org.jetbrains.kotlin.idea.util.positionContext.KotlinSimpleNameReferencePositionContext
import java.util.Optional
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/**
 * A completion section is an isolated part of code of a [K2CompletionContributor] that can be run independently.
 * The purpose is to decouple completion sections, allow re-ordering their execution based on their [priority]
 * and potentially run them in parallel.
 * To allow for easier debugging and gathering of performance data, the sections are named with a [name].
 */
internal class K2CompletionSection<P : KotlinRawPositionContext>(
    val priority: K2ContributorSectionPriority,
    val contributor: K2CompletionContributor<P>,
    val name: String,
    val runnable: K2CompletionSectionRunnable<P>,
)

/**
 * This class contains data that is common for all contexts that run within the same [KaSession].
 * This means we do not have to recreate them per section and can reuse them for all sections run in the session.
 */
internal class K2CompletionSectionCommonData<P : KotlinRawPositionContext>(
    val completionContext: K2CompletionContext<P>,
    val weighingContext: WeighingContext,
    val prefixMatcher: PrefixMatcher,
    val visibilityChecker: CompletionVisibilityChecker,
    val importStrategyDetector: ImportStrategyDetector,
    val symbolFromIndexProvider: KtSymbolFromIndexProvider,
) {
    // This needs to be stored in the common data because the storage should be the same for all sections executed
    // within the same analysis session to avoid unnecessary computations.
    val sessionStorage = UserDataHolderBase()
}

private val CURRENT_SECTION_CONTEXT: ThreadLocal<K2CompletionSectionContext<*>> = ThreadLocal()
private val CURRENT_SESSION: ThreadLocal<KaSession> = ThreadLocal()

/**
 * This is the context used within a [K2CompletionSection] providing common data that might be
 * shared between contributors running in the same analysis session.
 */
internal class K2CompletionSectionContext<out P : KotlinRawPositionContext>(
    commonData: K2CompletionSectionCommonData<P>,
    private val contributor: K2CompletionContributor<P>,
    val sink: K2LookupElementSink,
    private val addLaterSection: (K2CompletionSection<P>) -> Unit,
) : UserDataHolder by commonData.sessionStorage {
    companion object {
        // We need these keys because the LazyCompletionSessionProperty in this class are different property instances per context.
        // For extension properties, this will not be relevant and we can use anonymous keys.
        private val RUNTIME_TYPE_KEY: Key<Optional<KaType?>> = Key.create("RUNTIME_TYPE_KEY")
        private val EXTENSION_CHECKER_KEY: Key<Optional<KaCompletionExtensionCandidateChecker?>> = Key.create("EXTENSION_CHECKER_KEY")
    }

    val completionContext: K2CompletionContext<P> = commonData.completionContext

    val positionContext: P = completionContext.positionContext

    val parameters: KotlinFirCompletionParameters = completionContext.parameters

    val project: Project = parameters.completionFile.project

    val weighingContext: WeighingContext = commonData.weighingContext

    val prefixMatcher: PrefixMatcher = commonData.prefixMatcher

    val visibilityChecker: CompletionVisibilityChecker = commonData.visibilityChecker

    val importStrategyDetector: ImportStrategyDetector = commonData.importStrategyDetector

    val symbolFromIndexProvider: KtSymbolFromIndexProvider = commonData.symbolFromIndexProvider

    val runtimeType: KaType? by LazyCompletionSessionProperty(RUNTIME_TYPE_KEY) {
        val positionContext = contextOf<K2CompletionSectionContext<P>>().positionContext
        val receiver = (positionContext as? KotlinSimpleNameReferencePositionContext)?.explicitReceiver
        receiver?.evaluateRuntimeKaType()
    }

    val extensionChecker: KaCompletionExtensionCandidateChecker? by LazyCompletionSessionProperty(EXTENSION_CHECKER_KEY) {
        val sectionContext = contextOf<K2CompletionSectionContext<P>>()

        createExtensionChecker(sectionContext.positionContext, sectionContext.parameters.originalFile, sectionContext.runtimeType)
    }

    fun completeLaterInSameSession(
        name: String,
        priority: K2ContributorSectionPriority = K2ContributorSectionPriority.DEFAULT,
        runnable: context(KaSession, K2CompletionSectionContext<P>) () -> Unit
    ) {
        addLaterSection(
            K2CompletionSection(
                priority = priority,
                contributor = contributor,
                name = name,
                runnable = runnable
            )
        )
    }

    /**
     * Executes the given [block] within the context of this section and the given analysis session.
     * Note: this method needs to be used for [LazyCompletionSessionProperty] accessed from within the [block] to work correctly.
     */
    context(session: KaSession)
    fun <T> withSectionContext(block: context(KaSession, K2CompletionSectionContext<P>) () -> T): T {
        val oldSectionContext = CURRENT_SECTION_CONTEXT.get()
        CURRENT_SECTION_CONTEXT.set(this)
        val oldSession = CURRENT_SESSION.get()
        CURRENT_SESSION.set(session)
        try {
            context(session, this) {
                return block()
            }
        } finally {
            CURRENT_SECTION_CONTEXT.set(oldSectionContext)
            CURRENT_SESSION.set(oldSession)
        }
    }
}

/**
 * Creates a property that stores the value and is shared between all sections that run in the same analysis session.
 * It uses [initializer] to initialize its value for the current context when accessed for the first time.
 *
 * This is usually a good idea to use for expensive computations that should only be done once per analysis session.
 */
internal class LazyCompletionSessionProperty<T, P: KotlinRawPositionContext>(
    // We use an anonymous key by default because the name does not matter to us.
    // Different instances of a key with the same name are distinct.
    private val userDataKey: Key<Optional<T>> = Key.create(""),
    private val initializer: context(KaSession, K2CompletionSectionContext<P>) () -> T
) {

    operator fun getValue(thisRef: K2CompletionSectionContext<P>, property: KProperty<*>): T {
        val existingValue = thisRef.getUserData(userDataKey)
        // The .orElse here is technically a hack to trick the Kotlin type system by using a flexible type.
        // This allows us allowing for lazy nullable and non-nullable properties by using Optionals as a
        // way of differentiating between null and not initialized.
        if (existingValue != null) return existingValue.orElse(null)

        // We want to have lazy properties with convenient lazy initializers for our sessions.
        // Because almost all use cases for a session property will require the session and the context to initialize, we have
        // to store and obtain the current context from thread local variables.
        val currentContext = CURRENT_SECTION_CONTEXT.get()
        val currentSession = CURRENT_SESSION.get()

        if (currentContext == null || currentSession == null) {
            error("Could not find current KaSession or K2CompletionSectionContext")
        }

        @Suppress("UNCHECKED_CAST")
        context(currentSession, currentContext as K2CompletionSectionContext<P>) {
            val newValue = initializer()
            thisRef.putUserData(userDataKey, Optional.ofNullable(newValue) as Optional<T>)
            return newValue
        }
    }

    operator fun setValue(thisRef: K2CompletionSectionContext<P>, property: KProperty<*>, value: T?) {
        @Suppress("UNCHECKED_CAST")
        thisRef.putUserData(userDataKey, Optional.ofNullable(value) as Optional<T>)
    }
}

internal typealias K2CompletionSectionRunnable<P> = context(KaSession, K2CompletionSectionContext<P>) () -> Unit

/**
 * The priority of a completion section determines the order in which the sections are executed.
 * The order only determines the order in which the sections are _started_ to be executed and
 * the order in which elements are added to the [com.intellij.codeInsight.completion.CompletionResultSet].
 *
 * The priorities are ordered by the natural ordering of their respective [value]s.
 * That is to say, smaller values have priority over larger values.
 */
class K2ContributorSectionPriority private constructor(private val value: Double) : Comparable<K2ContributorSectionPriority> {

    companion object {
        /**
         * This priority should be used for sections that offer results that are very likely to be important to the user
         * based on the context _and_ can be executed quickly.
         *
         */
        val HEURISTIC: K2ContributorSectionPriority = K2ContributorSectionPriority(10.0)

        /**
         * This is the priority that should be used for sections that do not conform to the other cases.
         */
        val DEFAULT: K2ContributorSectionPriority = K2ContributorSectionPriority(50.0)

        /**
         * This priority should be used for sections that offer results that are less likely
         * to be useful to the user _and_ may take a long time to be executed.
         */
        val FROM_INDEX: K2ContributorSectionPriority = K2ContributorSectionPriority(100.0)
    }

    override fun compareTo(other: K2ContributorSectionPriority): Int = value.compareTo(other.value)
}

/**
 * The completion setup scope provides information about the current [position] of the completion and allows
 * registering of sections in the [contributor].
 * Please note that it is forbidden to use analysis sessions in this setup scope to avoid capturing the sessions
 * in the [K2CompletionSection]s that might use a different analysis session.
 */
internal class K2CompletionSetupScope<P : KotlinRawPositionContext> internal constructor(
    val completionContext: K2CompletionContext<P>,
    private val contributor: K2CompletionContributor<P>,
    private val registeredCompletions: MutableList<K2CompletionSection<P>>
) {
    val position: P = completionContext.positionContext

    fun completion(
        @NonNls name: String,
        priority: K2ContributorSectionPriority = K2ContributorSectionPriority.DEFAULT,
        runnable: K2CompletionSectionRunnable<P>,
    ) {
        registeredCompletions.add(
            K2CompletionSection(
                priority = priority,
                contributor = contributor,
                name = name,
                runnable = runnable
            )
        )
    }
}

/**
 * A completion contributor that splits completion into several different sections inside the [registerCompletions] method.
 * The purpose of this is to potentially allow executing the sections in parallel.
 * See [K2CompletionSection] for more information.
 */
internal abstract class K2CompletionContributor<P : KotlinRawPositionContext>(
    internal val positionContextClass: KClass<P>
) {
    abstract fun K2CompletionSetupScope<P>.registerCompletions()

    /**
     * Can be changed to not run in certain specific positions that are of type [P] but should still not execute.
     */
    open fun K2CompletionSetupScope<P>.isAppropriatePosition(): Boolean = true

    /**
     * If this method returns false, the execution of the section is skipped.
     * Similar to [isAppropriatePosition], but is executed within the analysis session right before execution of the section.
     */
    context(_: KaSession, context: K2CompletionSectionContext<P>)
    open fun shouldExecute(): Boolean = true

    protected fun K2CompletionSectionContext<P>.addElement(element: LookupElement) {
        sink.addElement(decorateLookupElement(element))
    }

    protected fun K2CompletionSectionContext<P>.addElements(elements: Iterable<LookupElement>) {
        val decoratedElements = elements.map { decorateLookupElement(it) }
        sink.addElements(decoratedElements)
    }

    /**
     * Returns the group priority of the completion section that will be applied as weight to the [LookupElement]s
     * from within the [org.jetbrains.kotlin.idea.completion.weighers.CompletionContributorGroupWeigher].
     *
     * Note: this priority only affects the order of the elements displayed to the user.
     *  It does not affect the order in which the sections are executed.
     */
    protected open fun K2CompletionSectionContext<P>.getGroupPriority(): Int = 0

    private fun K2CompletionSectionContext<P>.decorateLookupElement(
        element: LookupElement,
    ): LookupElement {
        element.groupPriority = getGroupPriority()
        element.contributorClass = this::class.java

        if (isAtFunctionLiteralStart(parameters.position)) {
            element.suppressItemSelectionByCharsOnTyping = true
        }

        val bracesInsertHandler = when (parameters.type) {
            KotlinFirCompletionParameters.CorrectionType.BRACES_FOR_STRING_TEMPLATE -> InsertStringTemplateBracesInsertHandler
            else -> WrapSingleStringTemplateEntryWithBracesInsertHandler
        }

        return LookupElementDecorator.withDelegateInsertHandler(
            LookupElementDecorator.withDelegateInsertHandler(element, bracesInsertHandler),
            CompletionCharInsertHandler(parameters.delegate.isAutoPopup),
        )
    }
}

/**
 * A basic completion contributor that only provides a single section that is executed in the [complete] method.
 */
internal abstract class K2SimpleCompletionContributor<P : KotlinRawPositionContext>(
    positionContextClass: KClass<P>,
    private val priority: K2ContributorSectionPriority = K2ContributorSectionPriority.DEFAULT,
    nameOverride: String? = null,
) : K2CompletionContributor<P>(positionContextClass) {
    context(_: KaSession, context: K2CompletionSectionContext<P>)
    abstract fun complete()

    private val name = nameOverride ?: this::class.simpleName ?: "Unknown"

    final override fun K2CompletionSetupScope<P>.registerCompletions() {
        completion(name = name, priority = priority) { complete() }
    }
}