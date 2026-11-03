package me.rerere.rikkahub.data.memory

import java.io.File
import kotlin.math.ln
import kotlin.math.max

/**
 * 记忆引擎 v1 · 检索引擎（P2）。平移 omp memory-engine/engine.mjs：
 *
 *  - 混合分词：latin 词（小写）+ CJK 2-gram（单 CJK 字保留）
 *  - BM25（k1=1.2, b=0.75）+ 语料级停用词（df 比例削减）
 *  - 层权重（越浅越新越准）：L1 1.35 / L2 1.25 / L3 1.2 / L4 1.0
 *  - 逐字锚点门控：latin ≥5 字符逐字命中 或 ≥5 连续 CJK 逐字命中，否则弃权（防碎词噪声）
 *  - markdown 结构感知分块（1-3 级标题切块，代码块不切，超长行滑窗，行号精确）
 *
 * 纯 Kotlin 无 Android 依赖，可 JVM 单测。向量融合（DESIGN：可用则接）设备端暂无
 * embedding 服务端点，v1 静默降级 BM25-only（omp 的降级纪律）。
 */
object MemoryTokenizer {
    private val LATIN = Regex("[A-Za-z0-9_][A-Za-z0-9_\\-/.]*")
    private val CJK_RUN = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\u3040-\\u30FF\\uAC00-\\uD7AF]+")

    fun tokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val tokens = ArrayList<String>()
        for (m in LATIN.findAll(text)) tokens.add(m.value.lowercase())
        for (run in CJK_RUN.findAll(text)) {
            val chars = run.value.toCharArray()
            if (chars.size == 1) {
                tokens.add(run.value)
                continue
            }
            for (i in 0 until chars.size - 1) tokens.add("${chars[i]}${chars[i + 1]}")
        }
        return tokens
    }
}

/** markdown 结构感知分块（返回 title 链 / 文本 / 起始行号，1-based）。 */
data class MemoryChunk(
    val docId: String,
    val layer: String,
    val title: String,
    val text: String,
    val startLine: Int,
)

object MemoryChunker {
    private val HEADING = Regex("^(#{1,3})\\s+(.*)$")
    private val FENCE = Regex("^\\s*```")

    /** 对齐 omp chunkMarkdown(maxChars=1200)：标题切块 + 超长行滑窗，行号精确。 */
    fun chunkMarkdown(docId: String, layer: String, text: String, maxChars: Int = 1200): List<MemoryChunk> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split("\n")
        data class Blk(val title: List<String>, val headingLine: Int, val lines: List<Pair<Int, String>>)

        val blocks = ArrayList<Blk>()
        var heading = ArrayList<String>()
        var headingLine = 0
        var buf = ArrayList<Pair<Int, String>>()
        var inFence = false

        fun flush() {
            if (buf.isEmpty() && heading.isEmpty()) return
            val body = buf.joinToString("\n") { it.second }.trim()
            if (body.isNotEmpty() || heading.isNotEmpty()) {
                blocks.add(Blk(heading.toList(), headingLine, buf.toList()))
            }
            buf = ArrayList()
        }

        for ((i, line) in lines.withIndex()) {
            if (FENCE.containsMatchIn(line)) {
                inFence = !inFence
                buf.add(i + 1 to line)
                continue
            }
            val h = if (inFence) null else HEADING.find(line)
            if (h != null) {
                flush()
                val level = h.groupValues[1].length
                while (heading.size >= level) heading.removeAt(heading.size - 1)
                heading.add(h.groupValues[2].trim())
                headingLine = i + 1
            } else {
                buf.add(i + 1 to line)
            }
        }
        flush()

        val out = ArrayList<MemoryChunk>()
        for (b in blocks) {
            val title = b.title.joinToString(" > ")
            val body = b.lines.joinToString("\n") { it.second }.trim()
            if (body.isEmpty()) continue
            if (body.length <= maxChars) {
                out.add(MemoryChunk(docId, layer, title, body, if (b.headingLine > 0) b.headingLine else b.lines.first().first))
                continue
            }
            var cur = ArrayList<Pair<Int, String>>()
            var size = 0
            fun push() {
                if (cur.isEmpty()) return
                out.add(
                    MemoryChunk(
                        docId, layer, title,
                        cur.joinToString("\n") { it.second }.trim(),
                        cur.first().first,
                    )
                )
                cur = ArrayList()
                size = 0
            }
            for (item in b.lines) {
                if (size + item.second.length > maxChars && cur.isNotEmpty()) push()
                cur.add(item)
                size += item.second.length + 1
            }
            push()
        }
        return out.filter { it.text.isNotEmpty() }
    }
}

/** 索引文档（一个 chunk 一条）。 */
data class IndexDoc(
    val id: String,
    val layer: String,
    val title: String,
    val text: String,
    val path: String,
)

/** 检索命中。text = 命中块完整正文（P6：对齐 omp，工具层再按预算裁剪展示）。 */
data class MemoryHit(
    val docId: String,
    val layer: String,
    val title: String,
    val path: String,
    val startLine: Int,
    val snippet: String,
    val score: Double,
    val text: String = "",
)

/** BM25 索引（进程内，构建后只读）。 */
class MemoryIndex(docs: List<IndexDoc>) {
    companion object {
        private const val K1 = 1.2
        private const val B = 0.75
        private const val STOPWORD_DF_RATIO = 0.6
        val LAYER_BOOST = mapOf("L1" to 1.35, "L2" to 1.25, "L3" to 1.2, "L4" to 1.0)
        private const val ANCHOR_LATIN_MIN = 5
        private const val ANCHOR_CJK_MIN = 5
    }

    private data class Entry(
        val doc: IndexDoc,
        val startLine: Int,
        val chunkText: String,
        val tf: Map<String, Int>,
        val len: Int,
    )

    private val entries: List<Entry>
    private val postings: Map<String, List<Pair<Int, Int>>>
    private val avgLen: Double
    private val stop: Set<String>
    private val latinVocab: Set<String>
    private val corpusText: String

    init {
        val built = docs.map { d ->
            val chunks = MemoryChunker.chunkMarkdown(d.id, d.layer, d.text)
            chunks.map { c ->
                val tokens = MemoryTokenizer.tokenize("${c.title}\n${c.text}")
                val tf = HashMap<String, Int>()
                for (t in tokens) tf[t] = (tf[t] ?: 0) + 1
                Entry(d, c.startLine, c.text, tf, tokens.size)
            }
        }.flatten()
        entries = built
        val n = entries.size
        val df = HashMap<String, Int>()
        for (e in entries) for (t in e.tf.keys) df[t] = (df[t] ?: 0) + 1
        stop = df.filter { it.value.toDouble() / n >= STOPWORD_DF_RATIO && it.value > 2 }.keys
        avgLen = if (n == 0) 1.0 else entries.sumOf { it.len }.toDouble() / n
        val p = HashMap<String, MutableList<Pair<Int, Int>>>()
        for ((i, e) in entries.withIndex()) {
            for ((t, f) in e.tf) {
                if (t in stop) continue
                p.getOrPut(t) { ArrayList() }.add(i to f)
            }
        }
        postings = p
        latinVocab = entries.flatMap { e -> e.tf.keys }
            .filter { it.length >= ANCHOR_LATIN_MIN && it[0].isLetterOrDigit() && it[0].code < 128 }
            .toSet()
        corpusText = entries.joinToString("\n") { "${it.doc.title}\n${it.doc.text}" }
    }

    val docCount: Int get() = entries.size

    /** 逐字锚点：latin ≥5 逐字命中，或 ≥5 连续 CJK 在语料里逐字出现。 */
    private fun anchors(query: String): List<String> {
        val out = ArrayList<String>()
        for (m in Regex("[A-Za-z0-9_][A-Za-z0-9_\\-/.]*").findAll(query)) {
            val w = m.value.lowercase()
            if (w.length >= ANCHOR_LATIN_MIN && w in latinVocab) out.add(w)
        }
        for (run in Regex("[\\u3400-\\u9FFF\\uF900-\\uFAFF]+").findAll(query)) {
            val cs = run.value
            for (i in 0..max(0, cs.length - ANCHOR_CJK_MIN)) {
                if (i + ANCHOR_CJK_MIN <= cs.length) {
                    val s = cs.substring(i, i + ANCHOR_CJK_MIN)
                    if (corpusText.contains(s)) {
                        out.add(s)
                        break
                    }
                }
            }
        }
        return out
    }

    /**
     * BM25 检索。
     * @param layer 层过滤（null = 全层）
     * @param minAnchors 锚点下限；0 = 关闭门控（调试用）
     */
    fun search(query: String, layer: String? = null, limit: Int = 8, minAnchors: Int = 1): List<MemoryHit> {
        if (query.isBlank() || entries.isEmpty()) return emptyList()
        val qTokens = MemoryTokenizer.tokenize(query).distinct().filter { it !in stop }
        if (qTokens.isEmpty()) return emptyList()
        if (minAnchors > 0 && anchors(query).size < minAnchors) return emptyList()

        val n = entries.size
        val scores = DoubleArray(n)
        val hits = HashSet<Int>()
        for (t in qTokens) {
            val list = postings[t] ?: continue
            val idf = ln(1 + (n - list.size + 0.5) / (list.size + 0.5))
            for ((idx, f) in list) {
                val e = entries[idx]
                val norm = (f * (K1 + 1)) / (f + K1 * (1 - B + (B * e.len) / avgLen))
                scores[idx] += idf * norm
                hits.add(idx)
            }
        }
        val layerBoosted = layer != null
        return hits.map { idx ->
            val e = entries[idx]
            val boost = if (layerBoosted) 1.0 else (LAYER_BOOST[e.doc.layer] ?: 1.0)
            MemoryHit(
                docId = e.doc.id,
                layer = e.doc.layer,
                title = e.doc.title.ifBlank { e.doc.path },
                path = e.doc.path,
                startLine = e.startLine,
                snippet = e.chunkText.take(200),
                text = e.chunkText,
                score = scores[idx] * boost,
            )
        }
            .filter { layer == null || it.layer == layer }
            .sortedByDescending { it.score }
            .take(limit)
    }
}

/**
 * 全量目录构建（L1-L4 → IndexDoc 列表）。
 */
object MemoryCatalog {

    fun buildDocs(store: AssistantMemoryStore, scopeId: String): List<IndexDoc> {
        val docs = ArrayList<IndexDoc>()

        // L1：一份（结构标题分块）
        val l1 = store.readL1(scopeId)
        if (l1.isNotBlank()) {
            docs.add(IndexDoc("L1:L1_summary.md", "L1", "L1 summary", l1, "L1_summary.md"))
        }

        // L2：每段一个 doc（firstQuery+lastQuery+replyExcerpt+gist 都进正文，检索友好）
        for (e in store.readAllEpisodes(scopeId)) {
            val body = buildString {
                if (e.title.isNotBlank()) appendLine("# ${e.title}")
                appendLine("Q: ${e.firstQuery}")
                if (e.lastQuery.isNotBlank() && e.lastQuery != e.firstQuery) appendLine("Q: ${e.lastQuery}")
                if (e.gist.isNotBlank()) appendLine("Gist: ${e.gist}")
                if (e.replyExcerpt.isNotBlank()) appendLine("A: ${e.replyExcerpt}")
            }.trim()
            if (body.isNotEmpty()) {
                docs.add(IndexDoc("L2:${e.id}", "L2", e.title.ifBlank { e.firstQuery.take(60) }, body, "episodes/${e.month}.jsonl"))
            }
        }

        // L3：docs/ 全部 .md（含 INDEX.md）
        val docsDir = store.docsDir(scopeId)
        if (docsDir.exists()) {
            docsDir.listFiles { f -> f.isFile && f.name.endsWith(".md", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?.forEach { f ->
                    val text = runCatching { f.readText() }.getOrDefault("")
                    if (text.isNotBlank()) {
                        docs.add(IndexDoc("L3:${f.name}", "L3", f.name.removeSuffix(".md"), text, "docs/${f.name}"))
                    }
                }
        }

        // L4：会话目录整体一份
        val idx = store.readSessionsIndex(scopeId)
        if (idx.sessions.isNotEmpty()) {
            val body = buildString {
                appendLine("# Session catalog")
                idx.sessions.forEach { s ->
                    appendLine("- ${s.title.ifBlank { s.firstPrompt.take(50) }} | ${s.turns} turns | ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(s.endTs))} | ${s.conversationId.take(8)}")
                }
            }
            docs.add(IndexDoc("L4:sessions_index.json", "L4", "Session catalog", body, "sessions_index.json"))
        }

        return docs
    }
}

/**
 * P6 · 索引新鲜度令牌：scope 目录（除 snapshots/）全部文件的
 * `相对路径:mtime:size` 串接哈希。文件未变 → 令牌不变 → 复用进程内索引。
 */
object MemoryFreshness {
    fun token(root: File): String {
        if (!root.exists()) return "empty"
        val parts = ArrayList<String>()
        root.walkTopDown()
            .filter { it.isFile }
            // 文件级过滤：snapshots/ 快照树与 *.tmp 不进令牌（invariantSeparatorsPath
            // 统一 '/'，Windows 下 File.path 是 '\'）；快照内容变化不致作废索引缓存。
            .filter { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                !rel.startsWith("snapshots/") && !f.name.endsWith(".tmp")
            }
            .take(2000)
            .forEach { f ->
                parts.add(f.relativeTo(root).invariantSeparatorsPath + ":" + f.lastModified() + ":" + f.length())
            }
        val joined = parts.sorted().joinToString("|")
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(joined.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
