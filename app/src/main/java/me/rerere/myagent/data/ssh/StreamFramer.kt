package me.rerere.myagent.data.ssh

import java.nio.charset.StandardCharsets

internal class FramingException(message: String) : Exception(message)

/**
 * `Content-Length` message framing shared by every stream protocol we speak over an SSH exec
 * channel: LSP (vscode-jsonrpc) and DAP (debug adapter protocol) both put a small ASCII header
 * in front of a UTF-8 JSON body.
 *
 * The decoder is incremental because a single `read()` on a socket returns an arbitrary slice
 * of the byte stream: one chunk can carry three messages, half a header, or half a body. A
 * `readLine()`-based reader is not usable here — LSP bodies are not newline-terminated JSON and
 * a body can legally contain `\r\n\r\n`, so the only safe split point is the header terminator
 * followed by an exact byte count.
 *
 * Byte counts are BYTES, not characters: a CJK body is three bytes per glyph, and a char-count
 * budget would either truncate a message or wait forever for bytes that never arrive.
 */
internal object StreamFramer {

    /**
     * Refuse absurd headers instead of growing the buffer forever. A peer that never sends
     * `\r\n\r\n` is broken (or the channel is carrying a shell banner we should not be
     * parsing), and failing loudly beats an OOM on the phone.
     */
    const val MAX_HEADER_BYTES: Int = 16 * 1024

    /** Same idea for the body: `Content-Length: 999999999` must not allocate 1 GB. */
    const val MAX_BODY_BYTES: Int = 128 * 1024 * 1024

    fun encode(payload: ByteArray): ByteArray {
        val header = "Content-Length: ${payload.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        val out = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, out, 0, header.size)
        System.arraycopy(payload, 0, out, header.size, payload.size)
        return out
    }

    /**
     * Feed bytes in, get complete frames out. Not thread-safe; one decoder per direction.
     */
    class Decoder {
        private var buffer = ByteArray(8 * 1024)
        private var length = 0

        /** Offset of the first body byte of the frame currently being assembled. */
        private var bodyStart = -1
        private var bodyLength = -1
        private var terminatorLength = 0

        fun feed(chunk: ByteArray, offset: Int = 0, count: Int = chunk.size): List<ByteArray> {
            if (count <= 0) return emptyList()
            ensureCapacity(length + count)
            System.arraycopy(chunk, offset, buffer, length, count)
            length += count

            var frames: ArrayList<ByteArray>? = null
            while (true) {
                if (bodyLength < 0) {
                    val headerEnd = findHeaderEnd()
                    if (headerEnd < 0) {
                        if (length > MAX_HEADER_BYTES) {
                            throw FramingException("header exceeded $MAX_HEADER_BYTES bytes without a terminator")
                        }
                        break
                    }
                    bodyLength = parseContentLength(headerEnd)
                    bodyStart = headerEnd + terminatorLength
                }
                if (length - bodyStart < bodyLength) break
                val frame = buffer.copyOfRange(bodyStart, bodyStart + bodyLength)
                if (frames == null) frames = ArrayList(1)
                frames.add(frame)

                val consumed = bodyStart + bodyLength
                val remaining = length - consumed
                if (remaining > 0) System.arraycopy(buffer, consumed, buffer, 0, remaining)
                length = remaining
                bodyStart = -1
                bodyLength = -1
                terminatorLength = 0
            }
            return frames ?: emptyList()
        }

        /** True when a partial message is buffered (used to explain a mid-frame disconnect). */
        val hasPartialFrame: Boolean get() = length > 0

        private fun ensureCapacity(required: Int) {
            if (required <= buffer.size) return
            if (required > MAX_HEADER_BYTES + MAX_BODY_BYTES) {
                throw FramingException("message exceeded the ${MAX_BODY_BYTES}-byte body cap")
            }
            var next = buffer.size
            while (next < required) next *= 2
            buffer = buffer.copyOf(next)
        }

        /**
         * Locate the header terminator. `\r\n\r\n` is the spec; a bare `\n\n` is tolerated
         * because a hand-written mock adapter is a realistic debugging aid and silently
         * hanging on it would cost an hour.
         */
        private fun findHeaderEnd(): Int {
            var i = 0
            while (i < length) {
                val b = buffer[i].toInt()
                if (b == 13 && i + 3 < length &&
                    buffer[i + 1].toInt() == 10 && buffer[i + 2].toInt() == 13 && buffer[i + 3].toInt() == 10
                ) {
                    terminatorLength = 4
                    return i
                }
                if (b == 10 && i + 1 < length && buffer[i + 1].toInt() == 10) {
                    terminatorLength = 2
                    return i
                }
                i++
            }
            return -1
        }

        private fun parseContentLength(headerEnd: Int): Int {
            var lineStart = 0
            var result = -1
            while (lineStart <= headerEnd) {
                var lineEnd = lineStart
                while (lineEnd < headerEnd && buffer[lineEnd].toInt() != 10) lineEnd++
                // Trim a trailing \r so both \r\n and \n headers parse.
                var end = lineEnd
                if (end > lineStart && buffer[end - 1].toInt() == 13) end--
                val line = String(buffer, lineStart, end - lineStart, StandardCharsets.US_ASCII)
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val name = line.substring(0, colon).trim()
                    if (name.equals("Content-Length", ignoreCase = true)) {
                        result = line.substring(colon + 1).trim().toIntOrNull()
                            ?: throw FramingException("malformed Content-Length: '$line'")
                    }
                }
                lineStart = lineEnd + 1
            }
            if (result < 0) throw FramingException("missing Content-Length header")
            if (result > MAX_BODY_BYTES) throw FramingException("Content-Length $result exceeds the body cap")
            return result
        }
    }
}
