// IS_APPLICABLE: false
// ERROR: 'infix' modifier is inapplicable on this function: must have a single value parameter
// K2_ERROR: 'infix' modifier is inapplicable to this function.
fun foo(x: Foo) {
    x.<caret>foo(bar = x)
}

interface Foo {
    infix fun foo(baz: Int = 0, bar: Foo? = null)
}
