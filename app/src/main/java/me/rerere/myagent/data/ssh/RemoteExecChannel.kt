package me.rerere.myagent.data.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * One long-lived remote process attached to an SSH `exec` channel, with its stdio exposed as
 * plain streams. This is how a language server (`gopls`, `clangd`, …) or a debug adapter
 * (`dlv dap`, `python -m debugpy.adapter`, …) runs on the far side of the connection: the
 * protocol travels over stdin/stdout exactly as it would over a local pipe.
 *
 * Two details matter and both have bitten hand-rolled implementations:
 *
 *  - **No PTY.** A pseudo-terminal gives the child line discipline: `\n` gets rewritten to
 *    `\r\n`, the peer sees local echo unless it disables it, and `^D` semantics apply to stdin.
 *    A framed JSON protocol cannot survive that. `setPty(false)` is the default for
 *    `ChannelExec`, and it is set explicitly so nobody "fixes" it later.
 *  - **stderr must be drained.** JSch buffers the extended-data stream inside a bounded pipe;
 *    a server that logs to stderr (clangd and rust-analyzer both do) fills it and then blocks
 *    inside `write()`, which looks exactly like a hung request. A daemon thread reads it into a
 *    small ring so the process never back-pressures, and the tail is available for error text.
 */
internal class RemoteExecChannel private constructor(
    val session: Session,
    private val channel: ChannelExec,
    val stdin: OutputStream,
    val stdout: InputStream,
    private val stderrTail: TailBuffer,
    val sshCommand: String,
) : Closeable {

    val isAlive: Boolean get() = channel.isConnected && !channel.isClosed

    val exitStatus: Int? get() = if (channel.isClosed) runCatching { channel.exitStatus }.getOrNull() else null

    /** Last few KiB the remote process wrote to stderr — the only useful text when it dies. */
    fun stderrSnapshot(maxChars: Int = 2_000): String = stderrTail.text().takeLast(maxChars)

    override fun close() {
        runCatching { channel.disconnect() }
    }

    companion object {
        /** How much stderr we keep for error reporting. */
        private const val STDERR_RETAIN_BYTES = 16 * 1024

        /**
         * The shell script handed to `sh -c` on the remote side: change into the project root
         * (when there is one) and `exec` the server so signals reach it directly instead of a
         * wrapper shell's pid.
         */
        fun scriptFor(rootPath: String?, commandLine: String): String = buildString {
            if (!rootPath.isNullOrBlank()) {
                append("cd ").append(posixShellQuote(rootPath)).append(" && ")
            }
            append("exec ").append(commandLine)
        }

        /**
         * The string sshd hands to the login shell.
         *
         * sshd runs exec requests as `$SHELL -c '<command>'`, so the first parse is whatever the
         * user's login shell is — fish on several of the hosts this app talks to. Quoting a
         * script for POSIX `sh` and praying fish agrees is a losing game: fish's escaping inside
         * single quotes is not identical, and a single mis-parsed quote silently turns the script
         * into a different command (observed: `printf '%s\t%s'` degenerating into literal
         * `%s%s` and clangd being executed with its own path as an argument).
         *
         * Base64 removes the question. The encoded payload is `[A-Za-z0-9+/=]` only, so the
         * single-quoted argument means the same thing in every shell, and the script is decoded
         * and run by a real `sh` on the far side.
         */
        fun sshCommandFor(script: String): String {
            val encoded = java.util.Base64.getEncoder()
                .encodeToString(script.toByteArray(StandardCharsets.UTF_8))
            // Three constraints, all load-bearing:
            //  1. the login shell (fish) must parse the outer string — it contains only a
            //     single-quoted base64 blob and `sh -c '…'`, so nothing is shell-specific;
            //  2. the script must NOT arrive on stdin — the language server / adapter inherits
            //     that stream and needs it for the protocol, so it is decoded into a temp file;
            //  3. the temp file must be removed after the run and its exit status preserved.
            val launcher = "f=\$(mktemp) && printf %s " + posixShellQuote(encoded) +
                " | base64 -d > \"\$f\" && sh \"\$f\"; rc=\$?; rm -f \"\$f\"; exit \$rc"
            return "sh -c " + posixShellQuote(launcher)
        }

        fun start(
            session: Session,
            rootPath: String?,
            commandLine: String,
            connectTimeoutMs: Int = 15_000,
        ): RemoteExecChannel {
            val script = scriptFor(rootPath, commandLine)
            val sshCommand = sshCommandFor(script)

            val channel = session.openChannel("exec") as ChannelExec
            channel.setPty(false)
            channel.setCommand(sshCommand)
            channel.connect(connectTimeoutMs)

            val stderrTail = TailBuffer(STDERR_RETAIN_BYTES)
            val extStream = channel.extInputStream
            Thread({
                val chunk = ByteArray(4 * 1024)
                try {
                    while (true) {
                        val n = extStream.read(chunk)
                        if (n < 0) break
                        stderrTail.append(chunk, n)
                    }
                } catch (_: Throwable) {
                    // Channel closed: nothing left to drain.
                }
            }, "myagent-exec-stderr").apply {
                isDaemon = true
                start()
            }

            return RemoteExecChannel(
                session = session,
                channel = channel,
                stdin = channel.outputStream,
                stdout = channel.inputStream,
                stderrTail = stderrTail,
                sshCommand = sshCommand,
            )
        }
    }
}

/**
 * Run one command on an existing connection and collect its output — used for cheap probes
 * (`command -v clangd`), project-root discovery and small remote reads. Distinct from
 * `ssh_exec`: no new TCP connection, no transport race, no credentials lookup.
 */
internal object RemoteExec {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    fun run(
        session: Session,
        command: String,
        timeoutMs: Int = 20_000,
        cwd: String? = null,
        maxStdoutBytes: Int = 256 * 1024,
    ): Result {
        val script = RemoteExecChannel.scriptFor(
            rootPath = cwd,
            commandLine = "sh -c " + posixShellQuote(command),
        )
        val channel = session.openChannel("exec") as ChannelExec
        channel.setPty(false)
        channel.setCommand(RemoteExecChannel.sshCommandFor(script))
        channel.setInputStream(ByteArrayInputStreamEmpty, false)
        channel.connect(minOf(timeoutMs, 15_000))

        val out = BoundedByteSink(maxStdoutBytes)
        val err = BoundedByteSink(64 * 1024)
        val stderrThread = Thread({
            val chunk = ByteArray(4 * 1024)
            try {
                val stream = channel.extInputStream
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    err.append(chunk, n)
                }
            } catch (_: Throwable) {
            }
        }, "myagent-exec-probe-stderr").apply {
            isDaemon = true
            start()
        }

        // Blocking read, not `available()` polling: JSch delivers channel data from its own
        // pump thread, so right after the remote process exits `available()` can be 0 with bytes
        // still in flight — a poll loop would return an empty stdout for a command that printed
        // plenty. A watchdog closes the channel at the deadline, which is what unblocks `read`.
        val watchdog = Thread({
            try {
                Thread.sleep(timeoutMs.toLong())
            } catch (_: InterruptedException) {
                return@Thread
            }
            // Unconditional: `channel.isClosed` only reflects the SSH channel, while the pipe a
            // caller is blocked on is released by closing the stream. Skipping this when the
            // channel already reported closed is exactly how a "finished" command hangs forever.
            runCatching { channel.disconnect() }
            runCatching { channel.inputStream.close() }
        }, "myagent-exec-probe-timeout").apply {
            isDaemon = true
            start()
        }

        try {
            val chunk = ByteArray(8 * 1024)
            val stdout = channel.inputStream
            while (true) {
                val n = try {
                    stdout.read(chunk)
                } catch (_: Throwable) {
                    break
                }
                if (n < 0) break
                if (n > 0) out.append(chunk, n)
            }
            // Give the channel a moment to publish the exit status after EOF.
            val statusDeadline = System.currentTimeMillis() + 1_000
            while (!channel.isClosed && System.currentTimeMillis() < statusDeadline) Thread.sleep(10)
            val exit = if (channel.isClosed) runCatching { channel.exitStatus }.getOrDefault(-1) else -1
            return Result(exit, out.text(), err.text())
        } finally {
            watchdog.interrupt()
            runCatching { channel.inputStream.close() }
            runCatching { channel.disconnect() }
            stderrThread.interrupt()
        }
    }

    private val ByteArrayInputStreamEmpty = java.io.ByteArrayInputStream(ByteArray(0))
}

/** Growable byte sink that stops storing past [cap] but still counts what it dropped. */
internal class BoundedByteSink(private val cap: Int) {
    private val buffer = ByteArrayOutputStream()
    private var dropped = 0

    @Synchronized
    fun append(bytes: ByteArray, count: Int) {
        val room = cap - buffer.size()
        if (room <= 0) {
            dropped += count
            return
        }
        val keep = minOf(room, count)
        buffer.write(bytes, 0, keep)
        if (keep < count) dropped += count - keep
    }

    @Synchronized
    fun text(): String {
        val body = buffer.toString(StandardCharsets.UTF_8.name())
        return if (dropped > 0) "$body\n[truncated; $dropped bytes more]" else body
    }
}

/** Retains the last [cap] bytes written — stderr is only useful when it is recent. */
internal class TailBuffer(private val cap: Int) {
    private val buffer = ByteArrayOutputStream()

    @Synchronized
    fun append(bytes: ByteArray, count: Int) {
        if (count <= 0) return
        // Keep at most cap bytes; drop from the front when the buffer overflows.
        if (buffer.size() + count <= cap) {
            buffer.write(bytes, 0, count)
            return
        }
        val existing = buffer.toByteArray()
        val combined = ByteArray(existing.size + count)
        System.arraycopy(existing, 0, combined, 0, existing.size)
        System.arraycopy(bytes, 0, combined, existing.size, count)
        val keep = minOf(cap, combined.size)
        buffer.reset()
        buffer.write(combined, combined.size - keep, keep)
    }

    @Synchronized
    fun text(): String = buffer.toString(StandardCharsets.UTF_8.name())
}
