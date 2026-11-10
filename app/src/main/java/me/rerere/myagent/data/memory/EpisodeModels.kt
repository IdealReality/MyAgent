package me.rerere.myagent.data.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 记忆引擎 v1 · 数据模型（P1 存储层）
 *
 * 四层记忆（每助手一套，目录 `files/assistants/memory/<scopeId>/`）：
 *  - L1 常驻摘要   L1_summary.md        （唯一自动注入的正文）
 *  - L2 原话流     episodes/YYYY-MM.jsonl（一段对话=一行 JSON，永不自动注入）
 *  - L3 知识库     docs/ + INDEX.md      （memory_recall / memory_guide 按需）
 *  - L4 会话目录   sessions_index.json   （会话结束时更新，可检索）
 * 另有 cursor.json（切段游标）与 snapshots/（P3 快照安全网）。
 *
 * 正文一律文件落盘；旧 memoryentity 热记录停用、数据不迁移（用户已拍板）。
 */

/** L2 原话流的一段（一件事=一段）。 */
@Serializable
data class EpisodeSegment(
    /** 段 id：`e-<yyyyMMdd-HHmmss>-<短随机>`，跨月不重排。 */
    val id: String,
    /** 段开始时间（epoch ms）。 */
    val startTs: Long,
    /** 段最后活动时间（epoch ms）。 */
    val endTs: Long,
    /** 本段回合数（一条用户消息=一回合）。 */
    val turns: Int,
    /** 归属月份（yyyy-MM），与文件名一致。 */
    val month: String,
    /** 首条用户消息（切段判定的锚点）。 */
    val firstQuery: String = "",
    /** 末条用户消息。 */
    val lastQuery: String = "",
    /** 末条助手答复节选（收尾摘要用）。 */
    val replyExcerpt: String = "",
    /** 会话标题（L4 目录展示用）。 */
    val title: String = "",
    /** 关联会话 id。 */
    val conversationId: String = "",
    /** autolearn 生成的 1-2 句要点（P3 接入 memory 模型；失败留空不阻塞）。 */
    val gist: String = "",
    /** autolearn 是否已把本段整理进 L1。 */
    val learned: Boolean = false,
)

/** L4 会话目录条目（sessions_index.json）。 */
@Serializable
data class SessionIndexEntry(
    val conversationId: String,
    val title: String = "",
    val firstPrompt: String = "",
    val startTs: Long = 0,
    val endTs: Long = 0,
    val turns: Int = 0,
)

/** sessions_index.json 的容器。 */
@Serializable
data class SessionsIndex(
    val sessions: List<SessionIndexEntry> = emptyList(),
)

/** 切段游标（cursor.json）：当前正在累积的段。 */
@Serializable
data class MemoryCursor(
    val scopeId: String,
    val conversationId: String = "",
    val startTs: Long = 0,
    val lastActivityTs: Long = 0,
    val turns: Int = 0,
    val firstQuery: String = "",
    val lastQuery: String = "",
    /** 末条助手答复节选（收段时进 replyExcerpt）。 */
    val lastReply: String = "",
    /** 会话标题（收段时兜底）。 */
    val title: String = "",
)

/** jsonl 行 <-> EpisodeSegment。字段名固定，行解析失败跳过该行（损坏行不阻塞整文件）。 */
object EpisodeCodec {
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(segment: EpisodeSegment): String = json.encodeToString(EpisodeSegment.serializer(), segment)

    /** 解析一行；返回 null 表示该行损坏（调用方跳过）。 */
    fun decode(line: String): EpisodeSegment? = runCatching {
        json.decodeFromString(EpisodeSegment.serializer(), line)
    }.getOrNull()

    fun parseFile(file: File): List<EpisodeSegment> {
        if (!file.exists()) return emptyList()
        // 显式 UTF-8：Windows JVM 默认平台字符集（GBK），不指定会把 jsonl 读坏。
        return file.readLines(Charsets.UTF_8).mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty()) null else decode(t)
        }
    }
}

object SessionsIndexCodec {
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(index: SessionsIndex): String =
        json.encodeToString(SessionsIndex.serializer(), index)

    fun decode(text: String): SessionsIndex = runCatching {
        json.decodeFromString(SessionsIndex.serializer(), text)
    }.getOrDefault(SessionsIndex())

    fun decodeFile(file: File): SessionsIndex {
        if (!file.exists()) return SessionsIndex()
        return decode(file.readText())
    }
}

object MemoryCursorCodec {
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(cursor: MemoryCursor): String =
        json.encodeToString(MemoryCursor.serializer(), cursor)

    fun decodeFile(file: File): MemoryCursor? {
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(MemoryCursor.serializer(), file.readText())
        }.getOrNull()
    }
}

/** 段 id 生成：时间戳 + 4 位随机后缀，同毫秒也不冲突。 */
fun newEpisodeId(nowMs: Long, random: () -> Int = { (0 until 36 * 36 * 36 * 36).random() }): String {
    val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(nowMs))
    val suffix = random().toString(36).padStart(4, '0')
    return "e-$ts-$suffix"
}

/** 月份目录名（episodes/YYYY-MM.jsonl）。 */
fun monthKey(epochMs: Long): String {
    return java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date(epochMs))
}
