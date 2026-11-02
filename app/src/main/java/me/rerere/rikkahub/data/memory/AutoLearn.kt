package me.rerere.rikkahub.data.memory

/**
 * 记忆引擎 v1 · autolearn 整理管线（P3，纯逻辑部分）。
 *
 * 触发（DESIGN 1.3）：未整理段 ≥10 或距上次整理 ≥7 天。
 * 动作：读最旧 N 段 → 用 memory 模型角色重写 L1（保留四节结构）→ 标记 learned。
 * L2 原话永不删除，整理只重写 L1。
 *
 * LLM 调用在 [AutoLearnRunner]（挂 ChatService 的 providerManager，WorkManager 触发）；
 * 本 object 只做纯计算（触发判定 + L1 合并提示词 + 合并结果解析），可单测。
 */
object AutoLearn {


    /** 未整理段数阈值。 */
    const val UNLEARNED_SEGMENT_THRESHOLD = 10
    /** 距上次整理天数阈值。 */
    const val DAYS_THRESHOLD = 7L
    /** 单次整理读取的最大段数。 */
    const val MAX_SEGMENTS_PER_RUN = 20
    /** L1 字数上限（DESIGN 1.1：≤2000 字）。 */
    const val L1_MAX_CHARS = 2000

    /** lastLearnedAt 记录文件（scope 内）。 */
    const val LAST_LEARNED_FILE = "last_learned_at"

    /** 触发判定。 */
    data class Trigger(val shouldRun: Boolean, val reason: String)

    fun shouldTrigger(store: AssistantMemoryStore, scopeId: String, nowMs: Long): Trigger {
        val unlearned = store.unlearnedEpisodes(scopeId)
        if (unlearned.size >= UNLEARNED_SEGMENT_THRESHOLD) {
            return Trigger(true, "unlearned=${unlearned.size}>=${UNLEARNED_SEGMENT_THRESHOLD}")
        }
        val lastFile = java.io.File(store.dirFor(scopeId), LAST_LEARNED_FILE)
        val last = if (lastFile.exists()) runCatching { lastFile.readText().trim().toLong() }.getOrDefault(0L) else 0L
        if (last == 0L) {
            // 从未整理过：有未整理段就跑（哪怕是第 1 段之后）
            if (unlearned.isNotEmpty()) return Trigger(true, "never-learned unlearned=${unlearned.size}")
            return Trigger(false, "empty")
        }
        val days = (nowMs - last) / (24 * 60 * 60 * 1000L)
        if (days >= DAYS_THRESHOLD && unlearned.isNotEmpty()) {
            return Trigger(true, "days=$days>=$DAYS_THRESHOLD unlearned=${unlearned.size}")
        }
        return Trigger(false, "unlearned=${unlearned.size} days=$days")
    }

    /** 待整理段（最旧优先，截到 MAX_SEGMENTS_PER_RUN）。 */
    fun pickSegments(store: AssistantMemoryStore, scopeId: String): List<EpisodeSegment> =
        store.unlearnedEpisodes(scopeId)
            .sortedBy { it.startTs }
            .take(MAX_SEGMENTS_PER_RUN)

    /** 重写 L1 的用户提示词（memory 模型角色调用；系统提示词固定）。 */
    fun buildRewritePrompt(oldL1: String, segments: List<EpisodeSegment>): String = buildString {
        appendLine("你是记忆整理器。根据新对话段落更新助手的 L1 常驻摘要。")
        appendLine()
        appendLine("规则：")
        appendLine("- 保留四节结构：## 用户偏好 / ## 项目事实 / ## 进行中事务 / ## 长期约定")
        appendLine("- 只吸收下面段落里**值得长期记住**的信息；一次性细节（问候、寒暄）丢弃")
        appendLine("- 进行中事务按最新状态改写（完成的删掉，新增的加上）")
        appendLine("- 全文不超过 ${L1_MAX_CHARS} 字")
        appendLine("- 直接输出新的 L1 markdown 正文，不要任何解释")
        appendLine()
        appendLine("=== 当前 L1 ===")
        appendLine(oldL1.ifBlank { "（空）" })
        appendLine()
        appendLine("=== 新对话段落 ===")
        for (seg in segments) {
            appendLine("- [${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(seg.startTs))}] ${seg.title.ifBlank { seg.firstQuery.take(60) }}")
            if (seg.gist.isNotBlank()) appendLine("  要点: ${seg.gist}")
            if (seg.replyExcerpt.isNotBlank()) appendLine("  答复节选: ${seg.replyExcerpt.take(160)}")
        }
    }

    /** 解析模型返回：取 markdown 正文，截断到 L1_MAX_CHARS。 */
    fun parseRewrite(response: String): String {
        var text = response.trim()
        // 剥掉代码围栏（模型常包一层 ```markdown）
        if (text.startsWith("```")) {
            text = text.removePrefix("```markdown").removePrefix("```").trim()
            if (text.endsWith("```")) text = text.dropLast(3).trim()
        }
        return text.take(L1_MAX_CHARS)
    }

    /** 整理完成后：标记 learned + 记录时间。 */
    fun commit(store: AssistantMemoryStore, scopeId: String, segments: List<EpisodeSegment>, newL1: String, nowMs: Long) {
        store.writeL1(scopeId, newL1)
        for (seg in segments) {
            store.updateEpisode(scopeId, seg.copy(learned = true))
        }
        val lastFile = java.io.File(store.dirFor(scopeId), LAST_LEARNED_FILE)
        lastFile.parentFile?.mkdirs()
        lastFile.writeText(nowMs.toString())
    }
}
