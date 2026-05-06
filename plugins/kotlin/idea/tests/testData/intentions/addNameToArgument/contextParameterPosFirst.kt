// COMPILER_ARGUMENTS: -Xcontext-parameters -XXLanguage:+ExplicitContextArguments
// PRIORITY: LOW
// INTENTION_TEXT: "Add 'a =' to argument"
// IGNORE_K1
// K2_ERROR: Mixing named and positional arguments is not allowed unless the order of the arguments matches the order of the parameters.
// K2_ERROR: Mixing named and positional arguments is not allowed unless the order of the arguments matches the order of the parameters.
// K2_ERROR: No context argument for 'x: String' found.
// K2_ERROR: No value passed for parameter 'a'.
// K2_ERROR: Unresolved reference 'y'.
// K2_AFTER_ERROR: Mixing named and positional arguments is not allowed unless the order of the arguments matches the order of the parameters.
// K2_AFTER_ERROR: No context argument for 'x: String' found.
// K2_AFTER_ERROR: Unresolved reference 'y'.

context(x: String)
fun foo(a: String, b: String): String = x + y + a

fun main() {
    // suggest "a" first
    foo(b = "World", <caret>"Hello", "a")
}