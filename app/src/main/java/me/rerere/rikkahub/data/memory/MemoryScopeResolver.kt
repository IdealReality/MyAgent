package me.rerere.rikkahub.data.memory

import me.rerere.rikkahub.data.model.Assistant

/**
 * 记忆 scope 解析：沿用旧记忆的 useGlobalMemory 语义。
 * 助手自己的记忆目录用 `a-<uuid>` 前缀（避免与全局名冲突，也避开目录名非法字符）。
 */
object MemoryScopeResolver {
    fun scopeIdOf(assistant: Assistant): String =
        if (assistant.useGlobalMemory) AssistantMemoryStore.GLOBAL_SCOPE_ID
        else "a-${assistant.id}"
}
