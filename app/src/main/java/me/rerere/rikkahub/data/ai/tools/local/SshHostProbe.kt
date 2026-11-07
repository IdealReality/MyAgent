package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.db.entity.SshHostEntity

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
}
