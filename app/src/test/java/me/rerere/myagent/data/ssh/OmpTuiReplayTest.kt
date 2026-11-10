package me.rerere.myagent.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Replays a real `omp` TUI paint (Ink/React, truecolor, 47×40 PTY) and pins the grid.
 *
 * `omp` is the full-screen client this terminal has to carry: it repaints by absolute addressing
 * (`CSI r;cH`), erases to end of line (`CSI K`), shrinks lines with `CSI nX` + `CSI nC`, and
 * enables bracketed paste. The expected grid was produced independently with `pyte`, so a failure
 * means our emulator drifted from a standard VT, not that omp changed its art.
 *
 * The one deliberate deviation from pyte is emoji width: U+1F5D1 (`🗑`, East-Asian width
 * "neutral") is one cell to a `wcwidth` table and two to every emoji-capable terminal and to the
 * JS width library the TUI laid itself out with. The stream is normalised to `x` for the
 * comparison, and [TerminalScreenTest] pins the two-cell rule on its own.
 */
class OmpTuiReplayTest {

    @Test
    fun `an omp tui paint renders cell for cell`() {
        val update = replay(normalisedBytes(), cols = 47, rows = 40)
        assertEquals(
            listOf(
                "                           ▒▒  ██             █",
                "                        ████████████",
                "                           ██  ██       ▄▀▀▄ █▀",
                "▄▀▄ █▀▀▄",
                "                           ██  ██       ▀▄▄▀ █",
                "█ █ █▄▄▀",
                "                           ▒▒  ██             █",
                "                               ██       v18.16.",
                "0",
                " xdev: xd://: mounted open_loops",
                "",
                " π > ◒ DeepSeek V4.1 Flash > x ~/tmp ▶─2%──────",
                "──────────────────────────────1M─",
                "╰─",
                "     ⇧⇥ to change thinking effort",
            ),
            update.screen.map { it.text.trimEnd() },
        )
        assertEquals(12, update.cursorRow)
        assertEquals(3, update.cursorCol)
    }

    private fun normalisedBytes(): ByteArray =
        String(resource("ompstream.bin"), Charsets.UTF_8)
            .replace("\uD83D\uDDD1", "x")
            .toByteArray(Charsets.UTF_8)

    /**
     * The same TUI mid-conversation: a tool-output box drawn with box-drawing characters, CJK
     * body text, a braille spinner, `CSI 80C` past the right edge, bold on/off, and rows wrapped
     * by the remote rather than by us.
     *
     * Rows 32–33 are the interesting ones: the 47th column falls inside a two-cell glyph, so the
     * whole glyph moves to the next row and the cell before it stays blank. The expected grid here
     * is `tmux 3.8`'s own rendering of the same bytes (`capture-pane`), not pyte's — pyte lets a
     * wide glyph straddle the right edge and reports 48 cells of text in a 47-column pane.
     */
    @Test
    fun `a conversation frame with a tool box renders cell for cell`() {
        val update = replay(
            String(resource("ompmenu.bin"), Charsets.UTF_8)
                .replace("\uD83D\uDDD1", "x")
                .toByteArray(Charsets.UTF_8),
            cols = 47,
            rows = 40,
        )
        assertEquals(
            listOf(
                "ly a quick scan of omp:// could",
                " give correct command list like /mem, /loops, /",
                "model, /doctor. Let me try",
                " reading omp://.",
                "",
                "╭─── ✔ Read omp:// ────────────────────────────",
                "────────────────────────────────╮",
                "│ Documentation                               │",
                "│",
                "                                │",
                "│ 133 files available:                        │",
                "│",
                "                                │",
                "│ - ERRATA-GPT5-HARMONY.md (omp://ERRATA-GPT5-H",
                "ARMONY.md)                      │",
                "│ - adding-a-provider.md (omp://adding-a-provid",
                "er.md)                          │",
                "│ - advisor-watchdog.md (omp://advisor-watchdog",
                ".md)                            │",
                "│ - agent-hub.md (omp://agent-hub.md)         │",
                "│ - ai-schema-normalize.md (omp://ai-schema-nor",
                "malize.md)                      │",
                "│ - approval-mode.md (omp://approval-mode.md) │",
                "│ - auth-broker-gateway.md (omp://auth-broker-g",
                "ateway.md)                      │",
                "│ - bash-tool-runtime.md (omp://bash-tool-runti",
                "me.md)                          │",
                "│ … 133 more lines ⟦Ctrl+O: Expand⟧",
                "                                │",
                "╰──────────────────────────────────────────────",
                "────────────────────────────────╯",
                "",
                " pace：本次进程冷启动约 5.55s（进程启动→开始等",
                "模型）。常驻会话（不加 -p，或用",
                " -c 续接）可省掉这段。",
                "",
                "  ⎋ Reading harness docs index",
                " ⠙ 3s > ◒ DeepSeek V4.1 Flash > x ~/tmp ▶─2%───",
                "──────────────────────────────1M─",
                "╰─",
            ),
            update.screen.map { it.text.trimEnd() },
        )
        assertEquals(39, update.cursorRow)
        assertEquals(3, update.cursorCol)
    }

    private fun replay(bytes: ByteArray, cols: Int, rows: Int): TerminalScreen.Update {
        val screen = TerminalScreen(cols = cols, rows = rows)
        var last: TerminalScreen.Update? = null
        bytes.toList().chunked(5).forEach { chunk ->
            last = screen.feed(chunk.toByteArray())
        }
        return last!!
    }

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()
}
