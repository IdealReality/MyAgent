package me.rerere.myagent.data.memory

import me.rerere.myagent.data.model.Assistant

/**
 * 记忆 scope 解析（v2：私有 + 共用同时成立）。
 *
 * 每个助手都有自己的记忆目录 `a-<uuid>`；`__global__` 是所有助手共享的公共记忆。
 * 旧字段 [Assistant.useGlobalMemory] 保留为"只用共用记忆"的兼容开关：
 *
 * | useGlobalMemory | useSharedMemory | 写入 | 读取 |
 * |---|---|---|---|
 * | false | true（默认） | 私有 | 私有 + 共用 |
 * | false | false | 私有 | 私有 |
 * | true  | 任意 | 共用 | 共用 |
 *
 * 写入口径只有一处：自动积累（L2 切段 / autolearn）永远写 [MemoryScopes.write]，
 * 因此共用记忆不会被单个助手的日常对话污染；只有显式 memory_write(scope=shared)
 * 或记忆页编辑会写共用层。
 */
object MemoryScopeResolver {

    /** 一个助手当前应当读写的 scope 集合；[read] 顺序即注入/合并顺序。 */
    data class MemoryScopes(
        val write: String,
        val read: List<String>,
    ) {
        val writesShared: Boolean get() = write == AssistantMemoryStore.GLOBAL_SCOPE_ID
        val readsShared: Boolean get() = read.contains(AssistantMemoryStore.GLOBAL_SCOPE_ID)
    }

    fun privateScopeIdOf(assistant: Assistant): String = "a-${assistant.id}"

    fun scopesOf(assistant: Assistant): MemoryScopes {
        val shared = AssistantMemoryStore.GLOBAL_SCOPE_ID
        if (assistant.useGlobalMemory) return MemoryScopes(write = shared, read = listOf(shared))
        val own = privateScopeIdOf(assistant)
        return if (assistant.useSharedMemory) {
            MemoryScopes(write = own, read = listOf(own, shared))
        } else {
            MemoryScopes(write = own, read = listOf(own))
        }
    }

    /** 写入 scope（兼容旧调用方）。 */
    fun scopeIdOf(assistant: Assistant): String = scopesOf(assistant).write

    fun scopeLabel(scopeId: String): String =
        if (scopeId == AssistantMemoryStore.GLOBAL_SCOPE_ID) "shared" else "self"
}
