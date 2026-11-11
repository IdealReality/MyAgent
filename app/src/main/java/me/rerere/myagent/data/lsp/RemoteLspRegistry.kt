package me.rerere.myagent.data.lsp

import com.jcraft.jsch.Session
import me.rerere.myagent.data.ssh.RemoteFileClient
import java.io.Closeable

/**
 * One live language server: the SSH session it runs on, the SFTP view of that same host, and the
 * protocol client.
 *
 * The SSH session is owned here (not by the client) because SFTP and the server share it: one
 * TCP connection, one handshake, one credential exchange, many channels.
 */
internal class RemoteLspHandle(
    val key: String,
    val hostName: String,
    val rootPath: String,
    val spec: LspServerSpec,
    val resolvedCommand: String,
    val session: Session,
    val files: RemoteFileClient,
    val client: RemoteLspClient,
) : Closeable {

    @Volatile
    var lastUsedAtMs: Long = System.currentTimeMillis()

    val alive: Boolean get() = client.processAlive

    override fun close() {
        runCatching { client.close() }
        runCatching { files.close() }
        runCatching { session.disconnect() }
    }
}

/**
 * Process-wide cache of language-server sessions.
 *
 * Idle servers are reaped rather than left running: a clangd or rust-analyzer on a small VPS
 * holds hundreds of megabytes and burns CPU on background indexing, and the agent only needs it
 * while it is actively navigating code. Reaping is checked at each tool call instead of on a
 * timer so the app needs no extra coroutine scope just to hold a sweep loop.
 */
internal object RemoteLspRegistry {

    /** Long enough to cover a burst of tool calls, short enough to free a small server. */
    const val IDLE_TIMEOUT_MS: Long = 15 * 60 * 1000L

    private val lock = Any()
    private val handles = LinkedHashMap<String, RemoteLspHandle>()

    fun key(hostName: String, rootPath: String, spec: LspServerSpec): String =
        "$hostName\u0000$rootPath\u0000${spec.name}"

    fun get(key: String): RemoteLspHandle? = synchronized(lock) { handles[key] }

    fun put(handle: RemoteLspHandle) {
        val previous = synchronized(lock) { handles.put(handle.key, handle) }
        previous?.close()
    }

    fun remove(key: String): RemoteLspHandle? = synchronized(lock) { handles.remove(key) }

    fun snapshot(): List<RemoteLspHandle> = synchronized(lock) { handles.values.toList() }

    /** Close servers whose last use is older than [idleMs]. Safe to call from the tool path. */
    fun closeStale(idleMs: Long = IDLE_TIMEOUT_MS, nowMs: Long = System.currentTimeMillis()) {
        val stale = synchronized(lock) {
            val expired = handles.values.filter { nowMs - it.lastUsedAtMs > idleMs }
            expired.forEach { handles.remove(it.key) }
            expired
        }
        stale.forEach { it.close() }
    }

    fun closeAll() {
        val all = synchronized(lock) {
            val copy = handles.values.toList()
            handles.clear()
            copy
        }
        all.forEach { it.close() }
    }
}
