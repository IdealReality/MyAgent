package me.rerere.myagent.data.ai.mcp.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * mcp_add / mcp_update 的 ssh_stdio 分支：原来这两种传输被显式拒绝，模型连「给用户配一台
 * SSH 外部工具」都做不到。这里钉住三个边界，避免以后又把 url 变成硬性必填。
 */
class McpSshAddArgsTest {
    @Test
    fun `ssh host is required`() {
        val (code, _) = validateSshParams(SshParams(host = "", command = "node x"))!!
        assertEquals("invalid_ssh_host", code)
    }

    @Test
    fun `command is required`() {
        val (code, _) = validateSshParams(SshParams(host = "arch", command = "  "))!!
        assertEquals("invalid_command", code)
    }

    @Test
    fun `complete ssh params pass and keep optional root`() {
        assertNull(
            validateSshParams(
                SshParams(host = "arch", command = "node /srv/stdio.mjs", root = "/srv", windowsShell = false),
            ),
        )
    }
}
