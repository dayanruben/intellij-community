// "Remove EXPRESSION target" "true"
import kotlin.annotation.AnnotationTarget.*

<caret>@Retention
@Target(FIELD, EXPRESSION, PROPERTY)
annotation class Ann
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.RemoveExpressionTargetFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.RemoveExpressionTargetFix