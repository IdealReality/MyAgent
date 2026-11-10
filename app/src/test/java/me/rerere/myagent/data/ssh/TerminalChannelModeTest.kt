package me.rerere.myagent.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 持久模式真正依赖的只有两件事：会话名稳定、远端命令行能被执行。
 *
 * 后者错一个字符就是一个「连上了但什么都没发生」的黑屏，而它只在真机上、只在某一种登录
 * shell 下才会现形——所以把命令当成数据钉在这里。
 */
class TerminalChannelModeTest {

    @Test
    fun `mux name is stable and safe for shell and mux`() {
        val id = "8f14e45f-ceea-467a-9575-9b1b6d2a1c3d"
        val name = muxSessionName(id)

        assertEquals(name, muxSessionName(id))
        assertEquals("myagent-8f14e45fceea", name)
        // tmux/screen 的会话名和 shell 字面量里都必须安全。
        assertTrue(name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' })
        assertFalse(name.contains("'"))
    }

    @Test
    fun `mux name never comes out empty`() {
        assertEquals("myagent-tab", muxSessionName(""))
        assertEquals("myagent-tab", muxSessionName("---"))
        assertEquals("myagent-abc", muxSessionName("ABC"))
    }

    @Test
    fun `attach command is wrapped in a posix sh that fish can pass through`() {
        val command = persistentAttachCommand("myagent-abc123")

        // sshd 用 `$SHELL -c <command>`，而 $SHELL 可能是 fish（arch 实测）：不包一层 sh，
        // `if … then … fi` 会在解析阶段就失败。
        assertTrue(command.startsWith("sh -c '"))
        assertTrue(command.endsWith("'"))
        val script = command.removePrefix("sh -c '").removeSuffix("'")
        // 整段被单引号包住，脚本里再出现单引号就会把引号提前闭合。
        assertFalse(script.contains("'"))
    }

    @Test
    fun `attach command prefers tmux then screen then abduco`() {
        val script = persistentAttachCommand("myagent-abc123")

        val tmux = script.indexOf("exec tmux new -A -D -s myagent-abc123")
        val screen = script.indexOf("exec screen -D -RR myagent-abc123")
        val abduco = script.indexOf("exec abduco -A myagent-abc123")
        assertTrue(tmux > 0)
        assertTrue(tmux < screen)
        assertTrue(screen < abduco)
        // tmux 的 -D 会踢掉掉线时残留的旧客户端，新客户端才能拿到自己的行列数。
        assertTrue(script.contains("-A -D"))
    }

    @Test
    fun `attach command explains itself when no mux exists`() {
        val script = persistentAttachCommand("myagent-abc123")

        assertTrue(script.contains("没有 tmux / screen / abduco"))
        assertTrue(script.contains("exit 127"))
    }

    @Test
    fun `attach command ignores anything that could break the quoting`() {
        val script = persistentAttachCommand("bad name'; rm -rf /; #")
        val inner = script.removePrefix("sh -c '").removeSuffix("'")

        assertFalse(inner.contains("rm -rf"))
        assertFalse(inner.contains("'"))
        // 引号、分号、斜杠、空格全部被丢掉；`-` 是安全的，保留。
        assertTrue(inner.contains("exec tmux new -A -D -s badnamerm-rf"))
    }

    @Test
    fun `only the persistent mode keeps autoConnect after a clean exit`() {
        // 普通 shell 里的 `exit` 是真的关掉通道；持久模式的 `exit` 是 detach，远端会话可能还在。
        assertFalse(keepsAutoConnectAfterCleanExit(TerminalChannelMode.Direct))
        assertTrue(keepsAutoConnectAfterCleanExit(TerminalChannelMode.Persistent))
    }

    @Test
    fun `default mode keeps the previous behaviour`() {
        assertEquals(TerminalChannelMode.Direct, TerminalChannelMode.DEFAULT)
    }
}
