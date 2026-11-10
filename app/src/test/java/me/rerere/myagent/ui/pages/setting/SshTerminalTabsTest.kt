package me.rerere.myagent.ui.pages.setting

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Several channels to the same host are legitimate, so the tab bar must still tell them apart.
 */
class SshTerminalTabsTest {

    @Test
    fun `first tab keeps the host name`() {
        assertEquals("arch", uniqueTabTitle("arch", emptyList()))
    }

    @Test
    fun `second tab to the same host is numbered`() {
        assertEquals("arch 2", uniqueTabTitle("arch", listOf("arch")))
        assertEquals("arch 4", uniqueTabTitle("arch", listOf("arch", "arch 2", "arch 3")))
    }

    @Test
    fun `a numbered name can be reused once its tab is gone`() {
        // Closing "arch 2" frees the name; the next tab takes the free slot, not "arch 3".
        assertEquals("arch 2", uniqueTabTitle("arch", listOf("arch", "arch 3")))
    }

    @Test
    fun `pty grid follows the phone width instead of a desktop 100 columns`() {
        // 1080×2800 at density 3: a 21.6px Monospace cell leaves room for 47 columns, which is
        // what keeps the shell's line width and the page's line width the same.
        assertEquals(PtyGrid(columns = 47, rows = 58), ptyGrid(1080, 2800, 3f))
    }

    @Test
    fun `pty grid clamps absurd geometry`() {
        assertEquals(PtyGrid(columns = 20, rows = 10), ptyGrid(10, 10, 3f))
        assertEquals(PtyGrid(columns = 240, rows = 200), ptyGrid(100_000, 100_000, 1f))
    }
}
