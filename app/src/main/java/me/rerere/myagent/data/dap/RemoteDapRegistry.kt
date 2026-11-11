package me.rerere.myagent.data.dap

import com.jcraft.jsch.Session
import me.rerere.myagent.data.ssh.RemoteFileClient
import java.io.Closeable

/** One live debug adapter plus the SSH session it runs on. */
internal class RemoteDapHandle(
    val key: String,
    val hostName: String,
    val adapter: DapAdapterSpec,
    val session: Session,
    val files: RemoteFileClient,
    val client: RemoteDapClient,
) : Closeable {

    @Volatile
    var lastUsedAtMs: Long = System.currentTimeMillis()

    val alive: Boolean get() = client.isAlive

    /** The absolute path of what was launched/attached to, for `sessions` output. */
    @Volatile
    var target: String? = null

    override fun close() {
        runCatching { client.close() }
        runCatching { files.close() }
        runCatching { session.disconnect() }
    }
}

/**
 * Debug sessions are far fewer than language servers (one root session is the norm), but they
 * are cached for the same reason: a broken breakpoint call must not start a *new* debuggee.
 */
internal object RemoteDapRegistry {

    const val IDLE_TIMEOUT_MS: Long = 30 * 60 * 1000L

    private val lock = Any()
    private val handles = LinkedHashMap<String, RemoteDapHandle>()

    @Volatile
    var activeKey: String? = null

    fun get(key: String): RemoteDapHandle? = synchronized(lock) { handles[key] }

    fun active(): RemoteDapHandle? = synchronized(lock) { activeKey?.let { handles[it] } }

    fun put(handle: RemoteDapHandle) {
        val previous = synchronized(lock) {
            val old = handles.put(handle.key, handle)
            activeKey = handle.key
            old
        }
        if (previous != null && previous.key != handle.key) previous.close()
    }

    fun activate(key: String) {
        synchronized(lock) { if (handles.containsKey(key)) activeKey = key }
    }

    fun remove(key: String): RemoteDapHandle? = synchronized(lock) {
        val removed = handles.remove(key)
        if (activeKey == key) activeKey = null
        removed
    }

    fun snapshot(): List<RemoteDapHandle> = synchronized(lock) { handles.values.toList() }

    fun closeStale(idleMs: Long = IDLE_TIMEOUT_MS, nowMs: Long = System.currentTimeMillis()) {
        val stale = synchronized(lock) {
            val expired = handles.values.filter { nowMs - it.lastUsedAtMs > idleMs }
            expired.forEach {
                handles.remove(it.key)
                if (activeKey == it.key) activeKey = null
            }
            expired
        }
        stale.forEach { it.close() }
    }

    fun closeAll() {
        val all = synchronized(lock) {
            val copy = handles.values.toList()
            handles.clear()
            activeKey = null
            copy
        }
        all.forEach { it.close() }
    }
}
