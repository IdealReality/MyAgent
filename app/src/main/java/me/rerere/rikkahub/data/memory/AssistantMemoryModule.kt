package me.rerere.rikkahub.data.memory

import android.content.Context
import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.model.Assistant
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 助手记忆引擎 v1 · 模块入口（P1+P2）。
 *
 * Koin 单例，持有全部 scope 的 [AssistantMemoryStore]（同 scope 共用同一实例）。
 * ChatService 用 [writer] 挂生命周期（L2 切段）、用 [injectL1Prompt] 组装注入段、
 * 用 [toolsFor] 注册检索三工具；ChatToolFactory（P2）同样走 [toolsFor]；
 * AutoLearnWorker（P3）用 [store] 读未整理段。
 * scope 解析：助手自己的记忆目录 `a-<uuid>`；`__global__` = useGlobalMemory。
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

    /** scope -> store。scope 目录内文件读写全走 store；跨 scope 无共享状态。 */
    fun store(scopeId: String): AssistantMemoryStore =
        stores.computeIfAbsent(scopeId) { AssistantMemoryStore(memoryRoot(context)) }

    /** 助手 -> scopeId（沿用 useGlobalMemory 语义）。 */
    fun scopeIdOf(assistant: Assistant): String = MemoryScopeResolver.scopeIdOf(assistant)

    /** 助手 -> EpisodeWriter（挂在 ChatService 生命周期上）。 */
    fun writer(assistant: Assistant): EpisodeWriter = EpisodeWriter(store(scopeIdOf(assistant)))

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
     * L1 不存在时用默认骨架占位（autolearn 首次整理前 GUI 与模型都有稳定结构），
     * 仅在记忆开关开启时由 ChatService 调用。
     */
    fun injectL1Prompt(assistant: Assistant): String {
        return runCatching {
            val scopeId = scopeIdOf(assistant)
            val store = store(scopeId)
            val l1 = store.readL1(scopeId).ifBlank { MemoryPrompts.defaultL1Skeleton() }
            MemoryPrompts.buildMemoryV1Prompt(l1, MemoryPrompts.toolHint(hasL1 = true))
        }.onFailure { MemLog.w(TAG, "injectL1Prompt failed: ${it.message}") }
            .getOrDefault("")
    }
}
