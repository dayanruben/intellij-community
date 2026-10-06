internal class ArrayField {
    fun test() {
        val array = arrayOfNulls<String>(0)

        for (s in array) {
            println(s!!.length)
        }
    }
}
