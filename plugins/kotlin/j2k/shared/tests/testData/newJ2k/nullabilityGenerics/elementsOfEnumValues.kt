internal enum class Color {
    RED, GREEN
}

internal class Palette {
    fun first(): Color {
        return Color.entries[0]
    }
}
