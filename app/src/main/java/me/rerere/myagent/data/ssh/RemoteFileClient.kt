package me.rerere.myagent.data.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Remote file access over SFTP on an **existing** SSH connection.
 *
 * The LSP client needs the file contents it is about to open, and rename/code-action edits must
 * land back on the remote disk. Opening a second SSH connection for every file would multiply
 * handshakes (and, on flaky mobile links, failures) for no benefit: one SSH session multiplexes
 * any number of channels, so this reuses the session the language server already runs on.
 *
 * Writes are crash-safe by construction: content goes to a sibling temp file first, the old file
 * is moved aside rather than deleted, and the temp file is renamed into place. At no instant is
 * the destination missing or half-written — an SFTP `put` straight onto the target truncates it
 * first, and a dropped connection mid-transfer would leave the user with an empty source file.
 */
internal class RemoteFileClient(private val session: Session) : Closeable {

    private var channel: ChannelSftp? = null

    @Synchronized
    private fun sftp(): ChannelSftp {
        val current = channel
        if (current != null && current.isConnected) return current
        val fresh = session.openChannel("sftp") as ChannelSftp
        fresh.connect(15_000)
        channel = fresh
        return fresh
    }

    @Synchronized
    fun exists(path: String): Boolean = try {
        sftp().stat(path)
        true
    } catch (e: com.jcraft.jsch.SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e
    }

    @Synchronized
    fun isDirectory(path: String): Boolean = try {
        sftp().stat(path).isDir
    } catch (e: com.jcraft.jsch.SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e
    }

    @Synchronized
    fun size(path: String): Long = sftp().stat(path).size

    /**
     * Read a regular file as text. [maxBytes] guards the phone's heap: a 500 MB log somebody
     * pointed at is not a document the language server can help with anyway.
     */
    fun readText(path: String, maxBytes: Int = DEFAULT_MAX_BYTES): String =
        String(readBytes(path, maxBytes), StandardCharsets.UTF_8)

    @Synchronized
    fun readBytes(path: String, maxBytes: Int = DEFAULT_MAX_BYTES): ByteArray {
        val sftp = sftp()
        val attrs = sftp.stat(path)
        if (attrs.isDir) throw IOException("'$path' is a directory")
        if (attrs.size > maxBytes) {
            throw IOException("'$path' is ${attrs.size} bytes, over the $maxBytes-byte cap for editor documents")
        }
        val input = sftp.get(path)
        input.use {
            val out = ByteArrayOutputStream(minOf(attrs.size, 64L * 1024).toInt().coerceAtLeast(64))
            val chunk = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val n = it.read(chunk)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IOException("'$path' grew past the $maxBytes-byte cap while reading")
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        }
    }

    fun writeText(path: String, text: String) = writeBytes(path, text.toByteArray(StandardCharsets.UTF_8))

    @Synchronized
    fun writeBytes(path: String, bytes: ByteArray) {
        val sftp = sftp()
        val permissions = runCatching { sftp.stat(path).permissions }.getOrNull()
        val stamp = System.nanoTime().toString(36)
        val temp = "$path.myagent-tmp-$stamp"
        val backup = "$path.myagent-bak-$stamp"
        try {
            sftp.put(ByteArrayInputStream(bytes), temp)
            // Keep the original mode: a Kotlin source that flips from 0644 to 0666 on every
            // agent edit is the kind of drift git status starts reporting.
            if (permissions != null) runCatching { sftp.chmod(permissions, temp) }
        } catch (t: Throwable) {
            runCatching { sftp.rm(temp) }
            throw IOException("failed to stage '$path': ${t.message}", t)
        }

        var movedAside = false
        try {
            if (exists(path)) {
                sftp.rename(path, backup)
                movedAside = true
            }
            sftp.rename(temp, path)
        } catch (t: Throwable) {
            if (movedAside) runCatching { sftp.rename(backup, path) }
            runCatching { sftp.rm(temp) }
            throw IOException("failed to replace '$path': ${t.message}", t)
        } finally {
            if (movedAside) runCatching { sftp.rm(backup) }
        }
    }

    @Synchronized
    fun rename(from: String, to: String) {
        val sftp = sftp()
        // SFTP v3 rename refuses to overwrite; move the destination aside first when asked to
        // replace, mirroring a POSIX `mv -f`.
        if (exists(to)) {
            val backup = "$to.myagent-bak-${System.nanoTime().toString(36)}"
            sftp.rename(to, backup)
            try {
                sftp.rename(from, to)
            } catch (t: Throwable) {
                runCatching { sftp.rename(backup, to) }
                throw t
            } finally {
                runCatching { sftp.rm(backup) }
            }
        } else {
            sftp.rename(from, to)
        }
    }

    @Synchronized
    fun delete(path: String) {
        sftp().rm(path)
    }

    @Synchronized
    override fun close() {
        runCatching { channel?.disconnect() }
        channel = null
    }

    companion object {
        /**
         * Same ceiling as omp's editor sync path territory: large enough for ordinary source
         * files, small enough that one `remote_lsp` call cannot exhaust a phone.
         */
        const val DEFAULT_MAX_BYTES: Int = 4 * 1024 * 1024
    }
}
