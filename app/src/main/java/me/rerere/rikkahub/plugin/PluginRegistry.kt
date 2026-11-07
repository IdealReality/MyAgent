package me.rerere.rikkahub.plugin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File

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

    @Volatile
    private var builtinTools: List<Tool> = emptyList()

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
        val promptParts = ArrayList<String>()

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
            if (promptText.isNotBlank()) promptParts += promptText
        }

        val block = buildPromptBlock(promptParts)
        val next = PluginSnapshot(
            plugins = loaded,
            errors = errors,
            signature = store.signature(),
            loadedAt = System.currentTimeMillis(),
        )
        snapshot = next
        promptBlock = block
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
            )
        }
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
