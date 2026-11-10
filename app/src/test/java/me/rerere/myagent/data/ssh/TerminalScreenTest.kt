package me.rerere.myagent.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid emulator, pinned down without Android: what a full-screen program relies on
 * (cursor addressing, erasing, scrolling, the alternate screen, double-width cells) plus the
 * three bugs the old line renderer existed to fix (split UTF-8, `\r` redraws, blocking queries).
 */
class TerminalScreenTest {

    private fun screen(cols: Int = 10, rows: Int = 3) = TerminalScreen(cols, rows)

    private fun feed(screen: TerminalScreen, text: String) =
        screen.feed(text.toByteArray(Charsets.UTF_8))

    private fun text(update: TerminalScreen.Update): List<String> = update.screen.map { it.text }

    @Test
    fun `plain text lands on the first row`() {
        val update = feed(screen(), "hello")
        assertEquals(listOf("hello"), text(update))
        assertEquals(0, update.cursorRow)
        assertEquals(5, update.cursorCol)
        assertTrue(update.cursorVisible)
    }

    @Test
    fun `a newline moves the cursor down and scrolling pushes the top line out`() {
        val update = feed(screen(rows = 3), "a\r\nb\r\nc\r\nd")
        assertEquals(listOf("b", "c", "d"), text(update))
        assertEquals(listOf("a"), update.scrolled.map { it.text })
    }

    @Test
    fun `cursor addressing writes where the program asked`() {
        val update = feed(screen(cols = 10, rows = 3), "\u001b[2;3HX")
        assertEquals(listOf("", "  X"), text(update))
        assertEquals(1, update.cursorRow)
        assertEquals(3, update.cursorCol)
    }

    @Test
    fun `erase in line clears from the cursor and keeps the row`() {
        val update = feed(screen(), "abcdef\r\u001b[3C\u001b[K")
        // `CSI K` from column 3 leaves "abc" and drops the rest.
        assertEquals(listOf("abc"), text(update))
    }

    @Test
    fun `erase in display clears the grid but leaves the cursor`() {
        val update = feed(screen(), "one\r\ntwo\u001b[2J")
        // `CSI 2J` blanks the grid but leaves the cursor on row 1, so rows 0..1 stay listed.
        assertEquals(listOf("", ""), text(update))
        assertEquals(1, update.cursorRow)
    }

    @Test
    fun `insert and delete lines shift the region`() {
        val update = feed(screen(rows = 3), "a\r\nb\r\nc\u001b[1;1H\u001b[L")
        // Inserting a blank line at the top pushes a/b down and drops c off the bottom.
        assertEquals(listOf("", "a", "b"), text(update))
    }

    @Test
    fun `sgr colours are kept as spans`() {
        val update = feed(
            screen(cols = 20),
            "\u001b[38;2;183;196;255mhello\u001b[0m \u001b[1mbold\u001b[0m",
        )
        assertEquals("hello bold", update.screen.single().text)
        assertEquals(
            listOf(
                TerminalSpan(0, 5, TerminalStyle(fg = 0xB7C4FF)),
                TerminalSpan(6, 10, TerminalStyle(bold = true)),
            ),
            update.screen.single().spans,
        )
    }

    @Test
    fun `style survives a newline and applies to the line after it`() {
        val update = feed(screen(rows = 2), "\u001b[31mred\r\nstill red")
        assertEquals(TerminalStyle(fg = 0xE06C75), update.screen[0].spans.single().style)
        assertEquals(TerminalStyle(fg = 0xE06C75), update.screen[1].spans.single().style)
    }

    @Test
    fun `the alternate screen hides the primary content and restores it on exit`() {
        val screen = screen(cols = 20, rows = 3)
        feed(screen, "main")
        val alt = feed(screen, "\u001b[?1049hFULL SCREEN")
        // An alt screen paints the whole grid; the top row is what a program writes first.
        assertEquals("FULL SCREEN", alt.screen.first().text)

        val restored = feed(screen, "\u001b[?1049l")
        assertEquals("main", restored.screen.single().text)
        // An alt-screen program's scrolling must never leak into the transcript.
        assertTrue(restored.scrolled.isEmpty())
    }

    @Test
    fun `double-width characters occupy two columns`() {
        val update = feed(screen(cols = 6), "中A")
        assertEquals("中A", update.screen.single().text)
        // 中 is two cells wide, so A lands in column 2 and the cursor is at 3.
        assertEquals(3, update.cursorCol)
    }

    @Test
    fun `autowrap defers the wrap so a full row does not scroll early`() {
        val screen = screen(cols = 5, rows = 2)
        val update = feed(screen, "12345")
        assertEquals(listOf("12345"), text(update))
        assertTrue(update.scrolled.isEmpty())

        val wrapped = feed(screen, "6")
        assertEquals(listOf("12345", "6"), text(wrapped))
    }

    @Test
    fun `erasing to end of line must not eat the character in the last column`() {
        // Ink (omp, Claude Code, gemini-cli) paints its right edge as `… █` + `CSI K`. After a
        // write into the last column the cursor is one past it, so `CSI K` erases nothing; an
        // emulator that keeps the cursor *on* that cell wipes the last column of every status
        // bar and every full-width rule.
        val update = feed(screen(cols = 5, rows = 2), "abcde\u001b[K")
        assertEquals(listOf("abcde"), text(update))
        // Same for the erase-in-display form.
        val ed = feed(screen(cols = 5, rows = 2), "abcde\u001b[0J")
        assertEquals(listOf("abcde"), text(ed))
    }

    @Test
    fun `a cursor move after a full row cancels the deferred wrap`() {
        val screen = screen(cols = 5, rows = 2)
        feed(screen, "12345")
        // Moving the cursor back one column and printing must land on that column, not wrap.
        val update = feed(screen, "\u001b[D9")
        assertEquals(listOf("12349"), text(update))
    }

    @Test
    fun `emoji are two cells wide even when they are not in the CJK ranges`() {
        // U+1F5D1 has East-Asian width "neutral", so a wcwidth table calls it one cell, but every
        // emoji-capable terminal and every JS width library (what the TUI laid itself out with)
        // draws it two. Matching the *client's* measurement is what keeps the columns aligned.
        val update = feed(screen(cols = 6), "\uD83D\uDDD1x")
        assertEquals("\uD83D\uDDD1x", update.screen.single().text)
        assertEquals(3, update.cursorCol)
    }

    @Test
    fun `bracketed paste mode is tracked for the input side`() {
        val screen = screen()
        assertFalse(screen.bracketedPaste)
        feed(screen, "\u001b[?2004h")
        assertTrue(screen.bracketedPaste)
        feed(screen, "\u001b[?2004l")
        assertFalse(screen.bracketedPaste)
    }

    @Test
    fun `cursor addressing marks the program as a full-screen one`() {
        // Plain prompt output does not address cells; a TUI does from its first frame. Only the
        // latter may be told about a live window-change (it repaints, a shell does not).
        val prompt = screen()
        feed(prompt, "PS C:\\> ls\r\n")
        assertFalse(prompt.usesCursorAddressing)

        val tui = screen()
        feed(tui, "\u001b[2;3HX")
        assertTrue(tui.usesCursorAddressing)
    }

    @Test
    fun `a resized grid keeps the cells that still fit`() {
        val screen = screen(cols = 6, rows = 3)
        feed(screen, "abc\r\ndef\r\nghi")
        val wider = screen.resized(10, 3)
        val update = feed(wider, "")
        assertEquals(listOf("abc", "def", "ghi"), text(update))
        val shorter = screen.resized(6, 1)
        assertEquals(listOf("abc"), text(feed(shorter, "")))
    }

    @Test
    fun `cursor visibility follows the remote`() {
        val screen = screen()
        assertFalse(feed(screen, "\u001b[?25l").cursorVisible)
        assertTrue(feed(screen, "\u001b[?25h").cursorVisible)
    }

    @Test
    fun `utf8 character split across reads is not corrupted`() {
        val screen = screen()
        val bytes = "中文".toByteArray(Charsets.UTF_8)
        val first = screen.feed(bytes, 4)
        assertEquals("中", text(first).single())
        assertTrue(screen.hasPendingBytes())

        val second = screen.feed(bytes.copyOfRange(4, bytes.size), bytes.size - 4)
        assertEquals("中文", text(second).single())
        assertFalse(text(second).single().contains('\uFFFD'))
    }

    @Test
    fun `clearing the screen keeps the scrollback, clearing it wholesale does not`() {
        // A pty repaints itself with `ESC [H ESC [2J` after a window change; on Windows that
        // happens every time the keyboard opens. Treating ED2 as "wipe the transcript" made the
        // history disappear. Only ED3 (and RIS) may drop it.
        val screen = screen(cols = 10, rows = 3)
        var update = feed(screen, "one\r\ntwo\r\nthree\r\nfour\r\nfive")
        assertTrue(update.scrolled.isNotEmpty())

        update = feed(screen, "\u001b[H\u001b[2J")
        assertFalse(update.resetTranscript)

        update = feed(screen, "\u001b[3J")
        assertTrue(update.resetTranscript)
    }

    @Test
    fun `charset selection escapes do not leak their byte as text`() {
        // htop emits `ESC ( B` (select ASCII) around its meters; printing the trailing B was
        // what scattered stray letters through the bars.
        val update = feed(screen(cols = 20), "\u001b(Bhello\u001b%Gworld")
        assertEquals("helloworld", text(update).single())
    }

    @Test
    fun `window titles are dropped`() {
        val update = feed(screen(cols = 20), "\u001b]0;[IdealRainb] ~\u0007ok")
        assertEquals("ok", text(update).single())
    }

    @Test
    fun `private use glyphs from nerd fonts are removed`() {
        val update = feed(screen(cols = 10), "\uE0B0\uE0B2 baka")
        assertEquals(" baka", text(update).single())
    }

    @Test
    fun `da1 query is answered and secondary attributes are not`() {
        val screen = screen()
        val update = feed(screen, "\u001b[0c")
        assertEquals(1, update.replies.size)
        assertEquals("\u001b[?62;1;2;6;9;15;22c", String(update.replies[0], Charsets.US_ASCII))
        assertTrue(feed(screen, "\u001b[>0c").replies.isEmpty())
    }

    @Test
    fun `cursor position report answers the grid column`() {
        val update = feed(screen(), "abc\u001b[6n")
        assertEquals("\u001b[1;4R", String(update.replies[0], Charsets.US_ASCII))
    }

    @Test
    fun `a coloured line round-trips through the persisted ansi form`() {
        val line = TerminalLine(
            "ok",
            listOf(TerminalSpan(0, 1, TerminalStyle(fg = 0x98C379, bold = true))),
        )
        assertEquals(line, TerminalLine.fromAnsi(line.toAnsi()))
        assertEquals(TerminalLine("plain"), TerminalLine.fromAnsi("plain"))
    }
}
