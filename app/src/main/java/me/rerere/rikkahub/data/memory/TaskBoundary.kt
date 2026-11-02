package me.rerere.rikkahub.data.memory

/**
 * 记忆引擎 v1 · 任务边界检测（P4）。平移 omp task-boundary.js 的判定规则：
 *
 *  - 续跑守卫：CONTINUATION_HEAD_RE / CONTINUATION_ANY_RE（词表照抄），
 *    「继续/还有/顺便」类消息一律不折叠
 *  - 新任务判定：长度 ≥15 字、非续跑词开头、不含续跑词、不是问句、不含未完成信号
 *  - 折叠在请求组装时做（ChatService 生成前对 request-only 视图折叠），不落盘、
 *    不写 compaction 条目、界面不动
 *  - 边界前消息折成三层摘要（上任务指针/上任务总结/更早概览）≤3000 字
 */
object TaskBoundary {

    // 续跑信号（平移 task-boundary.js L114-118 词表）
    private val CONTINUATION_HEAD = Regex(
        "^(继续|接着|再说|再试|再跑|再来|重新|还有|另外|顺便|顺带|同时|然后|以及|补充|刚才|上面|之前|那个|这个|这些|那些|它|他|她|他们|你|我们|为什么|为何|怎么|如何|能不能|可否|不对|不是|但是|不过|而且|嗯|哦|好吧|好|行|可以|ok|okay|go on|continue|and\\b|but\\b|also\\b|what about|why\\b|how\\b|again|more|same)",
        RegexOption.IGNORE_CASE,
    )
    private val CONTINUATION_ANY = Regex("(继续|接着|顺便|顺带|刚才|上面|再说|还有)")

    // 未完成信号（答复尾部）：未完成语气/待办/问句
    private val UNFINISHED_RE = Regex(
        "(待续|未完|稍后|马上|正在处理|进行中|TODO|todo|待办|接下来我|让我|我先|还没|尚未|先看一下|正在查|加载中|思考中)",
    )

    const val NEW_TASK_MIN_CHARS = 15
    const val DIGEST_MAX_CHARS = 3000

    /** 消息看起来是新任务（才允许折叠）？ */
    fun looksLikeNewTask(prompt: String): Boolean {
        val t = prompt.trim()
        if (t.length < NEW_TASK_MIN_CHARS) return false
        if (CONTINUATION_HEAD.containsMatchIn(t)) return false
        if (CONTINUATION_ANY.containsMatchIn(t)) return false
        if (t.endsWith("?") || t.endsWith("？")) return false
        if (UNFINISHED_RE.containsMatchIn(t.takeLast(60))) return false
        return true
    }

    /** 答复是否看起来已收尾（实质内容 + 非未完成语气 + 非问句结尾）。 */
    fun replyLooksFinished(replyText: String): Boolean {
        val t = replyText.trim()
        if (t.length < 30) return false
        if (t.endsWith("?") || t.endsWith("？")) return false
        if (UNFINISHED_RE.containsMatchIn(t.takeLast(80))) return false
        return true
    }

    /**
     * 折叠决策：给定边界前的对话（用户消息与答复交错），新任务消息进来时判断是否折叠。
     *
     * @param priorUserPrompts 边界前各轮用户提示（自旧到新）
     * @param lastReply 最后一条助手答复
     * @param newTaskPrompt 触发判定的下一条用户消息
     * @param priorTokensEstimate 边界前消息的 token 估算（上下文够大才值得折叠）
     * @param minFoldTokens 折叠的最小上下文规模（默认 4000，对齐 omp MIN_TOKENS_DEFAULT）
     */
    data class FoldDecision(
        val shouldFold: Boolean,
        val reason: String,
    )

    fun shouldFold(
        priorUserPrompts: List<String>,
        lastReply: String,
        newTaskPrompt: String,
        priorTokensEstimate: Int,
        minFoldTokens: Int = 4000,
    ): FoldDecision {
        if (!looksLikeNewTask(newTaskPrompt)) {
            return FoldDecision(false, "continuation-or-short")
        }
        if (priorUserPrompts.isEmpty()) return FoldDecision(false, "no-prior")
        if (!replyLooksFinished(lastReply)) return FoldDecision(false, "reply-not-finished")
        if (priorTokensEstimate < minFoldTokens) return FoldDecision(false, "context-too-small")
        return FoldDecision(true, "new-task+finished+enough-context")
    }

    /**
     * 组装折叠摘要（三层，≤DIGEST_MAX_CHARS）：
     *  1. 上任务指针（对话存档在 L2 段与 L4 目录，检索入口提示）
     *  2. 上任务总结（最后一轮用户请求 + 答复节选）
     * 3. 更早概览（更早各轮用户提示，自新到旧）
     */
    fun buildDigest(
        priorUserPrompts: List<String>,
        lastReply: String,
        conversationId: String,
    ): String = buildString {
        appendLine("[任务边界摘要 · 上下文已折叠]")
        appendLine("上一任务的完整原话已存入记忆（L2 原话流 + L4 会话目录），需要细节时用 memory_recall 检索。")
        appendLine()
        appendLine("### 上一任务总结")
        val lastUser = priorUserPrompts.lastOrNull()?.take(400) ?: "（无）"
        appendLine("用户请求：$lastUser")
        val reply = lastReply.trim().take(800)
        if (reply.isNotEmpty()) appendLine("最终答复节选：$reply")
        appendLine()
        appendLine("### 更早对话概览")
        priorUserPrompts.dropLast(1).reversed().take(8).forEach { p ->
            appendLine("- ${p.take(100)}")
        }
        // 兜底截断
        val out = toString()
        if (out.length > DIGEST_MAX_CHARS) {
            clear()
            append(out.take(DIGEST_MAX_CHARS - 20))
            appendLine()
            append("…（已截断）")
        }
    }
}
