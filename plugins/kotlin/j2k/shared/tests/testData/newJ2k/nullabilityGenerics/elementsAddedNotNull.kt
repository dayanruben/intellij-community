internal class Names {
    private val names: MutableList<String> = ArrayList<String>()

    fun add() {
        names.add("a")
    }

    fun first(): String {
        return names.get(0)
    }
}
