package me.rerere.vm

/**
 * 把参数列表拼成一条 shell 命令行。
 *
 * QEMU 的参数里有空格、逗号、冒号、`$`，而 proot 那边是 `bash -c "eval \"$2\""`，
 * 少引一层就会被吃掉或展开，所以统一做严格单引号转义，不做「看起来安全就不加引号」的
 * 启发式——那类启发式正是参数注入的来源。
 */
fun List<String>.toShellCommand(): String = joinToString(" ") { it.shellQuote() }

fun String.shellQuote(): String {
    if (isEmpty()) return "''"
    if (all { it.isLetterOrDigit() || it in SAFE }) return this
    return "'" + replace("'", "'\\''") + "'"
}

private const val SAFE = "_-./:=,@%+"
