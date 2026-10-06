internal class Cache {
    private val values: MutableList<String?> = ArrayList<String?>()

    fun clear() {
        values.add(null)
    }

    fun first(): String? {
        return values.get(0)
    }
}
