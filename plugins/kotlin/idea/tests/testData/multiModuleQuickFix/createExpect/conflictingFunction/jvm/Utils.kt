// "Create expected function in common module testModule_Common" "true"
// DISABLE_ERRORS

actual fun foo(): Boolean
actual fun <caret>foo(i: Int, d: Double, s: String) = s == "$i$d"