// "Remove variable 'test'" "true"
fun f() {
    val <caret>test: Int
}
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.RemovePsiElementSimpleFix$RemoveVariableFactory$doCreateQuickFix$removePropertyFix$1
// IGNORE_K2
// Task for K2: KTIJ-29591