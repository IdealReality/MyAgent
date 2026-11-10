package me.rerere.myagent.data.ai.tools.local

import android.content.Context
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.myagent.data.db.entity.SshHostEntity

/** Outcome of a real JSch handshake, never a bare TCP probe. */
data class SshProbeResult(
    val ok: Boolean,
    val errorCode: String? = null,
    val detail: String? = null,
)

/**
 * The single implementation behind every "test this host" surface.
 *
 * Both the Android settings page and the web socket (`/api/ssh/hosts/ws`, op `test`) call
 * this, so "test" cannot disagree with itself depending on which client asked. It uses
 * [openSshSession] rather than [execOneShot] — a test opens no channel and runs no command —
 * but it deliberately walks the same candidate transports, because otherwise a test can
 * report a failure for a host the tools reach without trouble.
 */
internal object SshHostProbe {
    /**
     * Deliberately generous — the same ceiling `ssh_exec` uses by default. A test that gives
     * up sooner than the tool it stands in for would report failures the tool never had: the
     * first byte through an active VPN can take several seconds longer than over a LAN.
     */
    const val TIMEOUT_MS = 30_000

    suspend fun probe(context: Context, host: SshHostEntity): SshProbeResult =
        withContext(Dispatchers.IO) {
            val auth = SshAuth(
                password = host.password,
                privateKey = host.privateKey,
                passphrase = host.passphrase,
            )
            if (!auth.isUsable()) {
                return@withContext SshProbeResult(false, "no_credentials", null)
            }
            // Admission before any network work: see [gate].
            if (!gate.enter(QUEUE_WAIT_MS)) {
                return@withContext SshProbeResult(
                    false,
                    "busy",
                    "Too many connection tests are already running",
                )
            }
            try {
                probeAdmitted(context, host, auth)
            } finally {
                gate.exit()
            }
        }

    private suspend fun probeAdmitted(
        context: Context,
        host: SshHostEntity,
        auth: SshAuth,
    ): SshProbeResult = withContext(Dispatchers.IO) {
        val jsch = newJSch(context)
        // Same candidate transports the exec path walks, so "test" agrees with
        // `ssh_exec` / `ssh_exec_saved` on the very same host: a factory pinned to the
        // default route alone cannot fall back to a physical network when the default
        // route (an active VPN, adaptive routing, a captive portal) is the broken one, and
        // the surface then reported connect_failed for a host the tools reached fine.
        val factory = networkRaceSocketFactory(context)
        var session: Session? = null
        try {
            // [openSshSession] performs the connect. Do NOT add a second `Session.connect`
            // after it: JSch throws "session is already connected" (a JSchException that
            // reads like a network failure) and the UI rendered that as a bogus
            // "connect_failed" even though the handshake had just succeeded.
            session = openSshSession(
                jsch, host.host, host.port, host.user, auth, TIMEOUT_MS, factory,
            )
            SshProbeResult(ok = true)
        } catch (e: Throwable) {
            if (factory.winningLabel == null && factory.failures.isNotEmpty()) {
                // No transport reached the host at all: report what we tried, the same way
                // the exec path does, instead of a bare "connect_failed".
                SshProbeResult(
                    ok = false,
                    errorCode = "tcp_unreachable",
                    detail = factory.failures.joinToString("; ") { (label, reason) ->
                        "$label: $reason"
                    },
                )
            } else {
                val env = wrapConnectError(host.host, e)
                SshProbeResult(
                    ok = false,
                    errorCode = env["error"]?.jsonPrimitive?.contentOrNull,
                    detail = env["raw"]?.jsonPrimitive?.contentOrNull
                        ?: env["reason"]?.jsonPrimitive?.contentOrNull,
                )
            }
        } finally {
            runCatching { session?.disconnect() }
        }
    }

    /**
     * Process-wide ceiling on concurrent handshakes.
     *
     * The socket already caps tests per connection, but that limit multiplies by the number of
     * open sockets — several browser tabs (or a reconnecting client whose old socket has not been
     * reaped yet) could each start four dials, and every dial holds a JSch session and a racing
     * socket factory for up to 30s. One global gate is what actually bounds that cost.
     */
    private val gate = ProbeGate(MAX_CONCURRENT_PROBES)

    /** How long a test waits for a free slot before reporting `busy`. */
    private const val QUEUE_WAIT_MS = 10_000L
    private const val MAX_CONCURRENT_PROBES = 8
}

/**
 * A counting gate for expensive handshakes: [enter] waits for a slot, [exit] returns it.
 * Split out so the admission logic is testable without Android or a network.
 */
internal class ProbeGate(private val capacity: Int) {
    private val permits = kotlinx.coroutines.sync.Semaphore(capacity)

    /** True when a slot was obtained within [timeoutMs]; the caller MUST [exit] on true. */
    suspend fun enter(timeoutMs: Long): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            permits.acquire()
            true
        } ?: false

    fun exit() = permits.release()
}
