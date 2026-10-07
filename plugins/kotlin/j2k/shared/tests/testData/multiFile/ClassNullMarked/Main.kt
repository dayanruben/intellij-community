package test

import org.jspecify.annotations.NullMarked

// KTIJ-26127: in a @NullMarked class, an unannotated type and its type arguments are not null
@NullMarked
class Main {
    private val names: MutableList<String> = ArrayList<String>()

    fun names(): MutableList<String> {
        return names
    }

    fun first(): String? {
        return if (names.isEmpty()) null else names.get(0)
    }

    fun add(name: String) {
        names.add(name)
    }
}
