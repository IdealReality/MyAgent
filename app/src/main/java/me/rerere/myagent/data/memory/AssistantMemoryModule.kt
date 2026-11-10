package me.rerere.myagent.data.memory

import android.content.Context
import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import me.rerere.myagent.data.model.Assistant
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 助手记忆引擎 v1 · 模块入口（P1+P2，v2 多作用域）。
 *
 * Koin 单例，持有全部 scope 的 [AssistantMemoryStore]（同 scope 共用同一实例）。
 * ChatService 用 [writer] 挂生命周期（L2 切段）、用 [injectL1Prompt] 组装注入段、
 * 用 [toolsFor] 注册检索三工具；ChatToolFactory（P2）同样走 [toolsFor]；
 * AutoLearnWorker（P3）用 [store] 读未整理段。
 *
 * scope 解析见 [MemoryScopeResolver]：每个助手有私有记忆 `a-<uuid>`，并可同时读取
 * `__global__` 共用记忆；自动积累只写私有层，共用层靠显式 memory_write(scope=shared)
 * 或记忆页编辑维护。
 */
class AssistantMemoryEngine(
    private val context: Context,
) {
    companion object {
        private const val TAG = "AssistantMemoryEngine"

        /** 记忆根：files/assistants/memory/。 */
        fun memoryRoot(context: Context): File = File(context.filesDir, "assistants/memory")
    }

    /** P2 三工具用的 Json（与 ChatToolFactory 注入的等价配置）。 */
    private val json: Json = Json { ignoreUnknownKeys = true }

    private val stores = ConcurrentHashMap<String, AssistantMemoryStore>()

    // P6 · 进程内索引缓存：scope 组合 -> (freshnessToken, index)。文件未变时直接复用，
    // 避免 memory_recall 每次全量重建；快照目录不计入令牌（快照写入不应作废索引）。
    private val indexCache = ConcurrentHashMap<String, Pair<String, MemoryIndex>>()

    /** scope -> store。scope 目录内文件读写全走 store；跨 scope 无共享状态。 */
    fun store(scopeId: String): AssistantMemoryStore =
        stores.computeIfAbsent(scopeId) { AssistantMemoryStore(memoryRoot(context)) }

    /** 助手 -> 读写 scope 集合（私有 + 可选共用）。 */
    fun scopesOf(assistant: Assistant): MemoryScopeResolver.MemoryScopes =
        MemoryScopeResolver.scopesOf(assistant)

    /** 助手 -> scopeId（写入作用域，兼容旧调用方）。 */
    fun scopeIdOf(assistant: Assistant): String =
        MemoryScopeResolver.scopeIdOf(assistant)

    /** 助手 -> EpisodeWriter（挂在 ChatService 生命周期上，永远写私有/写入作用域）。 */
    fun writer(assistant: Assistant): EpisodeWriter = EpisodeWriter(store(scopeIdOf(assistant)))

    /**
     * P6 · 多作用域检索索引（带 freshnessToken 缓存）：所有 scope 的文档合并成一个
     * BM25 语料，命中带 `scope` 标签。文件未变时复用同一实例。
     * 索引构建失败的降级纪律由调用方（MemoryToolsV1）负责。
     */
    fun index(scopeIds: List<String>): MemoryIndex {
        val ids = if (scopeIds.isEmpty()) listOf(AssistantMemoryStore.GLOBAL_SCOPE_ID) else scopeIds
        val cacheKey = ids.joinToString("|")
        val token = ids.joinToString("|") { MemoryFreshness.token(memoryRoot(context).resolve(it)) }
        indexCache[cacheKey]?.let { (t, idx) ->
            if (t == token) return idx
        }
        val docs = ids.flatMap { sid -> MemoryCatalog.buildDocs(store(sid), sid) }
        val built = MemoryIndex(docs)
        indexCache[cacheKey] = token to built
        return built
    }

    fun index(scopeId: String): MemoryIndex = index(listOf(scopeId))

    /**
     * 记忆引擎 v1（P2）：enableMemory 时返回 memory_recall / memory_guide / memory_write
     * 三工具；关闭时返回空。ChatService 两处工具面构建（首次生成 + regenerate）共用。
     */
    fun toolsFor(assistant: Assistant): List<Tool> =
        if (assistant.enableMemory) {
            MemoryToolsV1.build(json = json, engine = this, assistant = assistant)
        } else {
            emptyList()
        }

    /**
     * 组装 L1 注入段（零注入原则：只有 L1 + 固定提示行；正文一律工具按需取）。
     * 单作用域：一段 L1；多作用域：私有 L1 与共用 L1 各一段，标题明确来源。
     * L1 不存在时用默认骨架占位（autolearn 首次整理前 GUI 与模型都有稳定结构），
     * 仅在记忆开关开启时由 ChatService 调用。
     */
    fun injectL1Prompt(assistant: Assistant): String {
        return runCatching {
            val scopes = scopesOf(assistant)
            val hint = MemoryPrompts.toolHint(hasL1 = true)
            if (scopes.read.size == 1) {
                val sid = scopes.read.first()
                val l1 = store(sid).readL1(sid).ifBlank { MemoryPrompts.defaultL1Skeleton() }
                MemoryPrompts.buildMemoryV1Prompt(l1, hint)
            } else {
                val selfId = scopes.read.firstOrNull {
                    it != AssistantMemoryStore.GLOBAL_SCOPE_ID
                } ?: scopes.read.first()
                val sharedId = AssistantMemoryStore.GLOBAL_SCOPE_ID
                val selfL1 = store(selfId).readL1(selfId).ifBlank { MemoryPrompts.defaultL1Skeleton() }
                val sharedL1 = store(sharedId).readL1(sharedId)
                MemoryPrompts.buildScopedMemoryPrompt(
                    privateL1 = selfL1,
                    sharedL1 = sharedL1,
                    hint = hint,
                )
            }
        }.onFailure { MemLog.w(TAG, "injectL1Prompt failed: ${it.message}") }
            .getOrDefault("")
    }

    /** 供记忆页/guide 用：单个 scope 的四层统计。 */
    fun statsOf(scopeId: String): AssistantMemoryStore.Stats = store(scopeId).stats(scopeId)
}
