package me.rerere.myagent.data.ssh

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** One cell of the emulator grid. */
internal data class TerminalCell(
    val text: String = " ",
    val style: TerminalStyle = TerminalStyle.DEFAULT,
    /** Right half of a double-width character: occupies a column, draws nothing. */
    val continuation: Boolean = false,
)

/** Where the visible cursor sits, in grid coordinates. */
data class TerminalCursor(val row: Int, val col: Int)

/**
 * A VT screen emulator: `rows × cols` cells, a cursor, a scroll region and an alternate screen.
 *
 * The previous renderer was "one editable line + finished lines". That is enough for prompts and
 * command output, and structurally impossible for full-screen programs: `vim`, `htop` and `top`
 * address cells (`CSI H`), erase regions (`ED`/`EL`), insert and delete lines (`IL`/`DL`), scroll
 * within a region (`DECSTBM`) and switch to an alternate screen (`?1049h`). All of those used to
 * be parsed and thrown away, so such a program painted over itself.
 *
 * What it supports: CUP/CUU/CUD/CUF/CUB/CNL/CPL/CHA/VPA, ED/EL/ECH/ICH/DCH, IL/DL, SU/SD,
 * DECSTBM, alt-screen, cursor show/hide, autowrap, SGR (see [TerminalStyle]), double-width CJK,
 * combining marks, and the handful of queries a shell blocks on (DA1, DSR, XTVERSION, OSC 11).
 *
 * What it deliberately does not: sixel/kitty graphics, mouse reporting, bracketed paste,
 * character sets (DEC graphics), and the deprecated "insert mode".
 */
class TerminalScreen(
    val cols: Int = 80,
    val rows: Int = 24,
) {

    /**
     * One parsed read.
     *
     * @property scrolled lines pushed off the top of the primary screen; append to the transcript.
     * @property screen the visible grid, top row first, with trailing blank rows removed.
     * @property cursorRow/cursorCol/cursorVisible where to draw the cursor inside [screen].
     * @property resetTranscript the remote asked for a full clear (RIS / `CSI 2J`).
     * @property replies bytes that must be written back to the remote (query answers).
     */
    class Update(
        val scrolled: List<TerminalLine>,
        val screen: List<TerminalLine>,
        val cursorRow: Int,
        val cursorCol: Int,
        val cursorVisible: Boolean,
        val resetTranscript: Boolean,
        val replies: List<ByteArray>,
    )

    private enum class State { GROUND, ESC, ESC_CHARSET, CSI, OSC, OSC_ESC, DCS, DCS_ESC }

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /** Bytes of a multi-byte character that did not finish in the previous read. */
    private var pending = ByteArray(0)

    private var state = State.GROUND
    private val params = StringBuilder()
    private val intermediates = StringBuilder()
    private val osc = StringBuilder()

    private var style = TerminalStyle.DEFAULT
    private val cells = Array(rows) { Array(cols) { TerminalCell() } }
    private var cursorRow = 0
    private var cursorCol = 0
    private var cursorVisible = true
    private var autoWrap = true

    /**
     * Set when the cursor sits on the last column after printing: the *next* character wraps.
     * Printing immediately would scroll on every full-width line, which is the classic
     * off-by-one that leaves a stray blank line in `less` and `htop`.
     */
    private var pendingWrap = false

    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var savedCursor: SavedCursor? = null

    /** Non-null while an alternate-screen program (vim/htop) is running. */
    private var primary: Snapshot? = null

    /** True while an alternate-screen program (vim/htop) is running. */
    private val inAltScreen: Boolean get() = primary != null

    private val scrolled = ArrayList<TerminalLine>()
    private val replies = ArrayList<ByteArray>()
    private var resetTranscript = false

    /**
     * True while the remote asked for bracketed-paste framing (`CSI ? 2004 h`).
     *
     * Ink-based TUIs (`omp`, Claude Code, gemini-cli) enable it every frame. The terminal must
     * wrap pasted text in `CSI 200 ~ … CSI 201 ~`, otherwise a multi-line paste arrives as if the
     * user had typed it: the TUI submits on the first line and the rest lands in a new prompt.
     */
    var bracketedPaste = false
        private set

    /**
     * True once the running program addressed a cell by coordinates (`CSI r ; c H`).
     *
     * That is the difference between a shell prompt and a full-screen TUI: the TUI repaints from
     * scratch after `SIGWINCH`, a shell does not. It is what decides whether a live window-change
     * is safe (see `TerminalEngine.resize`).
     */
    var usesCursorAddressing = false
        private set


    /** High half of a surrogate pair seen in the previous read or earlier in this one. */
    private var pendingHigh: Char? = null

    /** Feed a raw chunk; returns everything the UI needs to render it plus bytes to answer. */
    fun feed(bytes: ByteArray): Update = feed(bytes, bytes.size)

    /** Feed the first [length] bytes of [bytes] (matters when the caller reuses its buffer). */
    fun feed(bytes: ByteArray, length: Int): Update {
        scrolled.clear()
        replies.clear()
        resetTranscript = false

        decode(bytes, length) { consume(it) }

        return Update(
            scrolled = scrolled.toList(),
            screen = screenLines(),
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            cursorVisible = cursorVisible,
            resetTranscript = resetTranscript,
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
                if (c.isHighSurrogate()) {
                    pendingHigh?.let { putCodePoint(it.code) }
                    pendingHigh = c
                    return
                }
                if (c.isLowSurrogate()) {
                    val high = pendingHigh
                    pendingHigh = null
                    if (high != null) putCodePoint(Character.toCodePoint(high, c))
                    return
                }
                pendingHigh?.let { putCodePoint(it.code); pendingHigh = null }
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
                '7' -> {
                    saveCursor(); State.GROUND
                }
                '8' -> {
                    restoreCursor(); State.GROUND
                }
                'c' -> {
                    fullReset(); State.GROUND
                }
                // Charset selection takes one more byte: `ESC ( B` is ASCII, `ESC % G` is
                // UTF-8. Swallowing only the '(' used to print that byte's partner as text —
                // htop's meters came out peppered with stray `B`s.
                '(', ')', '*', '+', '-', '.', '/', '#', '%' -> State.ESC_CHARSET
                '\\' -> State.GROUND
                else -> State.GROUND // two-byte escape (ESC = / ESC >)
            }

            State.ESC_CHARSET -> state = State.GROUND

            State.CSI -> when {
                c.code in 0x40..0x7e -> {
                    handleCsi(c)
                    state = State.GROUND
                }
                // '?' '>' '<' '=' are private-parameter prefixes, not part of the numbers;
                // they must reach handleCsi or `CSI ? u` looks like a malformed `CSI u`.
                c.code in 0x20..0x2f || c.code in 0x3c..0x3f ->
                    if (intermediates.length < 8) intermediates.append(c)
                c == '\u001b' -> state = State.ESC
                else -> if (params.length < 64) params.append(c)
            }

            State.OSC -> when {
                c == '\u0007' -> {
                    handleOsc(); state = State.GROUND
                }
                c == '\u001b' -> state = State.OSC_ESC
                else -> if (osc.length < 512) osc.append(c)
            }

            State.OSC_ESC -> if (c == '\\') {
                handleOsc(); state = State.GROUND
            } else {
                if (osc.length < 512) osc.append(c)
                state = State.OSC
            }

            State.DCS -> if (c == '\u001b') state = State.DCS_ESC

            State.DCS_ESC -> state = if (c == '\\') State.GROUND else State.DCS
        }
    }

    private fun handleCsi(final: Char) {
        val prefix = intermediates.toString()
        val args = parseArgs(params.toString())
        val first = args.firstOrNull() ?: 0
        val count = { index: Int, fallback: Int -> (args.getOrNull(index) ?: fallback).coerceAtLeast(1) }

        when (final) {
            'm' -> style = applySgr(style, params.toString())

            // Cursor movement. The origin is the scroll region, not the screen, when one is set.
            // Any of these also cancels a pending wrap: the deferred wrap belongs to the write
            // that just ended, not to wherever the program moves next.
            'A' -> {
                cursorRow = (cursorRow - count(0, 1)).coerceAtLeast(scrollTop)
                pendingWrap = false
            }

            'B' -> {
                cursorRow = (cursorRow + count(0, 1)).coerceAtMost(scrollBottom)
                pendingWrap = false
            }

            'C' -> moveRight(count(0, 1))
            'D' -> {
                // From the wrap position (one past the last column), so CUB 1 lands *on* the last
                // column rather than before it — verified against tmux: `12345 CSI D 9` → 12349.
                cursorCol = (writeCol - count(0, 1)).coerceIn(0, cols - 1)
                pendingWrap = false
            }

            'E' -> {
                cursorRow = (cursorRow + count(0, 1)).coerceAtMost(scrollBottom)
                cursorCol = 0
                pendingWrap = false
            }

            'F' -> {
                cursorRow = (cursorRow - count(0, 1)).coerceAtLeast(scrollTop)
                cursorCol = 0
                pendingWrap = false
            }

            'G' -> {
                cursorCol = (count(0, 1) - 1).coerceIn(0, cols - 1)
                pendingWrap = false
            }

            'd' -> {
                cursorRow = (count(0, 1) - 1).coerceIn(0, rows - 1)
                pendingWrap = false
            }

            'H', 'f' -> {
                cursorRow = (count(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (count(1, 1) - 1).coerceIn(0, cols - 1)
                pendingWrap = false
                usesCursorAddressing = true
            }

            'J' -> eraseDisplay(first)
            'K' -> eraseLine(first)
            'X' -> eraseChars(count(0, 1), eraseStyle = false)
            '@' -> insertChars(count(0, 1))
            'P' -> deleteChars(count(0, 1))
            // IL inserts blank lines *below* the cursor (pushing content down); DL deletes the
            // cursor line and pulls the rest up. Getting these two swapped looks like the
            // program scrolling backwards.
            'L' -> scrollRegionDown(count(0, 1), atCursor = true)
            'M' -> scrollRegionUp(count(0, 1), atCursor = true)
            'S' -> scrollRegionUp(count(0, 1), atCursor = false)
            'T' -> scrollRegionDown(count(0, 1), atCursor = false)
            'r' -> setScrollRegion(args)
            's' -> saveCursor()
            'u' -> restoreCursor()

            // DSR: the shell asks where the cursor is (usually to size a prompt redraw).
            'n' -> if (first == 6) reply("\u001b[${cursorRow + 1};${cursorCol + 1}R")

            // DA1: fish blocks on this for ten seconds; any `CSI ? … c` answer unblocks it.
            'c' -> if (prefix.isEmpty() && (args.isEmpty() || first == 0)) reply(DA1_RESPONSE)

            // Kitty keyboard protocol probe. Reporting "not supported" is correct and final.
            'u' -> if (prefix == "?") reply("\u001b[?0u")

            // XTVERSION probe.
            'q' -> if (prefix == ">") reply(XTVERSION_RESPONSE)

            'h' -> handleModes(args, set = true)
            'l' -> handleModes(args, set = false)

            else -> Unit
        }
    }

    private fun handleModes(args: List<Int>, set: Boolean) {
        for (mode in args) {
            when (mode) {
                // Alternate screen. 1049 also saves/restores the cursor, the others do not.
                47, 1047, 1049 -> if (set) enterAltScreen() else exitAltScreen()
                7 -> autoWrap = set
                25 -> cursorVisible = set
                // Bracketed paste is the one report mode a TUI *depends* on: it is how the app
                // tells "text was pasted" from "keys were typed". Everything else (mouse,
                // cursor blinking, focus events) is harmless to ignore, and the terminal must
                // answer nothing or a program waits for a reply.
                2004 -> bracketedPaste = set
                1, 4, 12, 1000, 1002, 1003, 1004, 1005, 1006, 1015, 2026 -> Unit
                else -> Unit
            }
        }
    }

    /**
     * The column the cursor *writes* at. After a character lands in the last column the cursor is
     * logically one past it (the deferred wrap), and xterm/pyte report that position as `cols`.
     * Erasing from there touches nothing — which is the whole point: Ink paints its right edge as
     * `… █` followed by `CSI K`, and treating the cursor as still sitting *on* that cell wiped the
     * last column of every omp status bar.
     */
    private val writeCol: Int get() = if (pendingWrap) cols else cursorCol

    private fun moveRight(n: Int) {
        cursorCol = (cursorCol + n).coerceIn(0, cols - 1)
        pendingWrap = false
    }

    /** `CSI J` — erase in display. */
    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseChars(cols - writeCol, eraseStyle = true)
                for (r in cursorRow + 1 until rows) cells[r] = blankRow()
            }

            1 -> {
                eraseRange(cursorRow, 0, writeCol + 1)
                for (r in 0 until cursorRow) cells[r] = blankRow()
            }

            // ED2 clears the grid but leaves the cursor where it is (only RIS homes it), and
            // it must NOT drop the scrollback: a pty repaints itself with `ESC [H ESC [2J`
            // whenever the window changes shape, and Windows' ConPTY does that on every
            // resize — treating it as "user wiped the transcript" made the history vanish
            // whenever the keyboard opened. `CSI 3J` is the sequence that means "and the
            // scrollback too".
            2 -> for (r in 0 until rows) cells[r] = blankRow()
            else -> {
                for (r in 0 until rows) cells[r] = blankRow()
                resetTranscript = true
            }
        }
        pendingWrap = false
    }

    /** `CSI K` — erase in line. */
    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> eraseRange(cursorRow, writeCol, cols)
            1 -> eraseRange(cursorRow, 0, writeCol + 1)
            else -> eraseRange(cursorRow, 0, cols)
        }
        pendingWrap = false
    }

    private fun eraseChars(n: Int, eraseStyle: Boolean) {
        eraseRange(cursorRow, writeCol, writeCol + n, eraseStyle)
    }

    private fun eraseRange(row: Int, from: Int, to: Int, eraseStyle: Boolean = true) {
        if (row !in 0 until rows) return
        val cellsOfRow = cells[row]
        for (c in from.coerceAtLeast(0) until to.coerceAtMost(cols)) {
            val style = if (eraseStyle) this.style else cellsOfRow[c].style
            cellsOfRow[c] = TerminalCell(style = style)
        }
    }

    private fun insertChars(n: Int) {
        val start = writeCol
        pendingWrap = false
        if (start >= cols) return
        val row = cells[cursorRow]
        val shift = n.coerceAtMost(cols - start)
        for (c in cols - 1 downTo start + shift) row[c] = row[c - shift]
        for (c in start until start + shift) row[c] = TerminalCell(style = style)
    }

    private fun deleteChars(n: Int) {
        val start = writeCol
        pendingWrap = false
        if (start >= cols) return
        val row = cells[cursorRow]
        val shift = n.coerceAtMost(cols - start)
        for (c in start until cols - shift) row[c] = row[c + shift]
        for (c in cols - shift until cols) row[c] = TerminalCell(style = style)
    }

    /** `CSI L` (atCursor) or `CSI S`: pull the region up and blank the bottom. */
    private fun scrollRegionUp(n: Int, atCursor: Boolean) {
        val top = if (atCursor) cursorRow else scrollTop
        repeat(n.coerceAtMost(scrollBottom - top + 1)) {
            // Only a full scroll (cursor on the bottom line of the screen) pushes a line into
            // the transcript; a program scrolling a sub-region is redrawing, not scrolling.
            for (r in top until scrollBottom) cells[r] = cells[r + 1]
            cells[scrollBottom] = blankRow()
        }
    }

    /** `CSI M` (atCursor) or `CSI T`: push the region down and blank the top. */
    private fun scrollRegionDown(n: Int, atCursor: Boolean) {
        val top = if (atCursor) cursorRow else scrollTop
        repeat(n.coerceAtMost(scrollBottom - top + 1)) {
            for (r in scrollBottom downTo top + 1) cells[r] = cells[r - 1]
            cells[top] = blankRow()
        }
    }

    private fun setScrollRegion(args: List<Int>) {
        val top = ((args.getOrNull(0) ?: 1) - 1).coerceIn(0, rows - 1)
        val bottom = ((args.getOrNull(1) ?: rows) - 1).coerceIn(0, rows - 1)
        if (top < bottom) {
            scrollTop = top
            scrollBottom = bottom
        } else {
            scrollTop = 0
            scrollBottom = rows - 1
        }
        cursorRow = scrollTop
        cursorCol = 0
        pendingWrap = false
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

    private fun saveCursor() {
        savedCursor = SavedCursor(cursorRow, cursorCol, style, autoWrap)
    }

    private fun restoreCursor() {
        val saved = savedCursor ?: return
        cursorRow = saved.row.coerceIn(0, rows - 1)
        cursorCol = saved.col.coerceIn(0, cols - 1)
        style = saved.style
        autoWrap = saved.autoWrap
        pendingWrap = false
    }

    private fun reply(text: String) {
        replies += text.toByteArray(Charsets.US_ASCII)
    }

    /** Handle one non-surrogate character while in the text state. */
    private fun groundChar(c: Char) {
        when {
            c == '\u001b' -> state = State.ESC
            c == '\r' -> {
                cursorCol = 0
                pendingWrap = false
            }

            c == '\n' -> lineFeed()
            c == '\b' -> {
                cursorCol = (cursorCol - 1).coerceAtLeast(0)
                pendingWrap = false
            }

            c == '\t' -> {
                val next = ((cursorCol / TAB_WIDTH) + 1) * TAB_WIDTH
                cursorCol = next.coerceAtMost(cols - 1)
                pendingWrap = false
            }

            c.code < 0x20 || c.code == 0x7f -> Unit // other C0 controls carry no text
            else -> putCodePoint(c.code)
        }
    }

    /** Writes one code point, honouring double-width cells and the deferred wrap. */
    private fun putCodePoint(cp: Int) {
        // Nerd-font glyphs: no glyph on the phone font, and printing them shows tofu.
        if (cp in PRIVATE_USE_START..PRIVATE_USE_END) return
        if (cp in 0xF0000..0xFFFFD || cp in 0x100000..0x10FFFD) return
        val width = widthOf(cp)
        if (width == 0) {
            appendCombining(cp)
            return
        }
        if (pendingWrap && autoWrap) {
            cursorCol = 0
            lineFeed()
        }
        // A double-width character cannot straddle the right edge; wrap before it instead.
        if (width == 2 && cursorCol == cols - 1) {
            cursorCol = 0
            lineFeed()
        }
        val text = String(Character.toChars(cp))
        val row = cells[cursorRow]
        // Clobbering half of a wide pair leaves a stray glyph; blank both halves first.
        clearWideAt(row, cursorCol)
        if (width == 2) {
            clearWideAt(row, cursorCol + 1)
            row[cursorCol] = TerminalCell(text = text, style = style)
            row[cursorCol + 1] = TerminalCell(text = "", style = style, continuation = true)
        } else {
            row[cursorCol] = TerminalCell(text = text, style = style)
        }
        cursorCol += width
        if (cursorCol >= cols) {
            cursorCol = cols - 1
            pendingWrap = true
        }
    }

    /** Zero-width marks (accents, ZWJ) attach to the previous cell instead of taking one. */
    private fun appendCombining(cp: Int) {
        val col = if (pendingWrap) cols - 1 else cursorCol - 1
        if (col < 0) return
        val row = cells[cursorRow]
        val cell = row[col]
        if (cell.continuation) return
        row[col] = cell.copy(text = cell.text + String(Character.toChars(cp)))
    }

    private fun clearWideAt(row: Array<TerminalCell>, col: Int) {
        if (col !in 0 until cols) return
        val cell = row[col]
        if (!cell.continuation && cell.text.length == 1) {
            // Occupied by the left half of a wide glyph? Blank its right half too.
            if (col + 1 < cols && row[col + 1].continuation) row[col + 1] = TerminalCell()
        } else if (cell.continuation && col > 0) {
            row[col - 1] = TerminalCell()
        }
    }

    private fun lineFeed() {
        pendingWrap = false
        if (cursorRow == scrollBottom) {
            // A scroll of the whole screen is the only one that leaves the visible area for
            // the transcript; an alt-screen program's scrolling is thrown away on exit.
            if (!inAltScreen && scrollTop == 0) scrolled += renderRow(scrollTop)
            for (r in scrollTop until scrollBottom) cells[r] = cells[r + 1]
            cells[scrollBottom] = blankRow()
        } else if (cursorRow < rows - 1) {
            cursorRow += 1
        }
    }

    private fun enterAltScreen() {
        if (inAltScreen) return
        primary = Snapshot(
            cells = Array(rows) { r -> cells[r].copyOf() },
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            style = style,
            scrollTop = scrollTop,
            scrollBottom = scrollBottom,
            autoWrap = autoWrap,
        )
        clearScreen()
        cursorRow = 0
        cursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        pendingWrap = false
    }

    private fun exitAltScreen() {
        val saved = primary ?: return
        primary = null
        for (r in 0 until rows) cells[r] = saved.cells[r].copyOf()
        cursorRow = saved.cursorRow.coerceIn(0, rows - 1)
        cursorCol = saved.cursorCol.coerceIn(0, cols - 1)
        style = saved.style
        scrollTop = saved.scrollTop
        scrollBottom = saved.scrollBottom
        autoWrap = saved.autoWrap
        pendingWrap = false
    }

    private fun clearScreen() {
        for (r in 0 until rows) cells[r] = blankRow()
        cursorRow = 0
        cursorCol = 0
        pendingWrap = false
    }

    private fun fullReset() {
        primary = null
        clearScreen()
        style = TerminalStyle.DEFAULT
        autoWrap = true
        cursorVisible = true
        scrollTop = 0
        scrollBottom = rows - 1
        savedCursor = null
        resetTranscript = true
    }

    private fun blankRow(): Array<TerminalCell> = Array(cols) { TerminalCell() }

    /**
     * The grid as lines, top row first. On the primary screen trailing blank rows are dropped so
     * the transcript does not end in a wall of empty space; the cursor row is always included.
     */
    private fun screenLines(): List<TerminalLine> {
        val last = if (inAltScreen) {
            rows - 1
        } else {
            val used = cells.indexOfLast { row -> row.any { it.text.isNotBlank() } }
            maxOf(used, cursorRow).coerceIn(0, rows - 1)
        }
        return (0..last).map { renderRow(it) }
    }

    /**
     * A copy of this emulator at another size, carrying the cells both grids share.
     *
     * Used for a live window-change: a TUI repaints over this immediately, and a shell keeps
     * whatever still fits instead of losing its visible prompt. Scroll region and the saved
     * cursor are dropped — both are coordinates on the old grid.
     */
    fun resized(newCols: Int, newRows: Int): TerminalScreen {
        val next = TerminalScreen(newCols, newRows)
        copyInto(next, cells)
        primary?.let { saved -> copyInto(next, saved) }
        next.style = style
        next.autoWrap = autoWrap
        next.cursorVisible = cursorVisible
        next.bracketedPaste = bracketedPaste
        next.usesCursorAddressing = usesCursorAddressing
        next.cursorRow = cursorRow.coerceIn(0, newRows - 1)
        next.cursorCol = cursorCol.coerceIn(0, newCols - 1)
        return next
    }

    private fun copyInto(target: TerminalScreen, saved: Snapshot) {
        copyInto(target, saved.cells)
        target.primary = Snapshot(
            cells = copyCells(saved.cells, target.cols, target.rows),
            cursorRow = saved.cursorRow.coerceIn(0, target.rows - 1),
            cursorCol = saved.cursorCol.coerceIn(0, target.cols - 1),
            style = saved.style,
            scrollTop = 0,
            scrollBottom = target.rows - 1,
            autoWrap = saved.autoWrap,
        )
    }

    private fun copyInto(target: TerminalScreen, from: Array<Array<TerminalCell>>) {
        val copied = copyCells(from, target.cols, target.rows)
        for (r in copied.indices) target.cells[r] = copied[r]
    }

    private fun copyCells(
        from: Array<Array<TerminalCell>>,
        targetCols: Int,
        targetRows: Int,
    ): Array<Array<TerminalCell>> {
        val out = Array(targetRows) { Array(targetCols) { TerminalCell() } }
        for (r in 0 until minOf(from.size, targetRows)) {
            for (c in 0 until minOf(from[r].size, targetCols)) out[r][c] = from[r][c]
        }
        return out
    }

    /** One grid row → a styled line with trailing blanks trimmed. */
    internal fun renderRow(row: Int): TerminalLine {
        val cellsOfRow = cells[row]
        val text = StringBuilder(cols)
        val styles = ArrayList<TerminalStyle>(cols)
        for (c in 0 until cols) {
            val cell = cellsOfRow[c]
            if (cell.continuation) continue
            text.append(cell.text)
            repeat(cell.text.length) { styles += cell.style }
        }
        var end = text.length
        while (end > 0 && text[end - 1] == ' ') end--
        if (end == 0) return TerminalLine.EMPTY
        return TerminalLine(text.substring(0, end), TerminalLine.spansOf(styles, end))
    }

    private data class SavedCursor(
        val row: Int,
        val col: Int,
        val style: TerminalStyle,
        val autoWrap: Boolean,
    )

    private data class Snapshot(
        val cells: Array<Array<TerminalCell>>,
        val cursorRow: Int,
        val cursorCol: Int,
        val style: TerminalStyle,
        val scrollTop: Int,
        val scrollBottom: Int,
        val autoWrap: Boolean,
    )

    private companion object {
        const val TAB_WIDTH = 8
        const val PRIVATE_USE_START = 0xE000
        const val PRIVATE_USE_END = 0xF8FF

        /** VT220-class answer: 62 = VT220, plus ANSI colour, printing and selective erase. */
        const val DA1_RESPONSE = "\u001b[?62;1;2;6;9;15;22c"
        const val XTVERSION_RESPONSE = "\u001bP>|RikkaTerm 1.0\u001b\\"
        const val OSC11_RESPONSE = "\u001b]11;rgb:0000/0000/0000\u001b\\"

        /** East-Asian-width approximation: 2 for CJK/Hangul/full-width, 0 for marks, else 1. */
        fun widthOf(cp: Int): Int = when {
            cp < 0x20 -> 0
            cp in 0x0300..0x036F || cp in 0x1AB0..0x1AFF || cp in 0x1DC0..0x1DFF -> 0
            cp in 0x200B..0x200F || cp in 0xFE00..0xFE0F || cp in 0xFE20..0xFE2F -> 0
            cp in 0x1100..0x115F -> 2
            cp in 0x2E80..0xA4CF -> 2
            cp in 0xAC00..0xD7A3 -> 2
            cp in 0xF900..0xFAFF -> 2
            cp in 0xFE30..0xFE4F -> 2
            cp in 0xFF00..0xFF60 -> 2
            cp in 0xFFE0..0xFFE6 -> 2
            cp in 0x1F300..0x1F64F || cp in 0x1F900..0x1F9FF -> 2
            cp in 0x20000..0x3FFFD -> 2
            else -> 1
        }
    }
}
