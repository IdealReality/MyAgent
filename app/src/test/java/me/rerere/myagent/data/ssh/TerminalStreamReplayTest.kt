package me.rerere.myagent.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Replays a real PowerShell-over-SSH byte stream (captured from `pwsh 7.6.9` with ConPTY) and
 * pins the rendered grid.
 *
 * The stream is ordinary Windows output: CRLF line endings, `ESC [?9001h`/`[?1004h`, private
 * window titles, `ESC [2J ESC [H` on start-up, `ESC [15X`, `ESC [6;1H`. Rendering it used to be
 * checked only by eye, and "it looks fine on the phone" was the whole test. The expected grid was
 * produced independently with `pyte`, so this now fails if the emulator drifts from a standard
 * VT implementation rather than from whatever the app happened to draw.
 */
class TerminalStreamReplayTest {

    @Test
    fun `a windows powershell session renders cell for cell`() {
        val update = replay("winstream.bin")
        assertEquals(
            listOf(
                "    Directory: C:\\",
                "",
                "Mode                 LastWriteTime         Length Name",
                "----                 -------------         ------ ----",
                "d----          2026/10/29    22:08                ae-bridge",
                "d----          2026/10/30     5:53                AI",
                "d----           2026/11/2     4:51                build",
                "d----           2026/11/4    11:08                BuildTools",
                "",
                // Captured while the client still submitted Enter as CRLF; PowerShell answers the
                // extra LF with a continuation prompt. Kept because the emulator must survive it
                // either way — the client-side fix lives in `terminalInputBytes`.
                "PS C:\\Users\\ZhouX> Write-Host \"中文测试 ok\"",
                ">>",
                "中文测试 ok",
                "PS C:\\Users\\ZhouX> Get-Process | Select-Object -First 2 | Format-Table -AutoSize",
                ">>",
                "",
                "NPM(K) PM(M) WS(M) CPU(s)     Id SI ProcessName",
                "------ ----- ----- ------     -- -- -----------",
                "    20  3.96 20.19  11.92 100252  1 2345PicWorker",
                "    11  1.73 11.91   0.06  17756  0 Aac3572DramHal_x86",
                "",
                "PS C:\\Users\\ZhouX>",
                ">>                 exit",
                ">>",
                "",
            ),
            update.screen.map { it.text.trimEnd() },
        )
        assertEquals(23, update.cursorRow)
        assertEquals(0, update.cursorCol)
    }

    @Test
    fun `clear-host redraws rather than dumping the grid into the transcript`() {
        // ConPTY answers `Clear-Host` with `ESC [H ESC [3J` and then repaints. Only that path may
        // drop the scrollback; the repaint itself must not.
        val update = replay("winstream_cr.bin")
        assertEquals(
            listOf(
                "                 > Write-Host \"after clear\"",
                "after clear",
                "PS C:\\Users\\ZhouX> exit",
                "",
            ),
            update.screen.map { it.text.trimEnd() },
        )
    }

    private fun replay(name: String): TerminalScreen.Update {
        val bytes = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()
        val screen = TerminalScreen(cols = 80, rows = 24)
        var last: TerminalScreen.Update? = null
        bytes.toList().chunked(5).forEach { chunk ->
            last = screen.feed(chunk.toByteArray())
        }
        return last!!
    }
}
