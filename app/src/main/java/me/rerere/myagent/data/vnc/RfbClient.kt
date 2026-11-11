package me.rerere.myagent.data.vnc

import android.graphics.Bitmap
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 最小 RFB(VNC) 3.8 客户端，只实现 QEMU 内置 VNC server 会用到的那部分：
 * None 认证、Raw 编码、PointerEvent / KeyEvent。
 *
 * 为什么不用现成库：本地回环、单连接、只需要「画面 + 触控」，引入一个网络库换不来任何东西，
 * 而协议本身不到 200 行。
 *
 * 线程模型：一个读线程持续收 FramebufferUpdate，[onFrame] 在 IO 线程回调；
 * 写操作（pointer/key）加锁串行。
 */
class RfbClient(
    private val host: String,
    private val port: Int,
    private val onFrame: (bitmap: Bitmap, width: Int, height: Int) -> Unit,
    private val onClosed: (Throwable?) -> Unit = {},
) {
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private val writeLock = Any()

    @Volatile
    private var running = false

    @Volatile
    var width: Int = 0
        private set

    @Volatile
    var height: Int = 0
        private set

    fun connect(timeoutMs: Int = 10_000) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        input = BufferedInputStream(s.getInputStream(), 64 * 1024)
        output = BufferedOutputStream(s.getOutputStream(), 16 * 1024)
        running = true
        Thread({ readLoop() }, "rfb-$host:$port").apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    /** x/y 已经是 guest 屏幕坐标；buttonMask bit0=左键 bit1=中键 bit2=右键 */
    fun pointer(x: Int, y: Int, buttonMask: Int) {
        if (!running) return
        val packet = ByteArray(6)
        packet[0] = 5
        packet[1] = buttonMask.toByte()
        packet[2] = (x shr 8).toByte(); packet[3] = x.toByte()
        packet[4] = (y shr 8).toByte(); packet[5] = y.toByte()
        write(packet)
    }

    fun key(keysym: Int, down: Boolean) {
        if (!running) return
        val packet = byteArrayOf(
            4.toByte(),
            (if (down) 1 else 0).toByte(),
            0.toByte(),
            0.toByte(),
            (keysym shr 24).toByte(),
            (keysym shr 16).toByte(),
            (keysym shr 8).toByte(),
            keysym.toByte(),
        )
        write(packet)
    }

    private fun write(bytes: ByteArray) {
        synchronized(writeLock) {
            try {
                output?.write(bytes)
                output?.flush()
            } catch (e: IOException) {
                Log.w(TAG, "rfb write failed", e)
                close()
            }
        }
    }

    private fun readLoop() {
        var failure: Throwable? = null
        try {
            handshake()
            requestFullUpdate()
            while (running) {
                readMessage()
            }
        } catch (e: Throwable) {
            if (running) {
                failure = e
                Log.w(TAG, "rfb loop ended", e)
            }
        } finally {
            running = false
            onClosed(failure)
        }
    }

    private fun handshake() {
        val serverVersion = readString(12)
        require(serverVersion.startsWith("RFB ")) { "Not a VNC server: $serverVersion" }
        // 只回 3.8：QEMU 同时接受 3.3/3.7/3.8，3.8 的认证流程最简单
        send("RFB 003.008\n".toByteArray(Charsets.US_ASCII))

        val securityCount = readByte()
        if (securityCount == 0) {
            val reasonLength = readU16()
            val reason = readString(reasonLength)
            throw IOException("VNC refused: $reason")
        }
        val types = ByteArray(securityCount) { readByte().toByte() }
        require(types.any { it.toInt() and 0xFF == 1 }) {
            "VNC requires authentication (types=${types.joinToString()}); only 'None' is supported"
        }
        write(byteArrayOf(1))
        val result = readU32()
        require(result == 0L) { "VNC security handshake failed: $result" }

        write(byteArrayOf(1)) // ClientInit: shared

        width = readU16()
        height = readU16()
        repeat(16) { readByte() } // server pixel format, overridden below
        val nameLength = readU32().toInt()
        readString(nameLength)

        // 客户端像素格式：32bpp、depth 24、big-endian、RGB
        val pixel = ByteArray(16)
        pixel[0] = 32           // bits-per-pixel
        pixel[1] = 24           // depth
        pixel[2] = 1            // big-endian
        pixel[3] = 1            // true-colour
        pixel[4] = 0; pixel[5] = 255.toByte()   // red-max
        pixel[6] = 0; pixel[7] = 255.toByte()   // green-max
        pixel[8] = 0; pixel[9] = 255.toByte()   // blue-max
        pixel[10] = 16          // red-shift
        pixel[11] = 8           // green-shift
        pixel[12] = 0           // blue-shift

        val setPixelFormat = ByteArray(4 + 16)
        setPixelFormat[0] = 0
        System.arraycopy(pixel, 0, setPixelFormat, 4, 16)
        write(setPixelFormat)

        // SetEncodings: Raw(0) + DesktopSize(-223)，后者让 QEMU 在分辨率变化时通知
        val encodings = intArrayOf(0, -223)
        val setEncodings = ByteArray(4 + encodings.size * 4)
        setEncodings[0] = 2
        setEncodings[2] = (encodings.size shr 8).toByte()
        setEncodings[3] = encodings.size.toByte()
        encodings.forEachIndexed { index, value ->
            val offset = 4 + index * 4
            setEncodings[offset] = (value shr 24).toByte()
            setEncodings[offset + 1] = (value shr 16).toByte()
            setEncodings[offset + 2] = (value shr 8).toByte()
            setEncodings[offset + 3] = value.toByte()
        }
        write(setEncodings)
    }

    private fun requestFullUpdate() {
        val req = ByteArray(10)
        req[0] = 3 // FramebufferUpdateRequest
        req[1] = 0 // incremental = false
        req[2] = 0; req[3] = 0
        req[4] = 0; req[5] = 0
        req[6] = (width shr 8).toByte(); req[7] = width.toByte()
        req[8] = (height shr 8).toByte(); req[9] = height.toByte()
        write(req)
    }

    private fun readMessage() {
        when (val type = readByte()) {
            0 -> readUpdate()
            1 -> readColourMap()
            2 -> Unit // Bell
            3 -> readCutText()
            else -> throw IOException("Unknown RFB message type $type")
        }
    }

    private fun readUpdate() {
        readByte() // padding
        val rects = readU16()
        repeat(rects) {
            val x = readU16()
            val y = readU16()
            val w = readU16()
            val h = readU16()
            val encoding = readS32()
            if (encoding == 0) {
                if (w <= 0 || h <= 0) return@repeat
                val pixels = IntArray(w * h)
                readRawPixels(pixels, w * h)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
                onFrame(bitmap, width, height)
            } else if (encoding == -223) {
                // DesktopSize 伪编码：分辨率变了，重新请求全屏
                width = w
                height = h
                requestFullUpdate()
            } else {
                // 没请求过的编码：丢弃该 rect 的负载无法安全跳过，直接断开让上层重连
                throw IOException("Unsupported RFB encoding $encoding")
            }
        }
        // 请求下一帧（增量）：让 server 只在有变化时回
        val req = ByteArray(10)
        req[0] = 3
        req[1] = 1
        req[6] = (width shr 8).toByte(); req[7] = width.toByte()
        req[8] = (height shr 8).toByte(); req[9] = height.toByte()
        write(req)
    }

    private fun readRawPixels(target: IntArray, count: Int) {
        val buffer = ByteArray(count * 4)
        var read = 0
        while (read < buffer.size) {
            val n = input!!.read(buffer, read, buffer.size - read)
            if (n < 0) throw EOFException("RFB stream ended mid-frame")
            read += n
        }
        for (i in 0 until count) {
            val o = i * 4
            // big-endian: [0, R, G, B]（depth 24）
            val r = buffer[o + 1].toInt() and 0xFF
            val g = buffer[o + 2].toInt() and 0xFF
            val b = buffer[o + 3].toInt() and 0xFF
            target[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun readColourMap() {
        readByte()
        readU16()
        val count = readU16()
        repeat(count) {
            readU16(); readU16(); readU16()
        }
    }

    private fun readCutText() {
        readByte(); readByte(); readByte()
        val length = readU32().toInt()
        if (length > 0) readString(length)
    }

    private fun readByte(): Int {
        val value = input!!.read()
        if (value < 0) throw EOFException("RFB stream ended")
        return value
    }

    private fun readU16(): Int {
        val hi = readByte()
        val lo = readByte()
        return (hi shl 8) or lo
    }

    private fun readU32(): Long {
        var value = 0L
        repeat(4) { value = (value shl 8) or readByte().toLong() }
        return value
    }

    private fun readS32(): Int = readU32().toInt()

    private fun readString(length: Int): String {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input!!.read(bytes, read, length - read)
            if (n < 0) throw EOFException("RFB stream ended")
            read += n
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun send(bytes: ByteArray) = write(bytes)

    private companion object {
        const val TAG = "RfbClient"
    }
}
