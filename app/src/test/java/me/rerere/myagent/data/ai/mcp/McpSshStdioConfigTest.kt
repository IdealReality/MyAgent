package me.rerere.myagent.data.ai.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * ssh_stdio 是唯一「连接参数不在 URL 里」的传输：host、命令、工作目录、远端 shell 全部
 * 决定实际连到什么东西。两个不变量必须成立，否则用户改了命令却复用旧连接、或配置在
 * 落盘后丢掉字段。
 */
class McpSshStdioConfigTest {
    private val base = McpServerConfig.SshStdioServer(
        commonOptions = McpCommonOptions(name = "omp-ssh"),
        sshHost = "windows",
        command = "node C:/Users/guest/bin/agent-bridge/stdio.mjs --prefix omp --agent omp",
        root = null,
        windowsShell = true,
    )

    @Test
    fun `config survives a serialization round trip`() {
        val json = Json { encodeDefaults = true }
        val text = json.encodeToString<McpServerConfig>(base)
        val decoded = json.decodeFromString<McpServerConfig>(text)
        assertEquals(base, decoded)
    }

    @Test
    fun `every connection-shaping field changes the connection key`() {
        val key = base.connectionKey()
        assertNotEquals(key, base.copy(sshHost = "arch").connectionKey())
        assertNotEquals(key, base.copy(command = "node /srv/stdio.mjs").connectionKey())
        assertNotEquals(key, base.copy(root = "/home/user").connectionKey())
        assertNotEquals(key, base.copy(windowsShell = false).connectionKey())
    }

    @Test
    fun `ssh transport is distinct from http transports with the same name`() {
        val http = McpServerConfig.StreamableHTTPServer(
            commonOptions = base.commonOptions,
            url = "http://192.168.0.10:8933/omp/mcp",
        )
        assertNotEquals(http.connectionKey(), base.connectionKey())
        assertEquals("ssh://windows", base.serverUrl)
    }
}
