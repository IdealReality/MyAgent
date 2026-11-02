package me.rerere.rikkahub.data.memory

import java.io.File
import java.security.MessageDigest

/**
 * 记忆引擎 v1 · 快照安全网（P3）。平移 omp memory-snapshot.js：
 *
 *  - 触发：每次 autolearn 重写 L1 前（破坏性写之前）
 *  - 内容：scope 目录哈希清单（manifest：relPath -> sha256/size）+ 正文副本（files/）
 *  - 保留：滚动保留最近 N 份（默认 15），超出删最旧
 *  - 恢复：GUI 出恢复入口（P5）；本类提供 [restore]
 *
 * 快照目录：files/assistants/memory/<scopeId>/snapshots/<timestamp>/。
 */
object MemorySnapshot {


    const val KEEP = 15
    /** 单文件副本上限（超过只记哈希不拷贝，防快照膨胀）。 */
    const val MAX_COPY_BYTES: Long = 4 * 1024 * 1024
    const val SNAPSHOTS_DIR = "snapshots"

    data class SnapshotInfo(
        val dir: File,
        val timestamp: Long,
        val fileCount: Int,
    )

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun walk(root: File, rel: String = "", out: MutableList<Pair<File, String>> = mutableListOf()) {
        val dir = if (rel.isEmpty()) root else File(root, rel)
        val children = dir.listFiles() ?: return
        for (c in children.sortedBy { it.name }) {
            val r = if (rel.isEmpty()) c.name else "$rel/${c.name}"
            if (c.isDirectory) {
                if (c.name == SNAPSHOTS_DIR) continue // 快照不递归（防自吞）
                walk(root, r, out)
            } else {
                out.add(c to r)
            }
        }
    }

    /** 创建快照；返回快照目录。已存在同毫秒目录时顺延。 */
    fun create(store: AssistantMemoryStore, scopeId: String, nowMs: Long): File? {
        return runCatching {
            val scopeDir = store.dirFor(scopeId)
            if (!scopeDir.exists()) return null
            val snapRoot = File(scopeDir, SNAPSHOTS_DIR)
            val filesDir = File(snapRoot, "files")
            var ts = nowMs
            var snapDir = File(snapRoot, ts.toString())
            while (snapDir.exists()) {
                ts++
                snapDir = File(snapRoot, ts.toString())
            }
            snapDir.mkdirs()
            filesDir.mkdirs()

            val entries = mutableListOf<Pair<File, String>>()
            walk(scopeDir, out = entries)

            val manifest = StringBuilder("{\n")
            var first = true
            var copied = 0
            for ((file, rel) in entries) {
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
                val sha = sha256(bytes)
                if (!first) manifest.append(",\n")
                first = false
                manifest.append("  ${kotlinx.serialization.json.JsonPrimitive(rel).toString()}: {\"sha256\":\"$sha\",\"size\":${bytes.size}}")
                if (bytes.size <= MAX_COPY_BYTES) {
                    val dst = File(filesDir, rel)
                    dst.parentFile?.mkdirs()
                    dst.writeBytes(bytes)
                    copied++
                }
            }
            manifest.append("\n}\n")
            File(snapDir, "manifest.json").writeText(manifest.toString())
            File(snapDir, "meta.txt").writeText("timestamp=$ts\nfiles=${entries.size}\ncopied=$copied\n")
            prune(snapRoot)
            snapDir
        }.getOrNull()
    }

    /** 滚动保留最近 KEEP 份。 */
    fun prune(snapRoot: File) {
        val dirs = snapRoot.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return
        if (dirs.size <= KEEP) return
        dirs.take(dirs.size - KEEP).forEach { it.deleteRecursively() }
    }

    /** 快照列表（GUI 恢复入口用）。 */
    fun list(store: AssistantMemoryStore, scopeId: String): List<SnapshotInfo> {
        val snapRoot = File(store.dirFor(scopeId), SNAPSHOTS_DIR)
        return snapRoot.listFiles { f -> f.isDirectory }
            ?.mapNotNull { d ->
                val ts = d.name.toLongOrNull() ?: return@mapNotNull null
                val count = File(d, "manifest.json").length().toInt() // 近似
                SnapshotInfo(d, ts, count)
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    /**
     * 从快照恢复：把 files/ 副本写回 scope 目录（不动 snapshots 自身）。
     * 返回恢复的文件数。
     */
    fun restore(store: AssistantMemoryStore, scopeId: String, snapshotDir: File): Int {
        val filesDir = File(snapshotDir, "files")
        if (!filesDir.exists()) return 0
        val scopeDir = store.dirFor(scopeId)
        var n = 0
        filesDir.walkTopDown().filter { it.isFile }.forEach { src ->
            val rel = src.relativeTo(filesDir).path
            val dst = File(scopeDir, rel)
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            n++
        }
        return n
    }
}
