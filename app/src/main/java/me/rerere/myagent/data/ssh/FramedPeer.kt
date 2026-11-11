package me.rerere.myagent.data.ssh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * A framed JSON peer on top of a pair of blocking streams — the transport half of both the LSP
 * and DAP clients.
 *
 * Responsibilities are deliberately narrow: read frames, parse them to [JsonObject], hand each
 * one to [onMessage]; serialize outgoing objects into frames under a write lock. Id allocation,
 * request/response correlation, cancellation and protocol state live in the protocol clients,
 * because LSP (numeric `id`) and DAP (`seq` / `request_seq`) disagree on all of it.
 *
 * The reader owns a dedicated daemon thread: `InputStream.read` on a JSch channel is a blocking
 * socket read that no coroutine cancellation can interrupt, so running it on Dispatchers.IO
 * would leak a thread per reconnect until the socket died on its own.
 *
 * [onMessage] runs ON the reader thread and must not block on a request to the same peer — that
 * would deadlock (the reply it waits for can only be read by the thread it is blocking). Peers
 * that need to answer a server-initiated request therefore hand that work to their own
 * single-thread executor.
 */
internal class FramedPeer(
    private val input: InputStream,
    private val output: OutputStream,
    private val label: String = "rpc",
) : Closeable {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    private val writeLock = Any()

    @Volatile
    var onMessage: ((JsonObject) -> Unit)? = null

    /** Invoked once when the reader stops, with the failure cause (null on a clean EOF). */
    @Volatile
    var onClosed: ((Throwable?) -> Unit)? = null

    @Volatile
    private var closed = false

    @Volatile
    private var reader: Thread? = null

    fun start() {
        if (reader != null) return
        reader = Thread({ readLoop() }, "myagent-$label-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop() {
        val decoder = StreamFramer.Decoder()
        val chunk = ByteArray(16 * 1024)
        var cause: Throwable? = null
        try {
            while (!closed) {
                val read = input.read(chunk)
                if (read < 0) break
                if (read == 0) continue
                for (frame in decoder.feed(chunk, 0, read)) {
                    val text = String(frame, StandardCharsets.UTF_8)
                    val obj = try {
                        json.parseToJsonElement(text).jsonObject
                    } catch (t: Throwable) {
                        throw FramingException("$label: peer sent a non-object JSON frame")
                    }
                    onMessage?.invoke(obj)
                }
            }
        } catch (t: Throwable) {
            cause = t
        } finally {
            closed = true
            onClosed?.invoke(cause)
        }
    }

    fun send(message: JsonObject) {
        val bytes = StreamFramer.encode(
            json.encodeToString(JsonObject.serializer(), message).toByteArray(StandardCharsets.UTF_8),
        )
        synchronized(writeLock) {
            if (closed) throw FramingException("$label: transport already closed")
            try {
                output.write(bytes)
                output.flush()
            } catch (t: Throwable) {
                closed = true
                throw FramingException("$label: write failed (${t.javaClass.simpleName}: ${t.message})")
            }
        }
    }

    val isClosed: Boolean get() = closed

    override fun close() {
        closed = true
        // Closing the channel's streams is what unblocks the reader thread's socket read.
        runCatching { input.close() }
        runCatching { output.close() }
    }
}
