package me.rerere.myagent.plugin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.util.LinkedHashMap

private const val TAG = "PluginRegistry"

data class LoadedPlugin(
    val manifest: PluginManifest,
    val dir: File,
    val state: PluginState,
    val prompt: String,
)

data class PluginLoadError(val id: String, val message: String)

data class PluginSnapshot(
    val plugins: List<LoadedPlugin> = emptyList(),
    val errors: List<PluginLoadError> = emptyList(),
    val signature: String = "",
    val loadedAt: Long = 0L,
)

data class PluginStatus(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val enabled: Boolean,
    val loadError: String?,
    val failures: Int,
    val lastError: String?,
    val toolNames: List<String>,
    val promptChars: Int,
    val declaredTools: List<String>,
    /** `ui.panel` relative path when the plugin ships an HTML panel, else null. */
    val panel: String? = null,
    /** `ui.title` (falls back to the plugin name in the UI). */
    val panelTitle: String = "",
    /** `ui.slots` 的简短摘要（`composer:chips` / `message:html`），用于插件页与 `plugin_list`。 */
    val slots: List<String> = emptyList(),
)

/**
 * 一个要渲染的聊天内槽位：来自哪个插件、第几个、声明了什么。
 *
 * [html] 是 `type=html` 时当场读到的文件内容（读不到就是 null，渲染点据此退化成一个
 * 错误条而不是空白）。刻意在查询时就读：槽位是界面，界面不该在渲染中途再回磁盘。
 */
data class PluginSlot(
    val pluginId: String,
    val pluginName: String,
    val index: Int,
    val spec: PluginSlotSpec,
    val html: String? = null,
)

/**
 * 槽位动作的返回值。
 *
 * 只认这五个键（外加 `text` 供 html 槽位自取数据）：界面能做的是「填字、发消息、提示、
 * 开面板」，不是「随便改应用」。其余键忽略。
 */
data class PluginSlotAction(
    val ok: Boolean,
    val insert: String? = null,
    val send: String? = null,
    val toast: String? = null,
    val panel: String? = null,
    val text: String? = null,
    val error: String? = null,
)

/**
 * P2-40 — plugin kernel, part 4: the registry (single source of truth for plugins).
 *
 * Responsibilities, in order of importance:
 *  - **Freshness.** [snapshot] compares a cheap on-disk token and reparses only when it
 *    changed. `ChatToolFactory` calls it once per turn, so a file the model just wrote is
 *    live on the *next* turn — no process restart, no APK rebuild.
 *  - **Isolation.** One bad manifest or one plugin that throws on load never removes the
 *    rest from the surface; it becomes a row in [PluginSnapshot.errors] and a status line.
 *  - **Health.** Repeated tool failures auto-disable a plugin (see [PluginStore.MAX_FAILURES]),
 *    and [reload] surfaces why, so a self-modifying agent cannot brick its own tool surface.
 */
class PluginRegistry(
    val store: PluginStore,
    private val runtime: PluginExecutor,
    private val json: Json,
    private val hostToolBridge: PluginHostToolBridge = PluginHostToolBridge(),
    private val seeder: (PluginStore) -> Unit = {},
    /** Maps a plugin-supplied base64 image to a URL the chat renderer can load. */
    private val imageResolver: (String) -> String? = { null },
) {
    private val mutex = Mutex()

    @Volatile
    private var snapshot: PluginSnapshot = PluginSnapshot()

    @Volatile
    private var promptBlock: String = ""

    /** Per-plugin prompt contributions, in load order: (plugin id, rendered block). */
    @Volatile
    private var promptParts: List<Pair<String, String>> = emptyList()

    @Volatile
    private var builtinTools: List<Tool> = emptyList()

    /**
     * 槽位网页的小缓存（LRU，(mtime,size) 作为命中判据）。槽位列表在消息数变化时重查，
     * 一次对话几十次重读同一个 HTML 没有意义。容量很小：一个插件一般只有一个卡片。
     */
    /**
     * 槽位列表按 (where, snapshot 签名) 记忆。
     *
     * 列表在「消息数变化」时被两边查询（输入框槽位 + 消息槽位），一次对话几十次；只要插件
     * 文件与状态没变，重建出来的就是同一份东西。命中时不碰磁盘，返回的也是同一个 List 实例，
     * Compose 可以直接跳过。
     */
    private val slotListCache = HashMap<String, Pair<String, List<PluginSlot>>>()

    private val slotHtmlCache =
        object : LinkedHashMap<String, CachedSlotHtml>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSlotHtml>) =
                size > MAX_SLOT_HTML_CACHE
        }

    init {
        hostToolBridge.invoker = { name, argsJson -> invokeHostTool(name, argsJson) }
    }

    /** Baseline tools a plugin may call through `ctx.tool` (set once per turn). */
    fun setHostTools(tools: List<Tool>) {
        builtinTools = tools
    }

    fun current(): PluginSnapshot = snapshot

    /** Last-loaded prompt contribution (stable for the whole turn). */
    fun prompt(): String = promptBlock

    /**
     * Prompt contributions minus the plugins named in [excludeIds].
     *
     * A plugin's instructions are global by construction (they are a property of the installed
     * kernel, not of an assistant), but some plugins only describe a *mode*: the `trpg` contract
     * is noise in an everyday chat. Callers that know the mode pass the ids to leave out;
     * the default keeps the historical single-string behaviour.
     */
    fun prompt(excludeIds: Set<String>): String {
        if (excludeIds.isEmpty()) return promptBlock
        val kept = promptParts.filterNot { it.first in excludeIds }.map { it.second }
        return buildPromptBlock(kept)
    }

    suspend fun snapshot(force: Boolean = false): PluginSnapshot = mutex.withLock {
        val signature = withContext(Dispatchers.IO) { store.signature() }
        if (!force && snapshot.loadedAt != 0L && signature == snapshot.signature) {
            return snapshot
        }
        reloadLocked()
    }

    suspend fun reload(): PluginSnapshot = mutex.withLock { reloadLocked() }

    private suspend fun reloadLocked(): PluginSnapshot = withContext(Dispatchers.IO) {
        store.ensure()
        runCatching { seeder(store) }.onFailure { Log.w(TAG, "plugin seeding failed", it) }

        val loaded = ArrayList<LoadedPlugin>()
        val errors = ArrayList<PluginLoadError>()
        val collected = ArrayList<Pair<String, String>>()

        store.listIds().forEach { id ->
            val source = store.readManifest(id)
            if (source == null) {
                errors += PluginLoadError(id, "manifest.json missing or not valid JSON")
                return@forEach
            }
            if (!source.validation.ok) {
                errors += PluginLoadError(id, source.validation.render())
                return@forEach
            }
            val state = source.state
            if (!state.enabled) {
                errors += PluginLoadError(id, "disabled" + (state.lastError?.let { ": $it" } ?: ""))
                return@forEach
            }
            if (state.failures >= PluginStore.MAX_FAILURES) {
                errors += PluginLoadError(id, "auto-disabled after ${state.failures} failures")
                return@forEach
            }

            val promptText = resolvePrompt(source).orEmpty()
            loaded += LoadedPlugin(source.manifest, source.dir, state, promptText)
            if (promptText.isNotBlank()) collected += source.manifest.id to promptText
        }

        val block = buildPromptBlock(collected.map { it.second })
        val next = PluginSnapshot(
            plugins = loaded,
            errors = errors,
            signature = store.signature(),
            loadedAt = System.currentTimeMillis(),
        )
        snapshot = next
        promptBlock = block
        promptParts = collected
        Log.i(TAG, "plugins loaded=${loaded.size} errors=${errors.size} promptChars=${block.length}")
        next
    }

    private fun resolvePrompt(source: PluginSource): String? {
        val spec = source.manifest.prompt ?: return null
        val body = when {
            !spec.file.isNullOrBlank() -> store.readFile(source.manifest.id, spec.file)
            !spec.text.isNullOrBlank() -> spec.text
            else -> null
        } ?: return null
        val header = "### Plugin: ${source.manifest.name.ifBlank { source.manifest.id }} (${source.manifest.id} ${source.manifest.version})"
        return "$header\n${body.trim()}"
    }

    private fun buildPromptBlock(parts: List<String>): String {
        if (parts.isEmpty()) return ""
        val joined = parts.joinToString("\n\n")
        val capped = if (joined.length > MAX_PROMPT_CHARS) {
            joined.take(MAX_PROMPT_CHARS) + "\n…(plugin prompt truncated at $MAX_PROMPT_CHARS chars)"
        } else {
            joined
        }
        return "## Installed plugin instructions\n\n$capped"
    }

    // ------------------------------------------------------------------ surface

    suspend fun tools(): List<Tool> {
        val snap = snapshot(force = false)
        // Names are globally unique across the surface: a plugin may not shadow a built-in
        // tool (the model would see one name and either definition could win), and two
        // plugins may not both claim the same name. First writer wins; the loser is logged
        // and surfaced through `plugin_list`'s declared list.
        val taken = HashSet<String>(builtinTools.map { it.name })
        taken += ADMIN_TOOL_NAMES
        val result = ArrayList<Tool>()
        snap.plugins.forEach { plugin ->
            plugin.manifest.tools.forEach { spec ->
                if (!taken.add(spec.name)) {
                    Log.w(
                        TAG,
                        "plugin '${plugin.manifest.id}': tool '${spec.name}' conflicts with an " +
                            "existing tool name; skipped on this surface",
                    )
                } else {
                    result += buildTool(plugin, spec)
                }
            }
        }
        return result
    }

    private fun buildTool(plugin: LoadedPlugin, spec: PluginToolSpec): Tool = Tool(
        name = spec.name,
        description = "[plugin:${plugin.manifest.id}] ${spec.description}".trim(),
        parameters = {
            InputSchema.Obj(
                properties = spec.propertyMap,
                required = spec.requiredNames.ifEmpty { null },
            )
        },
        needsApproval = { spec.needsApproval },
        execute = { args ->
            val result = runtime.invoke(plugin.dir, plugin.manifest, spec.name, args.toString())
            if (result.ok) {
                store.clearFailures(plugin.manifest.id)
                buildParts(result)
            } else {
                val state = store.recordFailure(
                    plugin.manifest.id,
                    "tool ${spec.name}: ${result.error}",
                )
                val note = if (!state.enabled) {
                    "; plugin auto-disabled after ${state.failures} consecutive failures"
                } else {
                    ""
                }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "plugin_tool_failed")
                            put("plugin", plugin.manifest.id)
                            put("detail", (result.error ?: "unknown error") + note)
                            result.logs?.let { put("logs", it) }
                        }.toString()
                    )
                )
            }
        },
    )

    private fun buildParts(result: PluginInvocation): List<UIMessagePart> {
        val parts = ArrayList<UIMessagePart>(2)
        val text = result.text.orEmpty()
        val payload = if (result.logs.isNullOrBlank()) {
            text
        } else {
            buildJsonObject {
                put("result", text)
                put("logs", result.logs)
            }.toString()
        }
        parts += UIMessagePart.Text(payload)
        result.imageBase64?.let { base64 ->
            imageResolver(base64)?.let { parts += UIMessagePart.Image(url = it) }
        }
        return parts
    }

    // ------------------------------------------------------------- status / admin

    fun statuses(): List<PluginStatus> {
        val snap = snapshot
        val states = store.states()
        val ids = (store.listIds() + snap.plugins.map { it.manifest.id }).distinct().sorted()
        return ids.map { id ->
            val loaded = snap.plugins.firstOrNull { it.manifest.id == id }
            val manifest = loaded?.manifest ?: store.readManifest(id)?.manifest
            val error = snap.errors.firstOrNull { it.id == id }?.message
            val state = states[id] ?: PluginState()
            PluginStatus(
                id = id,
                name = manifest?.name?.ifBlank { id } ?: id,
                version = manifest?.version.orEmpty(),
                description = manifest?.description.orEmpty(),
                enabled = state.enabled,
                loadError = error,
                failures = state.failures,
                lastError = state.lastError,
                toolNames = loaded?.manifest?.tools?.map { it.name }.orEmpty(),
                promptChars = loaded?.prompt?.length ?: 0,
                declaredTools = manifest?.tools?.map { it.name }.orEmpty(),
                panel = manifest?.ui?.panel?.takeIf { it.isNotBlank() },
                panelTitle = manifest?.ui?.title.orEmpty(),
                slots = manifest?.ui?.slots.orEmpty().map { "${it.where}:${it.type}" },
            )
        }
    }

    /**
     * `ui.panel` 的 HTML 内容，相对插件目录解析。读不到返回 null。
     *
     * 面板通过 `https://myagent.local/plugin/<id>/…` 加载自己的 css/js（见
     * WebViewLocalAssets），所以这里只负责把入口文件交给 WebView。
     */
    fun panelHtml(id: String): String? {
        val manifest = snapshot.plugins.firstOrNull { it.manifest.id == id }?.manifest
            ?: store.readManifest(id)?.manifest
            ?: return null
        val panel = manifest.ui?.panel?.takeIf { it.isNotBlank() } ?: return null
        return store.readFile(id, panel)
    }

    fun panelTitleOf(id: String): String {
        val manifest = snapshot.plugins.firstOrNull { it.manifest.id == id }?.manifest
            ?: store.readManifest(id)?.manifest
            ?: return id
        return manifest.ui?.title?.takeIf { it.isNotBlank() }
            ?: manifest.name.ifBlank { manifest.id }
    }

    // --------------------------------------------------------------- chat slots

    /**
     * 某个位置（`composer` / `message`）要渲染的槽位，按插件顺序 + 声明顺序。
     *
     * 只有「已启用且没有超过失败上限」的插件在 [PluginSnapshot.plugins] 里，所以这里不用
     * 再判启停；被停用的插件其界面也一并消失，和它的工具一致。
     */
    suspend fun slots(where: String, refresh: Boolean = false): List<PluginSlot> {
        val snap = snapshot(force = refresh)
        synchronized(slotListCache) {
            slotListCache[where]?.let { (signature, slots) ->
                if (signature == snap.signature) return slots
            }
        }
        val built = withContext(Dispatchers.IO) {
            val out = ArrayList<PluginSlot>()
            snap.plugins.forEach { plugin ->
                val id = plugin.manifest.id
                plugin.manifest.ui?.slots.orEmpty().forEachIndexed { index, spec ->
                    if (spec.where != where) return@forEachIndexed
                    val html = if (spec.type == PluginValidationRules.SLOT_TYPE_HTML) {
                        readSlotHtml(id, spec.file)
                    } else {
                        null
                    }
                    out += PluginSlot(
                        pluginId = id,
                        pluginName = plugin.manifest.name.ifBlank { id },
                        index = index,
                        spec = spec,
                        html = html,
                    )
                }
            }
            out
        }
        synchronized(slotListCache) {
            slotListCache[where] = snap.signature to built
        }
        return built
    }

    /**
     * 读槽位网页，带 (mtime, size) 缓存。
     *
     * 槽位列表在「消息数变了」时重查，一次对话就是几十次；卡片文件通常 1–50 KB，每次都重读
     * 纯属浪费。命中判据是文件时间戳 + 长度，所以模型改完文件立刻失效，热更新语义不变。
     */
    private fun readSlotHtml(id: String, relPath: String): String? {
        val key = "$id/$relPath"
        val file = File(store.dir(id), relPath)
        val modified = file.lastModified()
        val size = file.length()
        synchronized(slotHtmlCache) {
            val hit = slotHtmlCache[key]
            if (hit != null && hit.modified == modified && hit.size == size) return hit.content
        }
        val content = store.readFile(id, relPath)
        synchronized(slotHtmlCache) {
            // 淘汰由 LinkedHashMap 的 accessOrder + removeEldestEntry 负责。
            slotHtmlCache[key] = CachedSlotHtml(modified, size, content)
        }
        return content
    }

    private data class CachedSlotHtml(val modified: Long, val size: Long, val content: String?)


    /**
     * 跑一个槽位按钮的动作。
     *
     * 三道闸：插件要已加载；函数名要被该插件的**某个槽位**声明过（模型/网页不能凭空点名
     * 一个未声明的函数）；整体 [SLOT_ACTION_TIMEOUT_MS] 墙钟。失败不记入插件失败计数 ——
     * 用户点按钮失败不该把一个工具正常的插件自动停用。
     */
    suspend fun runSlotAction(
        pluginId: String,
        action: String,
        argsJson: String = "{}",
    ): PluginSlotAction {
        val snap = snapshot()
        val plugin = snap.plugins.firstOrNull { it.manifest.id == pluginId }
            ?: return PluginSlotAction(ok = false, error = "plugin '$pluginId' is not loaded")
        val declared = plugin.manifest.ui?.slots.orEmpty()
            .flatMap { it.items }
            .map { it.action }
            .filter { it.isNotBlank() }
            .toSet()
        if (action !in declared) {
            return PluginSlotAction(
                ok = false,
                error = "action '$action' is not declared by any slot of '$pluginId'",
            )
        }
        val result = withTimeoutOrNull(SLOT_ACTION_TIMEOUT_MS) {
            runtime.invokeAction(plugin.dir, plugin.manifest, action, argsJson)
        } ?: return PluginSlotAction(
            ok = false,
            error = "action '$action' timed out after ${SLOT_ACTION_TIMEOUT_MS}ms",
        )
        if (!result.ok) return PluginSlotAction(ok = false, error = result.error ?: "action failed")
        return parseSlotAction(pluginId, result.text.orEmpty())
    }

    private fun parseSlotAction(pluginId: String, raw: String): PluginSlotAction {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return PluginSlotAction(ok = true)
        val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
            ?: return PluginSlotAction(ok = true, text = trimmed.take(MAX_SLOT_TEXT_CHARS))
        fun field(name: String, max: Int): String? =
            (obj[name] as? JsonPrimitive)?.contentOrNull?.take(max)?.ifBlank { null }
        // `panel: ""` 表示「本插件自己的面板」，所以这里不能用 field()（空串会被当成缺席）。
        // 目标 id 必须像插件 id：否则界面会去打开一个不可能存在的页面，还把任意字符串
        // 塞进导航栈。
        val panelTarget = (obj["panel"] as? JsonPrimitive)?.contentOrNull?.take(64)
        val panel = panelTarget?.let { raw ->
            val id = if (raw.isBlank()) pluginId else raw.trim()
            if (PLUGIN_ID_RE.matches(id)) id else null
        }
        return PluginSlotAction(
            ok = true,
            insert = field("insert", MAX_SLOT_INSERT_CHARS),
            send = field("send", MAX_SLOT_SEND_CHARS),
            toast = field("toast", MAX_SLOT_TOAST_CHARS),
            panel = panel,
            text = field("text", MAX_SLOT_TEXT_CHARS),
        )
    }

    /**
     * 跑 `after_tool` 钩子：每个声明了它的插件依次拿到当前输出，返回非空则**替换**。
     *
     * 与 [runHooks] 的区别是「合并方式」：钩子提示用标题块拼起来喂模型，工具输出必须还是
     * 原来那段文本（模型读的是它），所以这里串行覆盖，不加任何标题。
     */
    suspend fun runAfterToolHook(toolName: String, argsJson: String, output: String): String {
        val targets = snapshot.plugins.filter { plugin ->
            plugin.state.enabled && plugin.manifest.hooks?.functionFor("after_tool") != null
        }
        if (targets.isEmpty()) return output
        var current = output
        for (plugin in targets) {
            val function = plugin.manifest.hooks?.functionFor("after_tool") ?: continue
            val payload = buildJsonObject {
                put("tool", toolName)
                put("args", JsonPrimitive(argsJson.take(MAX_SLOT_ARGS_CHARS)))
                put("output", JsonPrimitive(current.take(MAX_TOOL_HOOK_CHARS)))
            }.toString()
            val result = withTimeoutOrNull(TOOL_HOOK_TIMEOUT_MS) {
                runtime.invokeHook(plugin.dir, plugin.manifest, function, payload)
            }
            if (result == null) {
                Log.w(TAG, "after_tool of '${plugin.manifest.id}' timed out after ${TOOL_HOOK_TIMEOUT_MS}ms")
                continue
            }
            if (!result.ok) {
                Log.w(TAG, "after_tool of '${plugin.manifest.id}' failed: ${result.error}")
                continue
            }
            val next = result.text?.take(MAX_TOOL_HOOK_CHARS).orEmpty()
            if (next.isNotBlank()) current = next
        }
        return current
    }

    /**

     *
     * 三条约束都是为了让插件钩子不能拖垮聊天：
     *  - 没有插件声明它 → 立刻返回空串，一个 JS 上下文都不启动（每轮零成本）；
     *  - 单插件 [HOOK_TIMEOUT_MS] 超时、合并结果截断 [MAX_HOOK_PROMPT_CHARS]；
     *  - 失败/超时只记日志：不重试、不自动禁用插件（那是工具失败才有的惩罚）。
     */
    suspend fun runHooks(hook: String, argsJson: String = "{}"): String {
        val targets = snapshot.plugins.filter { plugin ->
            plugin.state.enabled && plugin.manifest.hooks?.functionFor(hook) != null
        }
        if (targets.isEmpty()) return ""

        val parts = ArrayList<String>()
        var remaining = MAX_HOOK_PROMPT_CHARS
        for (plugin in targets) {
            if (remaining <= 0) break
            val function = plugin.manifest.hooks?.functionFor(hook) ?: continue
            val result = withTimeoutOrNull(HOOK_TIMEOUT_MS) {
                runtime.invokeHook(plugin.dir, plugin.manifest, function, argsJson)
            }
            if (result == null) {
                Log.w(TAG, "hook '$hook' of '${plugin.manifest.id}' timed out after ${HOOK_TIMEOUT_MS}ms")
                continue
            }
            if (!result.ok) {
                Log.w(TAG, "hook '$hook' of '${plugin.manifest.id}' failed: ${result.error}")
                continue
            }
            val text = result.text?.trim().orEmpty()
            if (text.isEmpty()) continue
            val header = "### Hook: ${plugin.manifest.name.ifBlank { plugin.manifest.id }} ($hook)"
            val block = header + "\n" + text.take(remaining)
            parts += block
            remaining -= block.length
        }
        return parts.joinToString("\n\n")
    }

    /**
     * Runs one declared tool with the given args (default `{}`) and returns a human-readable
     * report. This is the manual counterpart to `plugin_check`: the author/model can verify a
     * handler actually executes — not just that the entry parses — before relying on it.
     */
    suspend fun invokeForTest(id: String, toolName: String, argsJson: String = "{}"): String {
        val snap = snapshot()
        val plugin = snap.plugins.firstOrNull { it.manifest.id == id }
            ?: return "error: plugin '$id' is not loaded"
        if (plugin.manifest.tools.none { it.name == toolName }) {
            return "error: plugin '$id' does not declare tool '$toolName'"
        }
        val result = runtime.invoke(plugin.dir, plugin.manifest, toolName, argsJson)
        if (result.ok) {
            store.clearFailures(id)
            return buildString {
                append("ok in ${result.durationMs}ms")
                result.text?.let { append("\n").append(it) }
                result.logs?.let { append("\nlogs:\n").append(it) }
            }
        }
        val state = store.recordFailure(id, "manual run $toolName: ${result.error}")
        val suffix = if (!state.enabled) " (plugin auto-disabled after ${state.failures} failures)" else ""
        return "error: ${result.error}$suffix"
    }

    /** Dry-run: load the entry and report the tools it registers vs. what it declares. */
    suspend fun inspect(id: String): String {
        val source = store.readManifest(id) ?: return "error: plugin '$id' has no readable manifest"
        if (!source.validation.ok) return "error: ${source.validation.render()}"
        val result = runtime.inspect(source.dir, source.manifest)
        if (!result.ok) return "error: ${result.error}"
        val declared = source.manifest.tools.map { it.name }.toSet()
        val registered = result.registeredTools.toSet()
        return buildString {
            appendLine("ok: entry loaded in ${result.durationMs}ms")
            appendLine("declared: ${declared.sorted().joinToString(", ").ifEmpty { "(none)" }}")
            appendLine("registered: ${registered.sorted().joinToString(", ").ifEmpty { "(none)" }}")
            val missing = declared - registered
            val extra = registered - declared
            if (missing.isNotEmpty()) appendLine("warning: declared but not registered: ${missing.joinToString(", ")}")
            if (extra.isNotEmpty()) appendLine("warning: registered but not declared: ${extra.joinToString(", ")}")
            val declaredActions = source.manifest.ui?.slots.orEmpty()
                .flatMap { it.items }.map { it.action }.filter { it.isNotBlank() }.toSet()
            if (declaredActions.isNotEmpty() || result.registeredActions.isNotEmpty()) {
                val registeredActions = result.registeredActions.toSet()
                appendLine("actions declared: ${declaredActions.sorted().joinToString(", ").ifEmpty { "(none)" }}")
                appendLine("actions registered: ${registeredActions.sorted().joinToString(", ").ifEmpty { "(none)" }}")
                val missingActions = declaredActions - registeredActions
                if (missingActions.isNotEmpty()) {
                    appendLine("warning: slot actions not registered: ${missingActions.joinToString(", ")}")
                }
            }
            result.logs?.let { appendLine("logs:\n$it") }
        }.trim()
    }

    private suspend fun invokeHostTool(name: String, argsJson: String): String {
        val tool = builtinTools.firstOrNull { it.name == name }
            ?: return buildJsonObject {
                put("ok", false)
                put("error", "no host tool named '$name'")
            }.toString()
        return runCatching {
            val element: JsonElement = runCatching { json.parseToJsonElement(argsJson) }
                .getOrElse { JsonObject(emptyMap()) }
            val parts = tool.execute(element)
            val text = parts.joinToString("\n") { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text
                    else -> part.toString()
                }
            }
            buildJsonObject {
                put("ok", true)
                put("result", JsonPrimitive(text.take(64_000)))
            }.toString()
        }.getOrElse { e ->
            buildJsonObject {
                put("ok", false)
                put("error", "host tool '$name' threw: ${e.message}")
            }.toString()
        }
    }

    companion object {
        const val MAX_PROMPT_CHARS = 12_000

        /** 与 PluginValidationRules 的 id 规则一致：面板目标必须是一个合法插件 id。 */
        private val PLUGIN_ID_RE = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

        /** 单个插件钩子的墙钟上限：它跑在每轮生成之前，慢一点就是用户多等一点。 */
        const val HOOK_TIMEOUT_MS = 1_200L

        /** 所有钩子合并后追加进提示的上限，避免一个插件把上下文吃掉。 */
        const val MAX_HOOK_PROMPT_CHARS = 4_000

        /** 单个 `after_tool` 钩子的墙钟上限：它跑在每个工具之后，慢就是整轮变慢。 */
        const val TOOL_HOOK_TIMEOUT_MS = 2_000L

        /** `after_tool` 能返回的最大长度（替换工具输出，比提示宽松得多）。 */
        const val MAX_TOOL_HOOK_CHARS = 32_000

        /** 槽位动作的墙钟上限：用户点了按钮在等，不能像工具那样跑 30 秒。 */
        const val SLOT_ACTION_TIMEOUT_MS = 5_000L

        /** 槽位动作返回值的上限：填进输入框 / 直接发送 / 提示 / 网页自取数据。 */
        const val MAX_SLOT_INSERT_CHARS = 4_000
        const val MAX_SLOT_SEND_CHARS = 8_000
        const val MAX_SLOT_TOAST_CHARS = 300
        const val MAX_SLOT_TEXT_CHARS = 8_000

        /** 传给 `after_tool` 的参数 JSON 上限（工具入参可能很大）。 */
        const val MAX_SLOT_ARGS_CHARS = 4_000

        /** 槽位网页缓存条数。 */
        const val MAX_SLOT_HTML_CACHE = 8

        /** Names owned by the kernel's own admin surface; plugins may not take them. */
        val ADMIN_TOOL_NAMES = setOf(
            "plugin_list", "plugin_read", "plugin_write", "plugin_create", "plugin_check",
            "plugin_set_enabled", "plugin_delete", "plugin_rollback", "plugin_reload",
        )
    }
}

/**
 * Breaks the construction cycle between the registry and the JS runtime: the runtime needs
 * a way to call host tools, host tools are resolved from the registry. The registry fills
 * [invoker] in its constructor; calling through an unset bridge is a structured error.
 */
class PluginHostToolBridge {
    @Volatile
    var invoker: (suspend (String, String) -> String)? = null
}
