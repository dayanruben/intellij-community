// PLATFORM: Common
// FILE: A.kt
// MAIN
expect open class BaseClass {
    open fun base()
}
open class CommonChild1 : BaseClass() {
    override fun base() {}
}
open class CommonChild2 : BaseClass()
abstract class CommonIntermediate : BaseClass()

// PLATFORM: Jvm
// FILE: A.kt
actual open class BaseClass actual constructor() {
    actual open fun base() {}
}
open class JvmOnlyChild : BaseClass()
class JvmIntermediate : CommonIntermediate()

// PLATFORM: Js
// FILE: A.kt
actual open class BaseClass actual constructor() {
    actual open fun base() {}
}
open class JsOnlyChild : BaseClass()
class JsIntermediate : CommonIntermediate()
