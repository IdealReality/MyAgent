package me.rerere.myagent.data.memory

import java.security.MessageDigest

/**
 * 记忆引擎 v1 · CCR 工具输出原文池（P4）。平移 omp tool-compress.mjs：
 *
 *  - 内容类型路由（detectKind，顺序即优先级）：search / jsonl / diff / log / table / text
 *  - 超长输出按 sha256 内容寻址落盘（cache/tool-output/<sha256>.txt），请求内保留
 *    头尾预览 + handle；模型用 shell 的 sed/head/tail 按 `handle:N-M` 行号取回
 *  - 智能预览：search 结果保留行结构（clip 长行）、diff 保留 hunk 头、log 保头尾、
 *    jsonl 保首尾行 —— 替换旧 truncateToolResult 的"一刀切 head60/tail40"
 *
 * 纯函数部分（detectKind/smartPreview/handleOf）可单测；落盘由 GenerationLoop 做。
 */
object ToolOutputPool {

    enum class Kind { SEARCH, JSONL, DIFF, LOG, TABLE, TEXT }

    // detectKind 平移 tool-compress.mjs L180（顺序即优先级）
    private val SEARCH_HINTS = listOf("google", "bing", "duckduckgo", "search result", "搜索结果", "results for", "total_results")
    private val JSONL_HINTS = Regex("^\\s*\\{.*\\}\\s*$", RegexOption.MULTILINE)
    private val DIFF_MARKERS = Regex("^(diff --git|--- a/|\\+\\+\\+ b/|@@ -|Index: )", RegexOption.MULTILINE)
    private val LOG_MARKERS = Regex("(^\\d{2}:\\d{2}:\\d{2}| \\[(WARN|ERROR|INFO|DEBUG)\\] |Exception|Traceback|at [\\w.]+\\()", RegexOption.MULTILINE)

    fun detectKind(text: String, toolName: String? = null): Kind {
        val t = text.take(8000)
        val n = (toolName ?: "").lowercase()
        if (n.contains("search") || n.contains("web")) return Kind.SEARCH
        if (SEARCH_HINTS.any { t.lowercase().contains(it) }) return Kind.SEARCH
        if (t.contains("event:") && t.contains(":")) return Kind.JSONL
        if (JSONL_HINTS.containsMatchIn(t.takeLast(2000))) return Kind.JSONL
        if (DIFF_MARKERS.containsMatchIn(t)) return Kind.DIFF
        if (LOG_MARKERS.containsMatchIn(t)) return Kind.LOG
        // 表格：大量竖线行
        val lines = t.lines()
        if (lines.size >= 4 && lines.count { it.contains('|') } > lines.size / 2) return Kind.TABLE
        return Kind.TEXT
    }

    /** sha256 内容寻址 handle（前 16 hex）。 */
    fun handleOf(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * 智能头尾预览。与 truncateToolResult 的纯字符切不同：
     *  - SEARCH/JSONL/LOG/TABLE：按行组装（头 N 行 + 尾 M 行），行内超长 clip
     *  - DIFF：保头（新 hunk 在头部）+ 尾（最终状态在尾部）
     *  - TEXT：head/tail 字符切（对齐旧行为）
     */
    fun smartPreview(fullText: String, headChars: Int, tailChars: Int, kind: Kind? = null): String {
        val k = kind ?: detectKind(fullText)
        val budget = (headChars + tailChars).coerceAtLeast(200)
        return when (k) {
            Kind.TEXT -> charPreview(fullText, headChars, tailChars)
            else -> linePreview(fullText, budget, k)
        }
    }

    private fun charPreview(text: String, headChars: Int, tailChars: Int): String {
        if (text.length <= headChars + tailChars) return text
        val head = text.take(headChars).trimEnd()
        val tail = text.takeLast(tailChars).trimStart()
        val elided = text.length - head.length - tail.length
        return "$head\n…（中间省略 $elided 字符，全文见 handle）…\n$tail"
    }

    private fun linePreview(text: String, budgetChars: Int, kind: Kind): String {
        val lines = text.lines()
        val maxLine = when (kind) {
            Kind.SEARCH -> 300
            Kind.JSONL -> 240
            Kind.LOG -> 200
            Kind.DIFF -> 240
            Kind.TABLE -> 200
            Kind.TEXT -> 200
        }
        fun clip(line: String): String = if (line.length > maxLine) line.take(maxLine) + "…" else line
        val clipped = lines.map { clip(it) }
        val total = clipped.sumOf { it.length + 1 }
        if (total <= budgetChars) return clipped.joinToString("\n")
        // 头 60% 尾 40%（行级）
        var headBudget = budgetChars * 6 / 10
        var tailBudget = budgetChars - headBudget
        val headLines = ArrayList<String>()
        for (l in clipped) {
            if (headBudget - l.length < 0) break
            headLines.add(l)
            headBudget -= l.length + 1
        }
        val tailLines = ArrayList<String>()
        for (l in clipped.reversed()) {
            if (tailBudget - l.length < 0) break
            tailLines.add(l)
            tailBudget -= l.length + 1
        }
        val elided = lines.size - headLines.size - tailLines.size
        return buildString {
            headLines.forEach { appendLine(it) }
            appendLine("…（中间省略 $elided 行，全文见 handle）…")
            tailLines.reversed().forEach { appendLine(it) }
        }.trimEnd()
    }

    /**
     * handle 通知：告诉模型全文在哪、怎么按行号范围取回。
     * poolRelPath 是相对 app filesDir 的路径（workspace shell 可经 /tool_outputs 或绝对路径读）。
     */
    fun handleNotice(handle: String, poolRelPath: String, totalLines: Int, totalChars: Int, kind: Kind): String =
        buildString {
            appendLine("[CCR 原文池] 全文已按内容寻址落盘（type=$kind, $totalLines 行 / $totalChars 字符）")
            appendLine("handle: $handle")
            appendLine("全文: $poolRelPath")
            appendLine("取回片段（workspace shell）: sed -n 'N,Mp' $poolRelPath   # 行号范围 N-M")
            appendLine("取回头部/尾部: head -n 50 $poolRelPath / tail -n 50 $poolRelPath")
        }
}
