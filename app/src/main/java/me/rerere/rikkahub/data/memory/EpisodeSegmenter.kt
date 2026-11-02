package me.rerere.rikkahub.data.memory

/**
 * 切段判定（纯函数，可单测）。
 *
 * 一段 = 一件事。会话落段时机（DESIGN 1.3）：
 *  - 闲置 > 2h（下一封用户消息到来时发现间隔超限，先收上一段）
 *  - 换话题（首条消息与当前消息相似度过低）
 *  - 超过回合上限（默认 24 回合）
 *  - 会话结束时兜底（游标一直没收掉的下一次启动时收）
 *
 * 「换话题」判定平移 omp 的续跑守卫思路（task-boundary.js CONTINUATION_*）：
 * 续跑词开头 / 含续跑词的消息一律不算换话题；否则比较首问与当前问的 CJK 2-gram /
 * ASCII 词 Jaccard 相似度，低于阈值视为新话题。
 */
object EpisodeSegmenter {

    /** 闲置超时（ms）：2 小时。 */
    const val IDLE_SEGMENT_MS: Long = 2 * 60 * 60 * 1000L

    /** 单段回合上限。 */
    const val MAX_TURNS_PER_SEGMENT: Int = 24

    /** 话题相似度阈值：低于该值视为新话题。 */
    const val TOPIC_SIMILARITY_THRESHOLD: Double = 0.18

    /** 新消息最小长度：太短的输入（"嗯"、"好"）不足以判话题。 */
    const val TOPIC_MIN_CHARS: Int = 8

    // 续跑信号（提示词开头命中 = 接着上一件事说）。平移 task-boundary.js L114-118。
    private val CONTINUATION_HEAD = Regex(
        "^(继续|接着|再说|再试|再跑|再来|重新|还有|另外|顺便|顺带|同时|然后|以及|补充|刚才|上面|之前|那个|这个|这些|那些|它|他|她|他们|你|我们|为什么|为何|怎么|如何|能不能|可否|不对|不是|但是|不过|而且|嗯|哦|好吧|好|行|可以|ok|okay|go on|continue|and\\b|but\\b|also\\b|what about|why\\b|how\\b|again|more|same)",
        RegexOption.IGNORE_CASE,
    )
    private val CONTINUATION_ANY = Regex("(继续|接着|顺便|顺带|刚才|上面|再说|还有)")

    /** 是否续跑消息（不参与话题判定）。 */
    fun isContinuationMessage(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (CONTINUATION_HEAD.containsMatchIn(t)) return true
        if (CONTINUATION_ANY.containsMatchIn(t)) return true
        return false
    }

    /**
     * CJK 2-gram + ASCII 词集合。用于话题相似度（与 P2 检索的分词独立，不求精确）。
     */
    fun tokenizeForOverlap(text: String): Set<String> {
        val out = HashSet<String>()
        val sb = StringBuilder()
        fun flushAscii() {
            if (sb.isNotEmpty()) {
                out.add(sb.toString().lowercase())
                sb.clear()
            }
        }
        var i = 0
        val chars = text.lowercase()
        while (i < chars.length) {
            val c = chars[i]
            when {
                c.isLetterOrDigit() && c.code < 128 -> { sb.append(c); i++ }
                c.code in 0x4E00..0x9FFF -> {
                    flushAscii()
                    if (i + 1 < chars.length && chars[i + 1].code in 0x4E00..0x9FFF) {
                        out.add(chars.substring(i, i + 2))
                    }
                    i++
                }
                else -> { flushAscii(); i++ }
            }
        }
        flushAscii()
        return out
    }

    /** Jaccard 相似度。 */
    fun similarity(a: String, b: String): Double {
        val sa = tokenizeForOverlap(a)
        val sb = tokenizeForOverlap(b)
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        val inter = sa.intersect(sb).size.toDouble()
        val union = sa.union(sb).size.toDouble()
        return inter / union
    }

    /**
     * 判定当前消息是否应开启新段（即收掉上一段）。
     * @param cursor 上一段游标；null 表示无历史，直接开新段（不收）。
     * @param nowMs 当前时间
     * @param text 当前用户消息
     */
    sealed interface Decision {
        /** 无需切段：继续累积当前段。 */
        object Continue : Decision
        /** 先落上一段（已有内容），再开新段。 */
        data class CloseAndStart(val reason: String) : Decision
        /** 直接开新段（上一段无内容，静默丢弃）。 */
        data class StartOnly(val reason: String) : Decision
    }

    fun decide(cursor: MemoryCursor?, nowMs: Long, text: String): Decision {
        if (cursor == null) return Decision.StartOnly("fresh")
        if (cursor.turns <= 0) return Decision.StartOnly("empty-cursor")
        if (nowMs - cursor.lastActivityTs > IDLE_SEGMENT_MS) {
            return Decision.CloseAndStart("idle>${IDLE_SEGMENT_MS / 60000}min")
        }
        if (cursor.turns >= MAX_TURNS_PER_SEGMENT) {
            return Decision.CloseAndStart("turns>=${MAX_TURNS_PER_SEGMENT}")
        }
        val trimmed = text.trim()
        if (trimmed.length >= TOPIC_MIN_CHARS && !isContinuationMessage(trimmed)) {
            val sim = similarity(cursor.firstQuery, trimmed)
            if (sim < TOPIC_SIMILARITY_THRESHOLD) {
                return Decision.CloseAndStart("topic-change(sim=%.2f)".format(sim))
            }
        }
        return Decision.Continue
    }
}
