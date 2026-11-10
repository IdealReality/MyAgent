package me.rerere.myagent.data.ssh

/**
 * One SGR attribute set for a run of transcript text.
 *
 * Colours are packed `0xRRGGBB`; `null` means "the terminal default", which the UI maps to the
 * theme's on-surface colour. Instances are immutable and shared — a line of a few thousand
 * characters normally points at two or three distinct styles.
 */
data class TerminalStyle(
    val fg: Int? = null,
    val bg: Int? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val inverse: Boolean = false,
) {
    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = TerminalStyle()
    }
}

/** Characters `[start, end)` of a line that share [style]. */
data class TerminalSpan(val start: Int, val end: Int, val style: TerminalStyle)

/**
 * One rendered transcript line: the plain text plus the runs that are not in the default style.
 *
 * Kept free of Compose and Android so it can be built, tested and persisted on a plain JVM.
 * [toAnsi] is what the on-disk snapshot stores, so a restored tab keeps its colours without a
 * new JSON shape: `lines` stays a `List<String>`, it just carries SGR sequences.
 */
data class TerminalLine(
    val text: String,
    /** Ordered, non-overlapping, and only non-default runs. */
    val spans: List<TerminalSpan> = emptyList(),
) {
    fun toAnsi(): String {
        if (text.isEmpty() || spans.isEmpty()) return text
        val out = StringBuilder(text.length + spans.size * 16 + 4)
        var cursor = 0
        for (span in spans) {
            val start = span.start.coerceIn(0, text.length)
            val end = span.end.coerceIn(start, text.length)
            if (end == start) continue
            if (start > cursor) out.append(text, cursor, start)
            out.append(span.style.toSgr())
            out.append(text, start, end)
            // Reset per run: a run that ends mid-line must not colour the plain text after it.
            out.append(RESET)
            cursor = end
        }
        if (cursor < text.length) out.append(text, cursor, text.length)
        return out.toString()
    }

    companion object {
        val EMPTY = TerminalLine("")

        /** Collapses a per-character style list into runs, skipping the default style. */
        fun spansOf(styles: List<TerminalStyle>, length: Int): List<TerminalSpan> {
            if (styles.isEmpty() || length <= 0) return emptyList()
            val limit = minOf(length, styles.size)
            val spans = ArrayList<TerminalSpan>()
            var start = 0
            var current = styles[0]
            for (i in 1 until limit) {
                val style = styles[i]
                if (style != current) {
                    if (!current.isDefault) spans += TerminalSpan(start, i, current)
                    start = i
                    current = style
                }
            }
            if (!current.isDefault) spans += TerminalSpan(start, limit, current)
            return spans
        }

        /**
         * Reads back what [toAnsi] wrote, and tolerates a plain string (older snapshots, or a
         * line that never had colours). Unknown escapes are dropped rather than printed.
         */
        fun fromAnsi(raw: String): TerminalLine {
            if (raw.indexOf(ESC) < 0) return TerminalLine(raw)
            val text = StringBuilder(raw.length)
            val spans = ArrayList<TerminalSpan>()
            var style = TerminalStyle.DEFAULT
            var runStart = 0
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c != ESC) {
                    text.append(c)
                    i += 1
                    continue
                }
                if (i + 1 < raw.length && raw[i + 1] == '[') {
                    val end = raw.indexOf('m', i + 2)
                    if (end < 0) {
                        i += 1
                        continue
                    }
                    if (text.length > runStart) spans += TerminalSpan(runStart, text.length, style)
                    style = applySgr(style, raw.substring(i + 2, end))
                    runStart = text.length
                    i = end + 1
                    continue
                }
                // Any other escape: skip through its final byte so its payload cannot leak out.
                var j = i + 1
                while (j < raw.length && !raw[j].isLetter()) j += 1
                i = j + 1
            }
            if (text.length > runStart) spans += TerminalSpan(runStart, text.length, style)
            return TerminalLine(text.toString(), spans.filterNot { it.style.isDefault })
        }
    }
}

/** The reset sequence; also the terminator [TerminalLine.toAnsi] appends. */
private const val RESET = "\u001b[0m"
private const val ESC = '\u001b'

private fun TerminalStyle.toSgr(): String {
    val codes = StringBuilder(RESET.length + 16)
    codes.append("0")
    if (bold) codes.append(";1")
    if (dim) codes.append(";2")
    if (italic) codes.append(";3")
    if (underline) codes.append(";4")
    if (inverse) codes.append(";7")
    if (strike) codes.append(";9")
    fg?.let { codes.append(";38;2;").appendChannel(it) }
    bg?.let { codes.append(";48;2;").appendChannel(it) }
    return "\u001b[" + codes + "m"
}

private fun StringBuilder.appendChannel(rgb: Int): StringBuilder = append((rgb shr 16) and 0xFF)
    .append(';').append((rgb shr 8) and 0xFF)
    .append(';').append(rgb and 0xFF)

/**
 * Applies one SGR parameter list (the text between `CSI` and `m`) to [style].
 *
 * An empty list is a reset, which is what `CSI m` means. Unknown codes are ignored — printing
 * them was the "garbage on screen" bug this renderer exists to avoid.
 */
internal fun applySgr(style: TerminalStyle, raw: String): TerminalStyle {
    if (raw.isEmpty()) return TerminalStyle.DEFAULT
    val parts = raw.split(';')
    var current = style
    var i = 0
    while (i < parts.size) {
        val code = parts[i].toIntOrNull() ?: 0
        when {
            code == 0 -> current = TerminalStyle.DEFAULT
            code == 1 -> current = current.copy(bold = true)
            code == 2 -> current = current.copy(dim = true)
            code == 3 -> current = current.copy(italic = true)
            code == 4 -> current = current.copy(underline = true)
            code == 7 -> current = current.copy(inverse = true)
            code == 9 -> current = current.copy(strike = true)
            code == 22 -> current = current.copy(bold = false, dim = false)
            code == 23 -> current = current.copy(italic = false)
            code == 24 -> current = current.copy(underline = false)
            code == 27 -> current = current.copy(inverse = false)
            code == 29 -> current = current.copy(strike = false)
            code in 30..37 -> current = current.copy(fg = ANSI_PALETTE[code - 30])
            code == 39 -> current = current.copy(fg = null)
            code in 90..97 -> current = current.copy(fg = ANSI_PALETTE[code - 90 + 8])
            code in 40..47 -> current = current.copy(bg = ANSI_PALETTE[code - 40])
            code == 49 -> current = current.copy(bg = null)
            code in 100..107 -> current = current.copy(bg = ANSI_PALETTE[code - 100 + 8])
            code == 38 || code == 48 -> {
                val parsed = parseExtendedColour(parts, i + 1)
                if (parsed != null) {
                    current = if (code == 38) current.copy(fg = parsed.first) else current.copy(bg = parsed.first)
                    i += parsed.second
                }
            }
        }
        i += 1
    }
    return current
}

/** `38;5;n` / `38;2;r;g;b`. Returns the colour and how many extra parameters it consumed. */
private fun parseExtendedColour(parts: List<String>, from: Int): Pair<Int, Int>? {
    if (from >= parts.size) return null
    return when (parts[from].toIntOrNull()) {
        5 -> {
            val index = parts.getOrNull(from + 1)?.toIntOrNull() ?: return null
            xtermColour(index.coerceIn(0, 255)) to 2
        }

        2 -> {
            val r = parts.getOrNull(from + 1)?.toIntOrNull() ?: return null
            val g = parts.getOrNull(from + 2)?.toIntOrNull() ?: return null
            val b = parts.getOrNull(from + 3)?.toIntOrNull() ?: return null
            ((pack(r) shl 16) or (pack(g) shl 8) or pack(b)) to 4
        }

        else -> null
    }
}

private fun pack(channel: Int): Int = channel.coerceIn(0, 255)

/** Standard 16 ANSI colours, tuned to stay readable on a dark terminal surface. */
private val ANSI_PALETTE = intArrayOf(
    0x1C1C1C, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xDCDFE4,
    0x5C6370, 0xFF7B86, 0xB5E890, 0xFFD68A, 0x7CC2FF, 0xDE9BFF, 0x6FD3DF, 0xFFFFFF,
)

/** xterm 256-colour index → packed RGB. */
private fun xtermColour(index: Int): Int = when {
    index < 16 -> ANSI_PALETTE[index]
    index < 232 -> {
        val cube = index - 16
        val r = cube / 36
        val g = (cube % 36) / 6
        val b = cube % 6
        val channel = intArrayOf(0, 95, 135, 175, 215, 255)
        (channel[r] shl 16) or (channel[g] shl 8) or channel[b]
    }

    else -> {
        val level = 8 + (index - 232) * 10
        (level shl 16) or (level shl 8) or level
    }
}
