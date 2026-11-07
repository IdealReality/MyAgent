package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The SSH terminal no longer has a composer: the page forwards the invisible field's edits and
 * hardware keys straight to the PTY. Both halves are pure functions, so the parts that are easy
 * to get subtly wrong — a swallowed backspace, an IME composition leaking pinyin to the shell,
 * a key that would be sent twice — are pinned here without an Android runtime.
 */
class TerminalInputTest {

    @Test
    fun `typing past the shadow sends only the new characters`() {
        assertEquals(TerminalEdit.Append("l"), imeEdit("l", shadow = "", composing = false))
        assertEquals(TerminalEdit.Append("s -la"), imeEdit("ls -la", shadow = "l", composing = false))
    }

    @Test
    fun `a space is ordinary input`() {
        assertEquals(TerminalEdit.Append(" "), imeEdit("ls ", shadow = "ls", composing = false))
    }

    @Test
    fun `newline from the IME return key reaches the shell`() {
        assertEquals(TerminalEdit.Append("\n"), imeEdit("ls\n", shadow = "ls", composing = false))
    }

    @Test
    fun `deleting is a shrink, not a lost keystroke`() {
        // With an empty field a backspace produces no change at all, which is why the diff is
        // taken against the shadow of what the shell already has.
        assertEquals(TerminalEdit.Backspace, imeEdit("ls", shadow = "ls ", composing = false))
        assertEquals(TerminalEdit.Backspace, imeEdit("", shadow = "l", composing = false))
    }

    @Test
    fun `a composing IME is not forwarded character by character`() {
        // Pinyin "ni" must not be typed into the shell; the commit arrives with composition null.
        assertEquals(TerminalEdit.Buffer, imeEdit("ni", shadow = "", composing = true))
        assertEquals(TerminalEdit.Append("你"), imeEdit("你", shadow = "", composing = false))
    }

    @Test
    fun `an IME rewriting the buffer is resynced instead of replayed`() {
        assertEquals(TerminalEdit.Resync, imeEdit("x", shadow = "ls -la", composing = false))
    }

    @Test
    fun `cursor keys send xterm sequences`() {
        assertArrayEquals(esc("[A"), terminalKeyBytes(Key.DirectionUp, ctrlPressed = false))
        assertArrayEquals(esc("[B"), terminalKeyBytes(Key.DirectionDown, ctrlPressed = false))
        assertArrayEquals(esc("[C"), terminalKeyBytes(Key.DirectionRight, ctrlPressed = false))
        assertArrayEquals(esc("[D"), terminalKeyBytes(Key.DirectionLeft, ctrlPressed = false))
        assertArrayEquals(esc("[H"), terminalKeyBytes(Key.MoveHome, ctrlPressed = false))
        assertArrayEquals(esc("[F"), terminalKeyBytes(Key.MoveEnd, ctrlPressed = false))
        assertArrayEquals(esc("[3~"), terminalKeyBytes(Key.Delete, ctrlPressed = false))
        assertArrayEquals(esc("[5~"), terminalKeyBytes(Key.PageUp, ctrlPressed = false))
        assertArrayEquals(esc("[6~"), terminalKeyBytes(Key.PageDown, ctrlPressed = false))
    }

    @Test
    fun `tab and escape are single control bytes`() {
        assertArrayEquals(byteArrayOf(0x09), terminalKeyBytes(Key.Tab, ctrlPressed = false))
        assertArrayEquals(byteArrayOf(0x1b), terminalKeyBytes(Key.Escape, ctrlPressed = false))
    }

    @Test
    fun `ctrl letters map to control bytes`() {
        assertArrayEquals(byteArrayOf(0x03), terminalKeyBytes(Key.C, ctrlPressed = true))
        assertArrayEquals(byteArrayOf(0x04), terminalKeyBytes(Key.D, ctrlPressed = true))
        assertArrayEquals(byteArrayOf(0x1a), terminalKeyBytes(Key.Z, ctrlPressed = true))
    }

    @Test
    fun `enter and backspace stay with the text field`() {
        // Both already arrive as text edits (newline / sentinel deletion); intercepting them
        // here would send every keystroke twice.
        assertNull(terminalKeyBytes(Key.Enter, ctrlPressed = false))
        assertNull(terminalKeyBytes(Key.Backspace, ctrlPressed = false))
        assertNull(terminalKeyBytes(Key.Spacebar, ctrlPressed = false))
    }

    private fun esc(rest: String): ByteArray = ("\u001b" + rest).toByteArray()
}
