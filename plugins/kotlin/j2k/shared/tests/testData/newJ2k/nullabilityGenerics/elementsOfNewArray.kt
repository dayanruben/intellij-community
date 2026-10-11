internal class ArrayFactory {
    fun make(): Array<String?> {
        val result = arrayOfNulls<String>(2)
        result[0] = "a"
        return result
    }

    fun first(): String {
        val names = arrayOf<String>("a", "b")
        return names[0]
    }
}
