// WITH_STDLIB
// ERROR: Using 'toUpperCase(): String' is an error. Use uppercase() instead.
// ERROR: Using 'toUpperCase(): String' is an error. Use uppercase() instead.
// K2_ERROR: 'fun String.toUpperCase(): String' is deprecated. Use uppercase() instead.
// K2_ERROR: 'fun String.toUpperCase(): String' is deprecated. Use uppercase() instead.
fun test(a: String, b: String): Boolean {
    return <caret>a.toUpperCase() == b.toUpperCase()
}