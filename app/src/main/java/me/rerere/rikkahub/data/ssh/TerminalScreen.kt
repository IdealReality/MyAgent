package me.rerere.rikkahub.data.ssh

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * A deliberately small VT renderer for the line-oriented SSH terminal page.
 *
 * The previous implementation took each raw `InputStream` read and turned it straight into a
 * `String`, then split on `\n` *and* `\r`. Against a real remote shell that is pure noise:
 *
 *  - A UTF-8 character can straddle two reads, so `String(buffer, 0, n, UTF_8)` produced
 *    replacement characters in the middle of Chinese text.
 *  - Every modern interactive shell (fish, zsh with themes, bash with a fancy prompt) emits
 *    OSC/CSI control sequences. Rendered as text they are the "garbage" the user sees.
 *  - `\r` is not a line separator — it is a carriage return used for in-place redraws. Splitting
 *    on it turned one redrawn prompt into dozens of fragments.
 *  - fish probes the terminal with a Primary Device Attribute query and blocks for **ten
 *    seconds** waiting for an answer; with no answer it disables its optional features and
 *    swallows keystrokes during that window (measured: `\e[0c` sent at connect, warning printed
 *    10s later).
 *
 * This class is the smallest thing that fixes all four: incremental UTF-8 decoding, a VT state
 * machine that renders a single editable line (`\r`, `\b`, CSI K/J/C/D/G), strips colours and
 * titles, drops private-use glyphs the phone font cannot draw, and answers the handful of
 * queries a shell actually blocks on.
 *
 * It is intentionally not a full terminal: no scroll regions, no alt-screen, no
 * multi-line cursor addressing. Full-screen TUI programs (vim, htop) are not supported and the
 * page says so; normal command runs, prompts and redraws render correctly.
 */
class TerminalScreen {

    /**
     * One parsed read.
     *
     * @property appended lines that are finished and should be appended to the transcript.
     * @property current the line still being edited (redrawn in place by the shell).
     * @property clear the remote asked for a full clear (`CSI 2J`); drop the transcript.
     * @property replies bytes that must be written back to the remote (query answers).
     */
    class Update(
        val appended: List<String>,
        val current: String,
        val clear: Boolean,
        val replies: List<ByteArray>,
    )

    private enum class State { GROUND, ESC, CSI, OSC, OSC_ESC, DCS, DCS_ESC }

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /** Bytes of a multi-byte character that did not finish in the previous read. */
    private var pending = ByteArray(0)

    private var state = State.GROUND
    private val params = StringBuilder()
    private val intermediates = StringBuilder()
    private val osc = StringBuilder()

    private val line = StringBuilder()
    private var cursor = 0

    private val appended = ArrayList<String>()
    private val replies = ArrayList<ByteArray>()
    private var clearing = false

    /** High half of a surrogate pair seen in the previous read or earlier in this one. */
    private var pendingHigh: Char? = null

    /** Feed a raw chunk; returns everything the UI needs to render it plus bytes to answer. */
    fun feed(bytes: ByteArray): Update = feed(bytes, bytes.size)

    /** Feed the first [length] bytes of [bytes] (matters when the caller reuses its buffer). */
    fun feed(bytes: ByteArray, length: Int): Update {
        appended.clear()
        replies.clear()
        clearing = false

        decode(bytes, length) { consume(it) }

        return Update(
            appended = appended.toList(),
            current = line.toString(),
            clear = clearing,
            replies = replies.toList(),
        )
    }

    /** True while a multi-byte character is split across reads; exposed for tests. */
    internal fun hasPendingBytes(): Boolean = pending.isNotEmpty()

    private fun decode(bytes: ByteArray, length: Int, sink: (Char) -> Unit) {
        val input = ByteArray(pending.size + length)
        System.arraycopy(pending, 0, input, 0, pending.size)
        System.arraycopy(bytes, 0, input, pending.size, length)

        val source = ByteBuffer.wrap(input)
        val target = CharBuffer.allocate(input.size + 8)
        // endOfInput=false keeps the decoder open across reads and leaves a trailing partial
        // character in `source`, which is exactly what we want to retry next time.
        decoder.decode(source, target, false)
        pending = if (source.hasRemaining()) {
            input.copyOfRange(source.position(), input.size)
        } else {
            ByteArray(0)
        }
        target.flip()
        while (target.hasRemaining()) sink(target.get())
    }

    private fun consume(c: Char) {
        when (state) {
            State.GROUND -> {
                // Nerd-font icons also live in the supplementary private-use planes
                // (U+F0000..U+10FFFD), which arrive as surrogate pairs. A Char-level
                // filter cannot see that code point, so pair them up here and drop them.
                if (c.isHighSurrogate()) {
                    pendingHigh?.let { put(it) }
                    pendingHigh = c
                    return
                }
                if (c.isLowSurrogate()) {
                    val high = pendingHigh
                    pendingHigh = null
                    if (high != null) {
                        val codePoint = Character.toCodePoint(high, c)
                        if (!isSupplementaryPrivateUse(codePoint)) {
                            put(high)
                            put(c)
                        }
                    }
                    return
                }
                pendingHigh?.let { put(it); pendingHigh = null }
                groundChar(c)
            }

            State.ESC -> state = when (c) {
                '[' -> {
                    params.clear(); intermediates.clear(); State.CSI
                }
                ']' -> {
                    osc.clear(); State.OSC
                }
                'P' -> State.DCS
                '\\' -> State.GROUND
                else -> State.GROUND // two-byte escape (e.g. ESC = / ESC >): no visible effect
            }

            State.CSI -> when {
                c.code in 0x40..0x7e -> {
                    handleCsi(c)
                    state = State.GROUND
                }
                // '?' '>' '<' '=' are private-parameter prefixes, not part of the numbers;
                // they must reach handleCsi or `CSI ? u` looks like a malformed `CSI u`.
                c.code in 0x20..0x2f || c.code in 0x3c..0x3f ->
                    if (intermediates.length < 8) intermediates.append(c)
                c == ESC -> state = State.ESC
                else -> if (params.length < 64) params.append(c)
            }

            State.OSC -> when {
                c == '\u0007' -> {
                    handleOsc(); state = State.GROUND
                }
                c == ESC -> state = State.OSC_ESC
                else -> if (osc.length < 512) osc.append(c)
            }

            State.OSC_ESC -> if (c == '\\') {
                handleOsc(); state = State.GROUND
            } else {
                if (osc.length < 512) osc.append(c)
                state = State.OSC
            }

            State.DCS -> if (c == ESC) state = State.DCS_ESC

            State.DCS_ESC -> state = if (c == '\\') State.GROUND else State.DCS
        }
    }

    private fun handleCsi(final: Char) {
        val prefix = intermediates.toString()
        val args = parseArgs(params.toString())
        val first = args.firstOrNull() ?: 0
        when (final) {
            // Colours and other graphic renditions: dropped, the page is plain text.
            'm' -> Unit

            'K' -> when (first) {
                0 -> if (cursor < line.length) line.setLength(cursor)
                1 -> for (i in 0 until minOf(cursor + 1, line.length)) line.setCharAt(i, ' ')
                2 -> line.setLength(0)
            }

            'J' -> when {
                first >= 2 -> {
                    line.setLength(0)
                    cursor = 0
                    clearing = true
                }
                // `CSI J` erases from the cursor to the end of the screen; with one line of
                // scrollback that is the tail of the current line.
                else -> if (cursor < line.length) line.setLength(cursor)
            }

            'C' -> moveRight(if (args.isEmpty()) 1 else first.coerceAtLeast(1))
            'D' -> cursor = (cursor - (if (args.isEmpty()) 1 else first.coerceAtLeast(1))).coerceAtLeast(0)
            'G' -> cursor = (first.coerceAtLeast(1) - 1).coerceAtMost(MAX_LINE_CHARS)
            // Cursor addressing, line movement and scrolling have no meaning in a one-line
            // model; ignoring them is better than printing their parameters.
            'H', 'f', 'A', 'B', 'E', 'F', 'S', 'T', 'd', 's', 'r' -> Unit

            // DSR: the shell asks where the cursor is (usually to size a prompt redraw).
            'n' -> if (first == 6) reply("\u001b[1;${cursor + 1}R")

            // DA1: fish blocks on this for ten seconds; any `CSI ? … c` answer unblocks it.
            'c' -> if (prefix.isEmpty() && (args.isEmpty() || first == 0)) reply(DA1_RESPONSE)

            // Kitty keyboard protocol probe. Reporting "not supported" is correct and final.
            'u' -> if (prefix == "?") reply("\u001b[?0u")

            // XTVERSION probe.
            'q' -> if (prefix == ">") reply(XTVERSION_RESPONSE)

            else -> Unit
        }
    }

    private fun handleOsc() {
        val text = osc.toString()
        // OSC 11 asks for the terminal background colour; fish uses it for prompt contrast.
        if (text.startsWith("11;?")) reply(OSC11_RESPONSE)
        // Everything else (window title, cwd, shell-integration marks) is intentionally dropped.
    }

    private fun parseArgs(raw: String): List<Int> =
        if (raw.isEmpty()) emptyList()
        else raw.split(';').map { it.toIntOrNull() ?: 0 }

    private fun put(c: Char) {
        if (line.length > MAX_LINE_CHARS) return
        while (line.length < cursor) line.append(' ')
        if (cursor < line.length) line.setCharAt(cursor, c) else line.append(c)
        cursor += 1
    }

    private fun moveRight(n: Int) {
        // Bound the jump: `CSI 99999999 C` would otherwise allocate a hundred million spaces.
        cursor = (cursor + n).coerceAtMost(MAX_LINE_CHARS)
        while (line.length < cursor) line.append(' ')
    }

    private fun commitLine() {
        appended += line.toString().trimEnd()
        if (appended.size > MAX_LINES_PER_READ) {
            // A `cat` of a huge file should not grow this batch without bound; the transcript
            // keeps only its tail anyway.
            appended.subList(0, appended.size - MAX_LINES_PER_READ).clear()
        }
        line.setLength(0)
        cursor = 0
    }

    private fun reply(text: String) {
        replies += text.toByteArray(Charsets.US_ASCII)
    }

    private fun isPrivateUse(c: Char): Boolean = c.code in 0xE000..0xF8FF

    private fun isSupplementaryPrivateUse(codePoint: Int): Boolean =
        codePoint in 0xF0000..0xFFFFD || codePoint in 0x100000..0x10FFFD

    /** Handle one non-surrogate character while in the text state. */
    private fun groundChar(c: Char) {
        when {
            c == ESC -> state = State.ESC
            c == '\r' -> cursor = 0
            c == '\n' -> commitLine()
            c == '\b' -> if (cursor > 0) cursor -= 1
            c == '\t' -> {
                val next = (cursor / TAB_WIDTH + 1) * TAB_WIDTH
                while (cursor < next) put(' ')
            }
            c.code < 0x20 || c.code == 0x7f -> Unit // other C0 controls carry no text
            isPrivateUse(c) -> Unit // nerd-font glyphs have no glyph on the phone font
            else -> put(c)
        }
    }

    private companion object {
        const val ESC = '\u001b'
        const val TAB_WIDTH = 8
        const val MAX_LINE_CHARS = 8_192
        const val MAX_LINES_PER_READ = 2_000

        /** VT220-class answer: 62 = VT220, plus ANSI colour, printing and selective erase. */
        const val DA1_RESPONSE = "\u001b[?62;1;2;6;9;15;22c"
        const val XTVERSION_RESPONSE = "\u001bP>|RikkaTerm 1.0\u001b\\"
        const val OSC11_RESPONSE = "\u001b]11;rgb:0000/0000/0000\u001b\\"
    }
}
