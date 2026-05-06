// COMPILER_ARGUMENTS: -Xcontext-parameters -XXLanguage:+ExplicitContextArguments
// PRIORITY: LOW
// INTENTION_TEXT: "Add 'x =' to argument"
// IGNORE_K1
// K2_ERROR: Function 'foo' must have a body.
// K2_ERROR: Mixing named and positional arguments is not allowed unless the order of the arguments matches the order of the parameters.
// K2_ERROR: Mixing named and positional arguments is not allowed unless the order of the arguments matches the order of the parameters.
// K2_ERROR: No context argument for 'x: String' found.
// K2_ERROR: No value passed for parameter 'a'.
// K2_AFTER_ERROR: Function 'foo' must have a body.
// K2_AFTER_ERROR: Mixing named and positional arguments is not allowed unless the order of the arguments matches the order of the parameters.
// K2_AFTER_ERROR: No value passed for parameter 'a'.

context(x: String)
fun foo(a: Int, b: String)

fun main() {
    // suggest "x", "a" will be type mismatch
    foo(b = "World", <caret>"Hello", 4)
}