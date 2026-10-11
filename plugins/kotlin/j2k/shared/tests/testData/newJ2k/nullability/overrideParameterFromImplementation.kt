// KTIJ-33359: the implementation dereferences the parameter, so the interface parameter is not null too
class OverrideNonNullContainer {
    internal interface OverrideNonNullFace {
        fun subjectMethod(id: Long)
    }

    class OverrideNonNullImpl : OverrideNonNullFace {
        override fun subjectMethod(id: Long) {
            val ping = id.toByte()
        }
    }
}
