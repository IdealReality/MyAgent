package me.rerere.myagent.data.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [StreamFramer] — the byte layer both LSP and DAP sit on.
 *
 * The failure modes pinned here are the ones that produce *silent* corruption rather than a
 * crash: a frame split across reads, two frames in one read, a body containing the header
 * terminator, and a multibyte body whose size in bytes differs from its size in characters.
 */
class StreamFramerTest {

    private fun decodeAll(bytes: ByteArray, chunkSize: Int = bytes.size): List<ByteArray> {
        val decoder = StreamFramer.Decoder()
        val out = ArrayList<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            out.addAll(decoder.feed(bytes, offset, end - offset))
            offset = end
        }
        return out
    }

    @Test
    fun `encode prefixes an ASCII content length header`() {
        val payload = "{\"a\":1}".toByteArray()
        val framed = StreamFramer.encode(payload)
        val header = String(framed, 0, framed.size - payload.size)
        assertEquals("Content-Length: ${payload.size}\r\n\r\n", header)
        assertArrayEquals(payload, framed.copyOfRange(header.length, framed.size))
    }

    @Test
    fun `one chunk carrying three messages decodes to three frames`() {
        val a = "{\"id\":1}".toByteArray()
        val b = "{\"id\":2,\"long\":true}".toByteArray()
        val c = ByteArray(0)
        val joined = StreamFramer.encode(a) + StreamFramer.encode(b) + StreamFramer.encode(c)

        val frames = decodeAll(joined)

        assertEquals(3, frames.size)
        assertArrayEquals(a, frames[0])
        assertArrayEquals(b, frames[1])
        assertEquals(0, frames[2].size)
    }

    @Test
    fun `a frame split byte by byte still decodes exactly once`() {
        val payload = "{\"method\":\"textDocument/hover\"}".toByteArray()
        val frames = decodeAll(StreamFramer.encode(payload), chunkSize = 1)
        assertEquals(1, frames.size)
        assertArrayEquals(payload, frames[0])
    }

    @Test
    fun `a body containing the header terminator is not split`() {
        val payload = "line1\r\n\r\nline2\t\"x\":\"\r\n\r\n\"".toByteArray()
        val frames = decodeAll(StreamFramer.encode(payload), chunkSize = 3)
        assertEquals(1, frames.size)
        assertArrayEquals(payload, frames[0])
    }

    @Test
    fun `multibyte bodies use byte length not character count`() {
        val payload = "{\"text\":\"中文内容🙂\"}".toByteArray(Charsets.UTF_8)
        assertTrue("payload must be longer in bytes than in chars", payload.size > "{\"text\":\"中文内容🙂\"}".length)
        val frames = decodeAll(StreamFramer.encode(payload))
        assertEquals(1, frames.size)
        assertEquals("{\"text\":\"中文内容🙂\"}", String(frames[0], Charsets.UTF_8))
    }

    @Test
    fun `extra headers and lowercase names are accepted`() {
        val body = "{\"ok\":true}".toByteArray()
        val framed = ("Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n" +
            "content-length: ${body.size}\r\n\r\n").toByteArray() + body
        val frames = decodeAll(framed)
        assertArrayEquals(body, frames.single())
    }

    @Test
    fun `bare newline terminators are tolerated`() {
        val body = "{\"ok\":1}".toByteArray()
        val framed = ("Content-Length: ${body.size}\n\n").toByteArray() + body
        val frames = decodeAll(framed)
        assertArrayEquals(body, frames.single())
    }

    @Test(expected = FramingException::class)
    fun `missing content length fails loudly`() {
        val bytes = "Content-Type: json\r\n\r\n{}".toByteArray()
        StreamFramer.Decoder().feed(bytes)
    }

    @Test(expected = FramingException::class)
    fun `unterminated header beyond the cap fails instead of growing forever`() {
        val decoder = StreamFramer.Decoder()
        val junk = ByteArray(StreamFramer.MAX_HEADER_BYTES + 16) { 'x'.code.toByte() }
        decoder.feed(junk)
    }

    @Test
    fun `partial frame is reported and completed by the next feed`() {
        val payload = "{\"id\":7,\"result\":null}".toByteArray()
        val framed = StreamFramer.encode(payload)
        val decoder = StreamFramer.Decoder()
        val firstHalf = framed.size / 2
        assertTrue(decoder.feed(framed, 0, firstHalf).isEmpty())
        assertTrue(decoder.hasPartialFrame)
        val rest = decoder.feed(framed, firstHalf, framed.size - firstHalf)
        assertArrayEquals(payload, rest.single())
        assertTrue(!decoder.hasPartialFrame)
    }
}
