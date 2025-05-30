// "Propagate 'SubclassOptInRequired(A::class)' opt-in requirement to 'SomeImplementation'" "true"
// ACTION: Add full qualifier
// ACTION: Flip ',' (may change semantics)
// ACTION: Implement interface
// ACTION: Introduce import alias
// ACTION: Opt in for 'A' in containing file 'propagateSubclassOptInRequired2.kts'
// ACTION: Opt in for 'A' in module 'light_idea_test_case'
// ACTION: Opt in for 'A' on 'SomeImplementation'
// ACTION: Propagate 'SubclassOptInRequired(A::class)' opt-in requirement to 'SomeImplementation'
// ERROR: This class or interface requires opt-in to be implemented. Its usage must be marked with '@PropagateSubclassOptInRequired2.B', '@OptIn(PropagateSubclassOptInRequired2.B::class)' or '@SubclassOptInRequired(PropagateSubclassOptInRequired2.B::class)'
// RUNTIME_WITH_SCRIPT_RUNTIME
// LANGUAGE_VERSION: 2.1

@RequiresOptIn
annotation class A

@RequiresOptIn
annotation class B

@SubclassOptInRequired(A::class)
interface LibraryA

@SubclassOptInRequired(B::class)
interface LibraryB

interface SomeImplementation : LibraryA<caret>, LibraryB
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.OptInFixes$PropagateOptInAnnotationFix