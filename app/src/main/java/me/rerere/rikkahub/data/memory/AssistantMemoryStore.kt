package me.rerere.rikkahub.data.memory

import java.io.File

/**
 * 助手记忆存储（P1 存储层）。每助手一套四层目录：
 *
 * ```
 * files/assistants/memory/<scopeId>/
 *   L1_summary.md          L1 常驻摘要（唯一自动注入正文）
 *   episodes/YYYY-MM.jsonl L2 原话流
 *   docs/ + INDEX.md       L3 知识库（收编现有 ColdMemory 的文档角色）
 *   sessions_index.json    L4 会话目录
 *   cursor.json            切段游标（当前累积段）
 *   snapshots/             快照安全网（P3）
 * ```
 *
 * scopeId 沿用旧记忆的语义：助手自己的 id，或 `__global__`（useGlobalMemory）。
 * 正文一律文件，正文编码 UTF-8；所有写操作先写临时文件再原子 rename（损坏安全）。
 *
 * 纯 java.io.File —— 可 JVM 单测（P1 配套 EpisodeSegmenterTest / MemoryStoreTest）。
 */
class AssistantMemoryStore(
    /** 记忆根：传 `File(context.filesDir, "assistants/memory")`（生产）或临时目录（测试）。 */
    private val memoryRoot: File,
) {
    companion object {
        private const val TAG = "AssistantMemoryStore"
        /** 全局共享记忆 scope（沿用 MemoryRepository.GLOBAL_MEMORY_ID 语义）。 */
        const val GLOBAL_SCOPE_ID = "__global__"
        /** L4 会话目录最大保留条数（超出丢最旧）。 */
        const val MAX_SESSION_ENTRIES = 500
        /** 摘要/节选字段统一截断长度。 */
        const val EXCERPT_CHARS = 400
    }

    /** scopeId -> 记忆目录。 */
    fun dirFor(scopeId: String): File = File(memoryRoot, scopeId)

    // ---------- L1 ----------

    fun readL1(scopeId: String): String {
        val f = File(dirFor(scopeId), "L1_summary.md")
        return if (f.exists()) runCatching { f.readText() }.getOrDefault("") else ""
    }

    fun writeL1(scopeId: String, content: String) {
        writeFileAtomic(File(dirFor(scopeId), "L1_summary.md"), content)
    }

    // ---------- L2 ----------

    /** 追加一段到 episodes/YYYY-MM.jsonl（endTs 定月份；原子追加：写临时行再 cat 追加不可靠，直接同步 append）。 */
    fun appendEpisode(scopeId: String, segment: EpisodeSegment) {
        val dir = File(dirFor(scopeId), "episodes")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "${segment.month}.jsonl")
        runCatching {
            file.appendText(EpisodeCodec.encode(segment) + "\n")
        }.onFailure { MemLog.w(TAG, "appendEpisode failed: ${it.message}") }
    }

    fun listEpisodeFiles(scopeId: String): List<File> {
        val dir = File(dirFor(scopeId), "episodes")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /** 全部段（旧→新）。 */
    fun readAllEpisodes(scopeId: String): List<EpisodeSegment> =
        listEpisodeFiles(scopeId).flatMap { EpisodeCodec.parseFile(it) }

    /** 未整理段（autolearn 输入）。 */
    fun unlearnedEpisodes(scopeId: String): List<EpisodeSegment> =
        readAllEpisodes(scopeId).filter { !it.learned }

    /** 更新段（autolearn 标记 learned / 补 gist 后回写；按 id 定位所在文件重写该文件）。 */
    fun updateEpisode(scopeId: String, updated: EpisodeSegment): Boolean {
        val dir = File(dirFor(scopeId), "episodes")
        if (!dir.exists()) return false
        val file = File(dir, "${updated.month}.jsonl")
        if (!file.exists()) return false
        val segments = EpisodeCodec.parseFile(file).toMutableList()
        val idx = segments.indexOfFirst { it.id == updated.id }
        if (idx < 0) return false
        segments[idx] = updated
        return runCatching {
            writeFileAtomic(file, segments.joinToString("\n") { EpisodeCodec.encode(it) } + "\n")
            true
        }.getOrDefault(false)
    }

    // ---------- L3 ----------

    fun docsDir(scopeId: String): File = File(dirFor(scopeId), "docs")

    // ---------- L4 ----------

    fun readSessionsIndex(scopeId: String): SessionsIndex =
        SessionsIndexCodec.decodeFile(File(dirFor(scopeId), "sessions_index.json"))

    /** 追加/更新一个会话条目（按 conversationId 去重，超出上限丢最旧）。 */
    fun upsertSessionEntry(scopeId: String, entry: SessionIndexEntry) {
        val file = File(dirFor(scopeId), "sessions_index.json")
        val index = readSessionsIndex(scopeId)
        val others = index.sessions.filter { it.conversationId != entry.conversationId }
        val merged = (others + entry).sortedByDescending { it.endTs }.take(MAX_SESSION_ENTRIES)
        writeFileAtomic(file, SessionsIndexCodec.encode(SessionsIndex(merged)))
    }

    // ---------- 游标 ----------

    fun readCursor(scopeId: String): MemoryCursor? = MemoryCursorCodec.decodeFile(File(dirFor(scopeId), "cursor.json"))

    fun writeCursor(scopeId: String, cursor: MemoryCursor) {
        writeFileAtomic(File(dirFor(scopeId), "cursor.json"), MemoryCursorCodec.encode(cursor))
    }

    fun clearCursor(scopeId: String) {
        File(dirFor(scopeId), "cursor.json").delete()
    }

    // ---------- 通用 ----------

    private fun writeFileAtomic(file: File, content: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // Windows/部分 FS 上 rename 不覆盖：显式删后重试。
                file.delete()
                if (!tmp.renameTo(file)) error("rename failed: ${file.name}")
            }
        }.onFailure { MemLog.w(TAG, "writeFileAtomic(${file.name}) failed: ${it.message}") }
    }

    /** 统计信息（GUI 总览区用）。 */
    data class Stats(
        val episodeCount: Int,
        val unlearnedCount: Int,
        val sessionCount: Int,
        val l1Chars: Int,
        val docsCount: Int,
    )

    fun stats(scopeId: String): Stats {
        val eps = readAllEpisodes(scopeId)
        val docs = docsDir(scopeId).listFiles { f -> f.isFile }?.size ?: 0
        return Stats(
            episodeCount = eps.size,
            unlearnedCount = eps.count { !it.learned },
            sessionCount = readSessionsIndex(scopeId).sessions.size,
            l1Chars = readL1(scopeId).length,
            docsCount = docs,
        )
    }

    /** 记忆目录是否已有任何内容（GUI 判空）。 */
    fun exists(scopeId: String): Boolean = dirFor(scopeId).exists()
}
