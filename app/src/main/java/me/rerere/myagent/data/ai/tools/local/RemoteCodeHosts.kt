package me.rerere.myagent.data.ai.tools.local

import android.content.Context
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.myagent.data.db.entity.SshHostEntity

/**
 * Opens the long-lived SSH session the code-intelligence tools run on.
 *
 * Deliberately reuses the exact connection path `ssh_exec` uses — transport racing inside the
 * JSch socket factory, trust-on-first-use host keys, the shared DNS cache — so a host that works
 * with the shell tool also works with the language server, instead of the two disagreeing about
 * whether a host is reachable.
 */
internal suspend fun openRemoteSession(
    context: Context,
    host: SshHostEntity,
    timeoutMs: Int = 30_000,
): Session = withContext(Dispatchers.IO) {
    val auth = SshAuth(
        password = host.password,
        privateKey = host.privateKey,
        passphrase = host.passphrase,
    )
    check(auth.isUsable()) { "saved host '${host.name}' has no usable credentials" }
    val jsch = newJSch(context)
    try {
        openSshSession(
            jsch = jsch,
            host = host.host,
            port = host.port,
            user = host.user,
            auth = auth,
            timeoutMs = timeoutMs,
            socketFactory = networkRaceSocketFactory(context),
        )
    } catch (t: Throwable) {
        // Reuse the same structured envelope ssh_exec returns: host_key_changed / auth_failed /
        // connect_failed, each with the recovery hint the model needs.
        val envelope = wrapConnectError(host.host, t)
        throw IllegalStateException(envelope.toString(), t)
    }
}
