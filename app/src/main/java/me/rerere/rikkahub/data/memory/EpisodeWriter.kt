package me.rerere.rikkahub.data.memory

import java.util.concurrent.ConcurrentHashMap

/**
 * L2 写入链（P1）。挂在 ChatService 生命周期：
 *
 *  - [onUserTurn]：每次用户消息进入生成前调用——先按 [EpisodeSegmenter.decide] 判定
 *    是否收掉上一段（闲置>2h / 换话题 / 超 24 回合），再把本回合累积进游标。
 *  - [onAssistantReply]：回合结束时补末答节选与标题。
 *  - [onConversationEnded]：会话结束兜底收段，并更新 L4 目录。
 *
 * 「段」的中间状态放在 cursor.json：崩了最多丢一段的收尾，不丢已落盘的段。
 */
class EpisodeWriter(
    private val store: AssistantMemoryStore,
) {
    companion object {
        private const val TAG = "EpisodeWriter"
    }

    /** 防止同一会话并发收段。 */
    private val closing = ConcurrentHashMap.newKeySet<String>()

    /**
     * 用户回合开始。
     * @param scopeId 记忆 scope（助手 id 或 __global__）
     * @param conversationId 会话 id
     * @param userText 用户消息纯文本（预处理后）
     * @param title 会话标题（可能还是空的）
     */
    fun onUserTurn(scopeId: String, conversationId: String, userText: String, title: String, nowMs: Long) {
        runCatching {
            val text = userText.trim()
            val cursor = store.readCursor(scopeId)
            when (val d = EpisodeSegmenter.decide(cursor, nowMs, text)) {
                is EpisodeSegmenter.Decision.Continue -> {
                    // decide() 对 null/空游标都返回 StartOnly，这里 cursor 必非空且同会话；
                    // 换会话（用户切走又回来）强制开新段。
                    val c = cursor!!
                    if (c.conversationId != conversationId) {
                        closeCursorToEpisode(c)
                        startCursor(scopeId, conversationId, text, title, nowMs)
                    } else {
                        store.writeCursor(
                            scopeId,
                            c.copy(
                                lastActivityTs = nowMs,
                                turns = c.turns + 1,
                                lastQuery = text.take(AssistantMemoryStore.EXCERPT_CHARS),
                                title = title.ifBlank { c.title },
                            ),
                        )
                    }
                }
                is EpisodeSegmenter.Decision.CloseAndStart -> {
                    cursor?.let { closeCursorToEpisode(it) }
                    startCursor(scopeId, conversationId, text, title, nowMs)
                    MemLog.d(TAG, "episode closed+started (${d.reason}) scope=$scopeId")
                }
                is EpisodeSegmenter.Decision.StartOnly -> {
                    startCursor(scopeId, conversationId, text, title, nowMs)
                }
            }
        }.onFailure { MemLog.w(TAG, "onUserTurn failed: ${it.message}") }
    }

    /** 回合结束：补末答节选与标题（cursor.conversationId 不匹配时忽略——上一段已收）。 */
    fun onAssistantReply(scopeId: String, conversationId: String, replyText: String, title: String, nowMs: Long) {
        runCatching {
            val cursor = store.readCursor(scopeId) ?: return
            if (cursor.conversationId != conversationId) return
            store.writeCursor(
                scopeId,
                cursor.copy(
                    lastActivityTs = nowMs,
                    lastReply = replyText.take(AssistantMemoryStore.EXCERPT_CHARS),
                    title = title.ifBlank { cursor.title },
                ),
            )
        }.onFailure { MemLog.w(TAG, "onAssistantReply failed: ${it.message}") }
    }

    /**
     * 会话结束（onGenerationFinished 且无排队 / session 移除）：落段 + 更新 L4 目录。
     * userText/replyText/title 为空时用 cursor 里累积的值。
     */
    fun onConversationEnded(
        scopeId: String,
        conversationId: String,
        userText: String = "",
        replyText: String = "",
        title: String = "",
        nowMs: Long,
    ): EpisodeSegment? {
        if (!closing.add(conversationId)) return null
        try {
            val cursor = store.readCursor(scopeId) ?: return null
            if (cursor.conversationId != conversationId) return null
            val seg = closeCursorToEpisode(
                cursor.copy(
                    lastQuery = userText.take(AssistantMemoryStore.EXCERPT_CHARS)
                        .ifBlank { cursor.lastQuery },
                    lastReply = replyText.take(AssistantMemoryStore.EXCERPT_CHARS)
                        .ifBlank { cursor.lastReply },
                    title = title.ifBlank { cursor.title },
                ),
            )
            store.upsertSessionEntry(
                scopeId,
                SessionIndexEntry(
                    conversationId = conversationId,
                    title = title.ifBlank { cursor.title }.ifBlank { cursor.firstQuery.take(60) },
                    firstPrompt = cursor.firstQuery.take(AssistantMemoryStore.EXCERPT_CHARS),
                    startTs = cursor.startTs,
                    endTs = nowMs,
                    turns = cursor.turns,
                ),
            )
            return seg
        } finally {
            closing.remove(conversationId)
        }
    }

    /** 收掉游标成段（无回合则不落）。 */
    private fun closeCursorToEpisode(cursor: MemoryCursor): EpisodeSegment? {
        if (cursor.turns <= 0) {
            store.clearCursor(cursor.scopeId)
            return null
        }
        val endTs = cursor.lastActivityTs
        val seg = EpisodeSegment(
            id = newEpisodeId(endTs),
            startTs = cursor.startTs,
            endTs = endTs,
            turns = cursor.turns,
            month = monthKey(endTs),
            firstQuery = cursor.firstQuery,
            lastQuery = cursor.lastQuery,
            replyExcerpt = cursor.lastReply,
            title = cursor.title.ifBlank { cursor.firstQuery.take(60) },
            conversationId = cursor.conversationId,
        )
        store.appendEpisode(cursor.scopeId, seg)
        store.clearCursor(cursor.scopeId)
        return seg
    }

    private fun startCursor(scopeId: String, conversationId: String, text: String, title: String, nowMs: Long) {
        store.writeCursor(
            scopeId,
            MemoryCursor(
                scopeId = scopeId,
                conversationId = conversationId,
                startTs = nowMs,
                lastActivityTs = nowMs,
                turns = 1,
                firstQuery = text.take(AssistantMemoryStore.EXCERPT_CHARS),
                lastQuery = text.take(AssistantMemoryStore.EXCERPT_CHARS),
                title = title,
            ),
        )
    }
}
