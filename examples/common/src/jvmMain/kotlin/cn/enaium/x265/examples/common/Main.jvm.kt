package cn.enaium.x265.examples.common

fun main() {
    val frames = System.getenv("EXAMPLE_FRAMES")?.toIntOrNull() ?: Int.MAX_VALUE
    runExample(frames)
}
