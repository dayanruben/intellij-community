class Lists {
    fun fillPublic(list: MutableList<String?>) {
        list.add("a")
    }

    fun fillPackagePrivate(list: MutableList<String>) {
        list.add("a")
    }
}
