package me.rerere.vm

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class VmInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val servers = mutableListOf<HttpServer>()

    @After
    fun tearDown() {
        servers.forEach { it.stop(0) }
    }

    private fun image(vararg files: VmImageFile) = VmImage(
        id = "img",
        name = "img",
        files = files.toList(),
        boot = VmBootSpec(dataDiskMb = 0),
    )

    @Test
    fun `local file source is copied verbatim`() {
        val source = File(tmp.root, "android.iso").apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        val dir = tmp.newFolder("instance")
        val files = VmInstaller().install(
            image(VmImageFile(url = source.absolutePath, fileName = "android.iso", role = VmFileRole.CDROM)),
            dir,
        )
        val copied = files.getValue(VmFileRole.CDROM).single()
        assertEquals(source.length(), copied.length())
        assertArrayEquals(source.readBytes(), copied.readBytes())
        assertTrue(!File(dir, "android.iso.part").exists())
    }

    @Test
    fun `resumes from existing part with range request`() {
        val payload = ByteArray(250_000) { (it % 97).toByte() }
        val rangeRequests = AtomicInteger(0)
        val server = startServer { exchange ->
            val path = exchange.requestURI.path
            if (path == "/img.iso") {
                val range = exchange.requestHeaders.getFirst("Range")
                if (range != null && range.startsWith("bytes=")) {
                    rangeRequests.incrementAndGet()
                    val from = range.removePrefix("bytes=").substringBefore('-').toInt()
                    exchange.responseHeaders.add("Content-Range", "bytes $from-${payload.size - 1}/${payload.size}")
                    exchange.sendResponseHeaders(206, (payload.size - from).toLong())
                    exchange.responseBody.use { it.write(payload, from, payload.size - from) }
                } else {
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                }
            } else {
                exchange.sendResponseHeaders(404, -1)
            }
        }
        val dir = tmp.newFolder("resume")
        // 预置 100000 字节的 part，模拟上次中断
        File(dir, "img.iso.part").writeBytes(payload.copyOfRange(0, 100_000))

        VmInstaller().install(
            image(VmImageFile(url = "http://127.0.0.1:${server.address.port}/img.iso", fileName = "img.iso")),
            dir,
        )

        val result = File(dir, "img.iso").readBytes()
        assertArrayEquals(payload, result)
        assertEquals(1, rangeRequests.get())
    }

    @Test
    fun `data disk is created with requested size`() {
        val source = File(tmp.root, "base.img").apply { writeBytes(ByteArray(1024)) }
        val dir = tmp.newFolder("data")
        val img = VmImage(
            id = "img",
            name = "img",
            files = listOf(VmImageFile(url = source.absolutePath, fileName = "base.img", role = VmFileRole.DISK)),
            boot = VmBootSpec(dataDiskMb = 1),
        )
        VmInstaller().install(img, dir)
        assertEquals(1024L * 1024L, File(dir, VmInstaller.DATA_DISK_NAME).length())
    }

    @Test
    fun `checksum mismatch fails the install`() {
        val source = File(tmp.root, "bad.iso").apply { writeBytes(ByteArray(2048) { 1 }) }
        val dir = tmp.newFolder("bad")
        val failing = runCatching {
            VmInstaller().install(
                image(
                    VmImageFile(
                        url = source.absolutePath,
                        fileName = "bad.iso",
                        sha256 = "00".repeat(32),
                    )
                ),
                dir,
            )
        }
        assertTrue(failing.isFailure)
        assertTrue(!File(dir, "bad.iso").exists())
    }

    private fun startServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handler(exchange) }
        server.start()
        servers += server
        return server
    }
}
