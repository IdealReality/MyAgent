package me.rerere.vm

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 按镜像条目把文件下到实例目录。
 *
 * 三条硬要求，决定可用性：
 * - **断点续传**：手机网络断一次不能把 1 GB 从头再下一遍，所以写 `.part` 并用 Range 续；
 * - **可校验**：有 sha256 就核对，避免半包的镜像启动时才炸；
 * - **可取消**：UI 取消时靠线程中断退出，且在退出时保留 `.part`。
 */
class VmInstaller(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000,
) {
    fun install(
        image: VmImage,
        instanceDir: File,
        onProgress: (VmInstallProgress) -> Unit = {},
    ): Map<VmFileRole, List<File>> {
        require(image.files.isNotEmpty()) { "Image ${image.id} has no files" }
        instanceDir.mkdirs()
        val result = mutableMapOf<VmFileRole, MutableList<File>>()

        image.files.forEachIndexed { index, file ->
            checkInterrupted()
            val target = File(instanceDir, sanitize(file.fileName))
            if (file.fromArchive.isNullOrBlank()) {
                ensureDownloaded(image, file, target, index, image.files.size, onProgress)
            }
            // fromArchive 的条目由调用方用 rootfs 里的解包工具生成；还没生成时不计入结果
            if (target.isFile) {
                result.getOrPut(file.role) { mutableListOf() } += target
            }
        }

        // 持久数据盘：框架自己生成，不属于下载内容
        if (image.boot.dataDiskMb > 0) {
            val data = File(instanceDir, DATA_DISK_NAME)
            ensureDataDisk(data, image.boot.dataDiskMb.toLong() * 1024 * 1024)
        }
        onProgress(
            VmInstallProgress(
                fileName = "",
                bytesRead = 0,
                totalBytes = image.sizeBytes,
                filesDone = image.files.size,
                filesTotal = image.files.size,
            )
        )
        return result
    }

    private fun ensureDownloaded(
        image: VmImage,
        spec: VmImageFile,
        target: File,
        index: Int,
        total: Int,
        onProgress: (VmInstallProgress) -> Unit,
    ) {
        // 已经下完且尺寸对得上就直接跳过，重复点「安装」不会重下
        if (target.isFile && spec.sizeBytes != null && target.length() == spec.sizeBytes) return
        if (target.isFile && spec.sizeBytes == null && target.length() > 0) return

        val part = File(target.parentFile, "${target.name}.part")
        // 文件换了源/尺寸变了，旧 part 不可续
        if (spec.sizeBytes != null && part.isFile && part.length() > spec.sizeBytes) {
            part.delete()
        }

        val urls = listOf(spec.url) + spec.mirrors
        var lastError: Throwable? = null
        for (url in urls) {
            try {
                download(url, part, spec.sizeBytes, image, index, total, onProgress)
                lastError = null
                break
            } catch (e: IOException) {
                lastError = e
            }
        }
        if (lastError != null) throw IOException("Download failed: ${spec.fileName}", lastError)

        spec.sha256?.let { expected ->
            val actual = sha256(part)
            require(actual.equals(expected, ignoreCase = true)) {
                "Checksum mismatch for ${spec.fileName}: expected $expected, got $actual"
            }
        }
        require(part.renameTo(target)) { "Failed to move download into place: ${target.name}" }
    }

    private fun download(
        url: String,
        part: File,
        expectedSize: Long?,
        image: VmImage,
        index: Int,
        total: Int,
        onProgress: (VmInstallProgress) -> Unit,
    ) {
        // 本地文件源：把镜像先放到共享存储再用它安装，比过一遍网络可靠得多
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            copyLocal(url, part, expectedSize, index, total, onProgress)
            return
        }
        val existing = if (part.isFile) part.length() else 0L
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            if (existing > 0) {
                setRequestProperty("Range", "bytes=$existing-")
            }
        }
        try {
            val code = connection.responseCode
            // 服务器不认 Range 时回 200，只能从零写
            val append = code == HttpURLConnection.HTTP_PARTIAL
            require(code in 200..299) { "HTTP $code for $url" }
            if (!append && existing > 0) part.delete()

            val contentLength = connection.contentLengthLong.takeIf { it > 0 }
            val totalBytes = expectedSize ?: contentLength?.let { if (append) existing + it else it }
            connection.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { output ->
                    if (!append) output.setLength(0)
                    output.seek(if (append) existing else 0)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = if (append) existing else 0L
                    var lastReport = 0L
                    while (true) {
                        checkInterrupted()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReport >= PROGRESS_STEP_BYTES || written == totalBytes) {
                            lastReport = written
                            onProgress(
                                VmInstallProgress(
                                    fileName = part.name.removeSuffix(".part"),
                                    bytesRead = written,
                                    totalBytes = totalBytes,
                                    filesDone = index,
                                    filesTotal = total,
                                )
                            )
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureDataDisk(file: File, size: Long) {
        if (file.isFile && file.length() == size) return
        RandomAccessFile(file, "rw").use { it.setLength(size) }
    }

    private fun copyLocal(
        url: String,
        part: File,
        expectedSize: Long?,
        index: Int,
        total: Int,
        onProgress: (VmInstallProgress) -> Unit,
    ) {
        val path = if (url.startsWith("file://")) java.io.File(java.net.URI(url)) else java.io.File(url)
        require(path.isFile) { "Local image not found: $path" }
        val totalBytes = expectedSize ?: path.length()
        path.inputStream().use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var written = 0L
                var lastReport = 0L
                while (true) {
                    checkInterrupted()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    if (written - lastReport >= PROGRESS_STEP_BYTES || written == totalBytes) {
                        lastReport = written
                        onProgress(
                            VmInstallProgress(
                                fileName = part.name.removeSuffix(".part"),
                                bytesRead = written,
                                totalBytes = totalBytes,
                                filesDone = index,
                                filesTotal = total,
                            )
                        )
                    }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                checkInterrupted()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("VM image install cancelled")
        }
    }

    private fun sanitize(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image.bin" }

    companion object {
        const val DATA_DISK_NAME = "data.img"
        private const val BUFFER_SIZE = 128 * 1024
        private const val PROGRESS_STEP_BYTES = 512 * 1024L
    }
}
