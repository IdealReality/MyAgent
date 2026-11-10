package me.rerere.myagent.data.ai

/**
 * Single place for assembling the system prompt that is sent to every provider.
 *
 * Callers provide pre-rendered sections so the ordering and formatting live in one spot
 * rather than being reimplemented in GenerationHandler and the provider adapters.
 *
 * Ordering is **stable-first**: the assistant prompt and tool prompts (byte-identical turn
 * to turn) come first, then the volatile sections (memory, recent chats, per-call
 * addendum) that change between turns. This lets prompt caching work: auto-caching
 * providers (OpenAI/DeepSeek/Grok/Gemini) reuse the stable byte-prefix, and OpenRouter's
 * explicit cache_control breakpoint is placed at the stable/volatile boundary (see
 * ChatCompletionsAPI). Volatile text in the prefix busts the cache every turn, which is
 * what happened before when memory was enabled.
 */
class SystemPromptBuilder {

    /**
     * Returns the system prompt split into `(stable, volatile)`.
     * - stable: assistant prompt + tool cost guidance + tool prompts.
     * - volatile: memory + recent chats + per-call addendum.
     * Either may be blank.
     */
    fun buildSections(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
        policyPrompt: String = "",
        // 助手配置自管理说明（启用 AssistantConfig 组时由 GenerationLoop 传入）。
        // 归入 stable 段：同一助手逐字节稳定，不破坏 prompt 缓存。
        assistantConfigHint: String = "",
        // P2-40 plugin kernel: stable prompt contributions from installed plugins. Stable
        // (not volatile) on purpose — a plugin only changes when its files change, which
        // also rebuilds this string; per turn it is byte-identical, so prompt caching holds.
        pluginPrompt: String = "",
        // 内联互动预览能力说明（用户开启「互动网页预览」时由 GenerationLoop 传入）。
        // 归入 stable 段：它只随该设置变化，逐轮逐字节相同。
        renderCapabilityPrompt: String = "",
        // 插件 before_turn 钩子的输出。归入 volatile 段：钩子可以是动态的（当前时间、
        // 状态摘要），放进 stable 前缀会把 prompt 缓存每轮打掉。
        pluginHookPrompt: String = "",
    ): Pair<String, String> {
        val stable = buildString {
            if (assistantPrompt.isNotBlank()) append(assistantPrompt)
            if (renderCapabilityPrompt.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(renderCapabilityPrompt)
            }
            if (toolPrompts.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("Tool cost guidance: prefer low-cost text tools before expensive visual or broad tools. Use read_window_tree/browser_get_text before screenshots when text is enough, and avoid repeating high-cost tools unless the state likely changed.")
                toolPrompts.forEachIndexed { index, toolPrompt ->
                    if (index > 0) appendLine()
                    append(toolPrompt)
                }
            }
            if (assistantConfigHint.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(assistantConfigHint)
            }
            if (pluginPrompt.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(pluginPrompt)
            }
        }.trim()

        val volatile = buildString {
            if (memoryPrompt.isNotBlank()) append(memoryPrompt)
            if (recentChatsPrompt.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(recentChatsPrompt)
            }
            if (!systemAddendum.isNullOrBlank()) {
                if (isNotEmpty()) appendLine()
                append(systemAddendum)
            }
            if (policyPrompt.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(policyPrompt)
            }
            if (pluginHookPrompt.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(pluginHookPrompt)
            }
        }.trim()

        return stable to volatile
    }

    /** Combined single-string prompt (stable then volatile), for callers/providers that do
     *  not split into cache blocks. */
    fun build(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
        policyPrompt: String = "",
    ): String {
        val (stable, volatile) = buildSections(
            assistantPrompt, memoryPrompt, recentChatsPrompt, toolPrompts, systemAddendum, policyPrompt
        )
        return listOf(stable, volatile).filter { it.isNotBlank() }.joinToString("\n")
    }
}
