package me.rerere.myagent.data.memory

/**
 * 记忆引擎 v1 · 提示注入（P1-P2）。
 *
 * 零注入原则（DESIGN 1.1）：除 L1 摘要 + 一条固定 MEMORY_TOOL_HINT，正文一律
 * 工具按需取（memory_recall / memory_guide / memory_write）。旧 memory_tool
 * 热记录注入（buildMemoryPrompt）随 P1 停用。
 */
object MemoryPrompts {

    /**
     * 固定提示行（平移 omp memory-engine.js 的 MEMORY_TOOL_HINT 语义）。
     * 稳定字节序列：同一助手逐请求不变，不破坏 prompt 缓存。
     */
    fun toolHint(hasL1: Boolean): String = buildString {
        append("[Memory] Persistent memory system is active. ")
        if (hasL1) {
            append("The summary above is your distilled memory (L1). ")
        } else {
            append("No distilled summary (L1) exists yet. ")
        }
        append(
            "Full episode archive (L2), knowledge docs (L3) and past-session catalog (L4) are NOT auto-injected. " +
                "Use memory_recall(query) to search them, memory_guide() for a memory map, and memory_write(file, content) to persist durable knowledge as an L3 doc."
        )
    }

    /**
     * 组装注入段：L1 正文 + 提示行。两者都空返回空串（不注入任何记忆段）。
     */
    fun buildMemoryV1Prompt(l1: String, hint: String): String {
        val body = l1.trim()
        if (body.isEmpty() && hint.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("**Memory (L1 summary)**")
            if (body.isNotEmpty()) {
                appendLine(body)
            } else {
                appendLine("(empty)")
            }
            if (hint.isNotEmpty()) {
                appendLine()
                append(hint)
            }
        }
    }

    /**
     * 双作用域注入：私有记忆（本助手） + 共用记忆（所有助手共享）。
     * 共用段为空时不渲染该段（避免空标题占预算）；私有段永远渲染（有默认骨架）。
     */
    fun buildScopedMemoryPrompt(privateL1: String, sharedL1: String, hint: String): String {
        val own = privateL1.trim()
        val shared = sharedL1.trim()
        if (own.isEmpty() && shared.isEmpty() && hint.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("**Memory (L1 summary · this assistant)**")
            appendLine(own.ifEmpty { "(empty)" })
            if (shared.isNotEmpty()) {
                appendLine()
                appendLine("**Shared memory (L1 summary · all assistants)**")
                appendLine(shared)
            }
            if (hint.isNotEmpty()) {
                appendLine()
                appendLine("Both layers are searchable with memory_recall; write to the shared layer only with memory_write(scope=\"shared\").")
                append(hint)
            }
        }
    }

    /**
     * L1 默认骨架（autolearn 首次重写前的占位，给 GUI 与模型一个稳定结构）。
     */
    fun defaultL1Skeleton(): String = """
        # L1 常驻摘要

        > 由 autolearn 自动整理（用户偏好 / 项目事实 / 进行中事务 / 长期约定）。
        > 本文件是唯一自动注入的记忆正文；历史原话在 L2，按需 memory_recall 检索。

        ## 用户偏好

        （待整理）

        ## 项目事实

        （待整理）

        ## 进行中事务

        （待整理）

        ## 长期约定

        （待整理）
    """.trimIndent()
}
