package me.rerere.rikkahub.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour that made the old terminal page unusable, pinned down without Android:
 * a UTF-8 character split across reads, `\r` as an in-place redraw rather than a newline,
 * ANSI/OSC sequences leaking into the transcript, and the DA1 query fish blocks ten seconds on.
 */
class TerminalScreenTest {

    private fun screen() = TerminalScreen()

    private fun feed(screen: TerminalScreen, text: String) =
        screen.feed(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `utf8 character split across reads is not corrupted`() {
        val screen = screen()
        val bytes = "中文".toByteArray(Charsets.UTF_8)

        // "中" is the first three bytes; the fourth starts "文" and must be held back.
        val first = screen.feed(bytes, 4)
        assertEquals("中", first.current)
        assertTrue(screen.hasPendingBytes())

        val second = screen.feed(bytes.copyOfRange(4, bytes.size), bytes.size - 4)
        assertEquals("中文", second.current)
        assertFalse(second.current.contains('\uFFFD'))
        assertFalse(screen.hasPendingBytes())
    }

    @Test
    fun `carriage return redraws the line instead of splitting it`() {
        val screen = screen()
        assertEquals("XYc", feed(screen, "abc\rXY").current)
    }

    @Test
    fun `newline commits the line and starts a new one`() {
        val screen = screen()
        val update = feed(screen, "one\ntwo\n")
        assertEquals(listOf("one", "two"), update.appended)
        assertEquals("", update.current)
    }

    @Test
    fun `colour sequences and window titles are dropped`() {
        val screen = screen()
        val update = feed(
            screen,
            "\u001b]0;[IdealRainb] ~\u0007\u001b[38;2;183;196;255mhello\u001b[0m \u001b[1mbold\u001b[0m"
        )
        assertEquals("hello bold", update.current)
    }

    @Test
    fun `osc spanning two reads is still swallowed`() {
        val screen = screen()
        feed(screen, "\u001b]0;half a ti")
        val update = feed(screen, "tle\u0007ok")
        assertEquals("ok", update.current)
    }

    @Test
    fun `private use glyphs from nerd fonts are removed`() {
        val screen = screen()
        val update = feed(screen, "\uE0B0\uE0B2 baka")
        assertEquals(" baka", update.current)
    }

    @Test
    fun `da1 query is answered`() {
        val screen = screen()
        val update = feed(screen, "\u001b[0c")
        assertEquals(1, update.replies.size)
        assertEquals("\u001b[?62;1;2;6;9;15;22c", String(update.replies[0], Charsets.US_ASCII))
    }

    @Test
    fun `da1 split across reads is answered once complete`() {
        val screen = screen()
        assertTrue(screen.feed("\u001b[".toByteArray()).replies.isEmpty())
        val update = screen.feed("0c".toByteArray())
        assertEquals(1, update.replies.size)
    }

    @Test
    fun `secondary device attributes are not answered as da1`() {
        val screen = screen()
        assertTrue(feed(screen, "\u001b[>0c").replies.isEmpty())
    }

    @Test
    fun `cursor position report is answered with the current column`() {
        val screen = screen()
        val update = feed(screen, "abc\u001b[6n")
        assertEquals("\u001b[1;4R", String(update.replies[0], Charsets.US_ASCII))
    }

    @Test
    fun `erase to end of line truncates at the cursor`() {
        val screen = screen()
        val update = feed(screen, "hello\rAB\u001b[K")
        assertEquals("AB", update.current)
    }

    @Test
    fun `cursor forward pads the gap`() {
        val screen = screen()
        val update = feed(screen, "\u001b[3Cx")
        assertEquals("   x", update.current)
    }

    @Test
    fun `clear screen requests a transcript reset`() {
        val screen = screen()
        val update = feed(screen, "stale\u001b[2Jfresh")
        assertTrue(update.clear)
        assertEquals("fresh", update.current)
    }

    @Test
    fun `a full prompt redraw renders as one line`() {
        val screen = screen()
        // Shape of what fish actually sends: prompt, then per-character cursor moves while
        // the user types. The old splitter turned this into one fragment per \r.
        val update = feed(
            screen,
            "╰─> \r\u001b[4C" + "e" + "\r\u001b[5C" + "c" + "\r\u001b[6C" + "h" + "\r\u001b[7C" + "o" +
                "\r\u001b[8C \r\u001b[9C" + "x"
        )
        assertEquals("╰─> echo x", update.current)
        assertTrue(update.appended.isEmpty())
    }
}
