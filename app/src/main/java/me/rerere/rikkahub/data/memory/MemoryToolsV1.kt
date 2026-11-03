package me.rerere.rikkahub.data.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 记忆引擎 v1 · 三工具（P2）。平移 omp memory-engine.js 的工具语义：
 *
 *  - memory_recall(query, layer?, k?)  → BM25 命中块（来源层/路径/行号/摘要）；零命中时给"弃权"提示
 *  - memory_guide()                    → 自检段+记忆地图+会话目录（导航，不耗正文预算）
 *  - memory_write(file, content, mode) → 写 L3 文档（**需用户批准**，沿用 ColdMemoryTools 批准模式）
 *
 * 索引进程内重建（每次工具调用现算，规模上限 ~几千块毫秒级）；
 * 索引不可用时工具返回提示字符串，不抛错（omp 降级纪律）。
 */
object MemoryToolsV1 {

    private const val DEFAULT_K = 8
    private const val MAX_K = 20

    fun build(
        json: Json,
        engine: AssistantMemoryEngine,
        assistant: me.rerere.rikkahub.data.model.Assistant,
    ): List<Tool> {
        val scopeId = engine.scopeIdOf(assistant)
        val store = engine.store(scopeId)

        // P6 · 命中展示预算：单块上限 + 总预算（对齐 omp 的块级返回，而不是 200 字摘要）。
        val PER_HIT_CHARS = 900
        val TOTAL_CHARS = 4200

        fun renderHits(hits: List<MemoryHit>): String = buildString {
            if (hits.isEmpty()) {
                append("No memory hits. Either the memory has no related content, or the query has no verbatim anchor into it.")
                append(" Use memory_guide() to browse what exists, or rephrase with distinctive words.")
            } else {
                appendLine("Found ${hits.size} memory hits:")
                var used = 0
                hits.forEachIndexed { i, h ->
                    if (used >= TOTAL_CHARS) {
                        appendLine("[$i] layer=${h.layer} path=${h.path}:L${h.startLine} — omitted (output budget reached; re-query with a narrower layer or smaller k)")
                        return@forEachIndexed
                    }
                    val remaining = TOTAL_CHARS - used
                    val body = h.text.ifBlank { h.snippet }
                    val clipped = body.take(minOf(PER_HIT_CHARS, remaining))
                    used += clipped.length
                    appendLine("[$i] layer=${h.layer} path=${h.path}:L${h.startLine}-${h.startLine + body.lines().size - 1} score=%.3f".format(h.score))
                    appendLine("    title: ${h.title.take(80)}")
                    appendLine(clipped)
                    if (body.length > clipped.length) appendLine("    …(clipped; full text: read ${h.path} near line ${h.startLine})")
                }
            }
        }

        return listOf(
            Tool(
                name = "memory_recall",
                description = """
                Search this assistant's persistent memory (L1 summary / L2 episode archive / L3 knowledge docs / L4 session catalog)
                with BM25. Returns ranked hits with layer, file path, line number and a snippet.

                Use it when the user refers to earlier work, a past decision, a preference, or anything you
                would otherwise have to guess. Chinese and English both fine; use distinctive words
                (identifiers, project names, ≥5-char CJK phrases) for best precision.
                """.trimIndent(),
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "string")
                                put("description", "Search keywords (Chinese or English)")
                            })
                            put("layer", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add("L1"); add("L2"); add("L3"); add("L4")
                                })
                                put("description", "Optional layer filter (default: all layers)")
                            })
                            put("k", buildJsonObject {
                                put("type", "integer")
                                put("description", "Max hits to return (default 8, max 20)")
                            })
                        },
                        required = listOf("query")
                    )
                },
                needsApproval = { false },
                execute = {
                    val params = it.jsonObject
                    val query = params["query"]?.jsonPrimitive?.contentOrNull
                        ?: return@Tool listOf(UIMessagePart.Text("memory_recall: query is required"))
                    val layer = params["layer"]?.jsonPrimitive?.contentOrNull
                    val k = (params["k"]?.jsonPrimitive?.intOrNull ?: DEFAULT_K).coerceIn(1, MAX_K)
                    val result = runCatching {
                        // P6：引擎级缓存索引（freshnessToken 命中时零重建）
                        engine.index(scopeId).search(query, layer = layer, limit = k)
                    }.getOrElse { e ->
                        return@Tool listOf(
                            UIMessagePart.Text("memory_recall: index unavailable (${e.javaClass.simpleName}); falling back to memory_guide().")
                        )
                    }
                    listOf(UIMessagePart.Text(renderHits(result)))
                },
            ),
            Tool(
                name = "memory_guide",
                description = """
                Navigate this assistant's memory: stats, memory map (what exists per layer),
                and the recent-session catalog (L4). Read-only and cheap.

                Call it first when you don't know what the memory contains or where to look.
                """.trimIndent(),
                parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
                needsApproval = { false },
                execute = {
                    val text = runCatching {
                        val stats = store.stats(scopeId)
                        buildString {
                            appendLine("=== Memory guide ===")
                            appendLine("episodes(L2): ${stats.episodeCount} (${stats.unlearnedCount} unlearned) | docs(L3): ${stats.docsCount} | sessions(L4): ${stats.sessionCount} | L1 chars: ${stats.l1Chars}")
                            appendLine()
                            appendLine("Layers: L1_summary.md (auto-injected) | episodes/YYYY-MM.jsonl (verbatim archive) | docs/*.md (knowledge) | sessions_index.json (session catalog)")
                            appendLine()
                            val idx = store.readSessionsIndex(scopeId)
                            if (idx.sessions.isNotEmpty()) {
                                appendLine("Recent sessions:")
                                idx.sessions.take(10).forEach { s ->
                                    appendLine("- ${s.title.ifBlank { s.firstPrompt.take(50) }} | ${s.turns} turns | ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(s.endTs))}")
                                }
                            } else {
                                appendLine("No sessions recorded yet.")
                            }
                        }
                    }.getOrElse { "memory_guide: unavailable (${it.javaClass.simpleName})" }
                    listOf(UIMessagePart.Text(text))
                },
            ),
            Tool(
                name = "memory_write",
                description = """
                Persist durable knowledge into this assistant's L3 knowledge base (docs/*.md).

                Write things worth keeping across conversations: user preferences, project facts,
                conventions, how-tos. Use mode=append for journals/logs, mode=overwrite to rewrite a doc,
                mode=edit to replace one text occurrence. See memory_guide for what docs exist.
                """.trimIndent(),
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("file", buildJsonObject {
                                put("type", "string")
                                put("description", "Markdown file name (single segment, .md suffix, e.g. user-preferences.md)")
                            })
                            put("content", buildJsonObject {
                                put("type", "string")
                                put("description", "Content to write")
                            })
                            put("mode", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray { add("append"); add("overwrite"); add("edit") })
                                put("description", "append (default) / overwrite / edit (replace oldText with content)")
                            })
                            put("oldText", buildJsonObject {
                                put("type", "string")
                                put("description", "Required for mode=edit: the exact text to replace")
                            })
                        },
                        required = listOf("file", "content")
                    )
                },
                // 用户批准（DESIGN 1.2：沿用 ColdMemoryTools 批准模式）
                needsApproval = { true },
                execute = {
                    val params = it.jsonObject
                    val file = params["file"]?.jsonPrimitive?.contentOrNull
                        ?: return@Tool listOf(UIMessagePart.Text("memory_write: file is required"))
                    val content = params["content"]?.jsonPrimitive?.contentOrNull
                        ?: return@Tool listOf(UIMessagePart.Text("memory_write: content is required"))
                    val mode = params["mode"]?.jsonPrimitive?.contentOrNull ?: "append"
                    val oldText = params["oldText"]?.jsonPrimitive?.contentOrNull

                    // 文件名校验（对齐 ColdMemoryRules 严格性：单段 + .md + 无 ..）
                    val name = file.trim()
                    val invalid = name.isEmpty() || name == "." || name == ".." ||
                        name.contains('/') || name.contains('\\') || name.contains("..") ||
                        !name.endsWith(".md", ignoreCase = true)
                    if (invalid) {
                        return@Tool listOf(
                            UIMessagePart.Text("memory_write: invalid file name '$name' (single segment, .md only, no path separators)")
                        )
                    }
                    val result = runCatching {
                        val target = java.io.File(store.docsDir(scopeId), name)
                        when (mode) {
                            "overwrite" -> {
                                target.parentFile?.mkdirs()
                                target.writeText(content, Charsets.UTF_8)
                            }
                            "edit" -> {
                                val old = oldText
                                    ?: error("oldText is required for mode=edit")
                                val existing = if (target.exists()) target.readText() else ""
                                if (!existing.contains(old)) error("oldText not found in $name")
                                target.writeText(existing.replaceFirst(old, content), Charsets.UTF_8)
                            }
                            else -> {
                                target.parentFile?.mkdirs()
                                target.appendText(
                                    buildString {
                                        if (target.exists() && target.length() > 0) appendLine()
                                        append(content)
                                    },
                                    Charsets.UTF_8,
                                )
                            }
                        }
                        "memory_write: ok file=docs/$name mode=$mode bytes=${target.length()}"
                    }.getOrElse { "memory_write: failed (${it.message})" }
                    listOf(UIMessagePart.Text(result))
                },
            ),
        )
    }
}
