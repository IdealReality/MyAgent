package me.rerere.myagent.data.ai.mcp

import android.content.Context
import com.jcraft.jsch.Session
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import me.rerere.myagent.data.ai.tools.local.openRemoteSession
import me.rerere.myagent.data.db.entity.SshHostEntity
import me.rerere.myagent.data.ssh.RemoteExecChannel

/**
 * 一条 SSH `exec` 通道上的 MCP stdio 连接。
 *
 * 传输本身由 SDK 的 [StdioClientTransport] 负责（它接受任意 `kotlinx.io.Source/Sink`，不要求
 * 本地子进程）；这里只负责把 JSch 通道的流接上去，并保证三种资源按依赖顺序释放：
 * transport → channel → session。顺序反了，JSch 的读线程会卡在已经断开的 socket 上。
 */
internal class SshMcpConnection(
    private val session: Session,
    private val channel: RemoteExecChannel,
    val transport: AbstractTransport,
) {
    /** 远端进程写到 stderr 的尾巴——它死掉时这是唯一有用的信息。 */
    val stderrTail: String get() = channel.stderrSnapshot()

    suspend fun close() {
        try {
            transport.close()
        } catch (_: Throwable) {
            // 通道可能已被远端关闭；资源下面继续释放。
        }
        runCatching { channel.close() }
        runCatching { session.disconnect() }
    }
}

/**
 * 打开一条免 token 的 MCP 连接。
 *
 * 认证完全交给已保存的 SSH 主机（密钥/密码），因此没有 bearer token、没有监听端口、也没有
 * 局域网依赖：只要 `ssh_exec_saved` 能连上这台主机，这条路径就能连上。
 */
internal suspend fun openSshMcpConnection(
    context: Context,
    host: SshHostEntity,
    config: McpServerConfig.SshStdioServer,
): SshMcpConnection {
    require(config.command.isNotBlank()) { "ssh_stdio: command is blank" }
    val session = openRemoteSession(context, host)
    try {
        val channel = if (config.windowsShell) {
            RemoteExecChannel.startRaw(session, config.command)
        } else {
            RemoteExecChannel.start(session, config.root, config.command)
        }
        val transport = StdioClientTransport(
            input = channel.stdout.asSource().buffered(),
            output = channel.stdin.asSink().buffered(),
            // stderr 已被 RemoteExecChannel 的后台线程独占消费，这里不能再接管同一个流。
            error = null,
        )
        return SshMcpConnection(session, channel, transport)
    } catch (t: Throwable) {
        runCatching { session.disconnect() }
        throw t
    }
}
