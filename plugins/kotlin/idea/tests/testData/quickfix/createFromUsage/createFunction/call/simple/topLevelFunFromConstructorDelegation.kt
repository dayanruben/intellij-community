// "Create function 'foo'" "true"
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.createFromUsage.createCallable.CreateCallableFromUsageFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.k2.codeinsight.quickFixes.createFromUsage.CreateKotlinCallableAction
// K2_AFTER_ERROR: None of the following candidates is applicable:<br>constructor(pi: Int): CtorChain<br>constructor(ps: String): CtorChain
class CtorChain(val pi: Int) {
    constructor() : this(0)
    constructor(ps: String) : this(f<caret>oo())
}
// IGNORE_K1