package me.rerere.myagent.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The SSH terminal tabs are restored from disk on start-up, so the store is the only thing
 * standing between "my channels are still there" and an empty page. These pin the parts that
 * fail quietly: ordering, the tail-only trim, a corrupt file taking the whole list down, and
 * `delete` actually removing the file.
 */
class TerminalSessionStoreTest {

    private fun tempDir() = Files.createTempDirectory("ssh-terminals").toFile()

    private fun session(
        id: String,
        host: String = "arch",
        createdAt: Long = 0L,
        lines: List<String> = emptyList(),
    ) = SavedTerminalSession(
        id = id,
        hostName = host,
        title = host,
        createdAtMs = createdAt,
        updatedAtMs = createdAt,
        lines = lines,
    )

    @Test
    fun `saved tab is loaded back with its transcript`() {
        val dir = tempDir()
        val store = TerminalSessionStore(dir)
        store.save(session("a", createdAt = 1L, lines = listOf("$ ls", "file.txt")))
        store.save(session("b", host = "nas", createdAt = 2L, lines = listOf("$ uptime")))

        val loaded = store.loadAll()

        assertEquals(listOf("a", "b"), loaded.map { it.id })
        // The channel's name rides along with the channel, not with the host: reconnect and
        // app restart must not rename a tab the user recognises.
        assertEquals(listOf("arch", "nas"), loaded.map { it.title })
        assertEquals(listOf("$ ls", "file.txt"), loaded[0].lines)
        assertEquals("nas", loaded[1].hostName)
        assertEquals(listOf("$ uptime"), loaded[1].lines)
    }

    @Test
    fun `overwriting a tab does not leave a second copy`() {
        val dir = tempDir()
        val store = TerminalSessionStore(dir)
        store.save(session("a", lines = listOf("old")))
        store.save(session("a", lines = listOf("old", "new")))

        val loaded = store.loadAll()

        assertEquals(1, loaded.size)
        assertEquals(listOf("old", "new"), loaded.single().lines)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `delete removes the file so the tab does not come back`() {
        val dir = tempDir()
        val store = TerminalSessionStore(dir)
        store.save(session("a"))
        store.save(session("b"))

        store.delete("a")

        assertEquals(listOf("b"), store.loadAll().map { it.id })
    }

    @Test
    fun `a corrupt file only loses its own tab`() {
        val dir = tempDir()
        val store = TerminalSessionStore(dir)
        store.save(session("good", lines = listOf("$ whoami")))
        java.io.File(dir, "broken.json").writeText("{ not json")

        val loaded = store.loadAll()

        assertEquals(listOf("good"), loaded.map { it.id })
    }

    @Test
    fun `trim keeps the tail and caps very long lines`() {
        val lines = (0 until MAX_SAVED_TERMINAL_LINES + 50).map { "line $it" } + "x".repeat(9_000)
        val trimmed = session("a", lines = lines).trimmed()

        assertEquals(MAX_SAVED_TERMINAL_LINES, trimmed.lines.size)
        // The newest line survives, and it is capped rather than stored whole.
        assertEquals(MAX_SAVED_TERMINAL_LINE_CHARS, trimmed.lines.last().length)
        assertEquals("line 51", trimmed.lines.first())
    }

    @Test
    fun `a tab the user disconnected stays disconnected across a reload`() {
        val dir = tempDir()
        val store = TerminalSessionStore(dir)
        store.save(session("a", lines = listOf("$ exit")).copy(autoConnect = false))

        val loaded = store.loadAll().single()

        // 断开 is sticky: a restart must not dial a channel the user switched off.
        assertFalse(loaded.autoConnect)
    }

    @Test
    fun `the start command rides with the tab`() {
        val dir = tempDir()
        val store = TerminalSessionStore(dir)
        store.save(session("a").copy(startCommand = "tmux new -A -s omp"))

        val loaded = store.loadAll().single()

        // It has to survive a restart, otherwise the tab reattaches to nothing after every APK.
        assertEquals("tmux new -A -s omp", loaded.startCommand)
    }

    @Test
    fun `a tab without a start command stays a plain shell`() {
        val dir = tempDir()
        val store = TerminalSessionStore(dir)
        store.save(session("a"))

        assertEquals("", store.loadAll().single().startCommand)
    }

    @Test
    fun `a file written by an older version still loads`() {
        val dir = tempDir()
        dir.mkdirs()
        // Older versions stored two extra keys describing a remote session. They are gone;
        // the reader ignores unknown keys instead of dropping the whole channel.
        java.io.File(dir, "legacy.json").writeText(
            """{"id":"legacy","hostName":"arch","title":"arch","createdAtMs":1,"lines":[],""" +
                """"remoteSessionKey":"myagent-abc123","persistent":true}""",
        )

        val loaded = TerminalSessionStore(dir).loadAll().single()

        assertEquals("legacy", loaded.id)
        assertEquals("arch", loaded.hostName)
        assertTrue(loaded.autoConnect)
        // 老快照没有模式字段：缺省就是当时唯一的行为，不能凭空变成持久会话。
        assertEquals(TerminalChannelMode.Direct, loaded.mode)
        assertEquals("", loaded.muxName)
    }

    @Test
    fun `mode and mux name ride with the tab`() {
        val dir = tempDir()
        val store = TerminalSessionStore(dir)
        store.save(
            session("a").copy(
                mode = TerminalChannelMode.Persistent,
                muxName = "myagent-8f14e45fceea",
            ),
        )

        val loaded = store.loadAll().single()

        // 会话名变了，重连就会挂到另一条会话上：名字必须跟着通道落盘。
        assertEquals(TerminalChannelMode.Persistent, loaded.mode)
        assertEquals("myagent-8f14e45fceea", loaded.muxName)
    }

    @Test
    fun `ids cannot escape the store directory`() {
        val dir = tempDir()
        val store = TerminalSessionStore(dir)
        store.save(session("../../escape", lines = listOf("$ id")))

        assertEquals(listOf("../../escape"), store.loadAll().map { it.id })
        assertTrue(dir.listFiles()!!.all { it.parentFile == dir })
    }
}
