package me.rerere.myagent.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * P2-40 — the self-modification surface.
 *
 * These are the tools that let the in-app model extend the app it is running inside:
 * write a plugin directory, validate it, and let the next turn pick it up. Everything is
 * snapshot-first, so a bad rewrite is one `plugin_rollback` away, and every write returns
 * the validation result instead of a bare "done".
 *
 * Deliberately NOT here: any way to write outside a plugin directory. The app's own code
 * stays out of reach — the kernel is the boundary; plugins are the extension point.
 */
fun pluginAdminTools(
    registry: PluginRegistry,
    store: PluginStore,
    json: Json,
): List<Tool> = listOf(
    listPluginTools(registry, json),
    readPluginFileTool(store, json),
    writePluginFileTool(registry, store, json),
    createPluginTool(registry, store, json),
    checkPluginTool(registry),
    setPluginEnabledTool(registry, store, json),
    deletePluginTool(registry, store, json),
    rollbackPluginTool(registry, store, json),
    reloadPluginsTool(registry, json),
)

private fun listPluginTools(registry: PluginRegistry, json: Json): Tool = Tool(
    name = "plugin_list",
    description = "List installed self-modification plugins with their enabled state, declared tools, " +
        "load errors and failure counters. Call this before writing a plugin so you do not clobber one.",
    parameters = { InputSchema.Obj(properties = kotlinx.serialization.json.JsonObject(emptyMap())) },
    execute = {
        val statuses = withContext(Dispatchers.IO) { registry.statuses() }
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("plugins", buildJsonArray {
                    statuses.forEach { status ->
                        add(buildJsonObject {
                            put("id", status.id)
                            put("name", status.name)
                            put("version", status.version)
                            put("enabled", status.enabled)
                            put("tools", status.declaredTools.joinToString(", "))
                            if (status.slots.isNotEmpty()) put("slots", status.slots.joinToString(", "))
                            if (status.panel != null) put("panel", status.panel)
                            status.loadError?.let { put("load_error", it) }
                            if (status.failures > 0) put("failures", status.failures)
                            status.lastError?.let { put("last_error", it) }
                        })
                    }
                })
            }.toString()
        ))
    },
)

private fun readPluginFileTool(store: PluginStore, json: Json): Tool = Tool(
    name = "plugin_read",
    description = "Read a file inside an installed plugin directory, or list its files when `path` is omitted. " +
        "Paths are relative to the plugin directory and cannot escape it.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("plugin_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Plugin id (the directory name).")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Relative path, e.g. manifest.json or main.js. Omit to list files.")
                })
            },
            required = listOf("plugin_id"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val id = obj["plugin_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (id.isBlank()) return@Tool err("missing_plugin_id", "plugin_id is required")
        val path = obj["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        withContext(Dispatchers.IO) {
            if (path == null) {
                val files = store.listFiles(id)
                if (files.isEmpty()) err("plugin_not_found", "no readable plugin directory '$id'")
                else listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("plugin", id)
                        put("files", buildJsonArray { files.forEach { add(JsonPrimitive(it)) } })
                    }.toString()
                ))
            } else {
                val content = store.readFile(id, path)
                if (content == null) err("file_not_found", "no file '$path' in plugin '$id'")
                else listOf(UIMessagePart.Text(content))
            }
        }
    },
)

private fun writePluginFileTool(registry: PluginRegistry, store: PluginStore, json: Json): Tool = Tool(
    name = "plugin_write",
    description = "Write or overwrite ONE file inside a plugin directory (snapshots the previous state first). " +
        "After writing manifest.json the manifest is re-validated and the verdict is returned. " +
        "The change is live from the NEXT assistant turn on — no rebuild, no reinstall.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("plugin_id", buildJsonObject { put("type", "string"); put("description", "Plugin id.") })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Relative path inside the plugin directory, e.g. main.js.")
                })
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Full file content to write.")
                })
            },
            required = listOf("plugin_id", "path", "content"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val obj = args.jsonObject
        val id = obj["plugin_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val path = obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (id.isBlank() || path.isBlank()) return@Tool err("missing_argument", "plugin_id and path are required")
        withContext(Dispatchers.IO) {
            val validation = store.writeFile(id, path, content)
            registry.reload()
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("plugin", id)
                    put("path", path)
                    put("bytes", content.toByteArray().size)
                    put("validation", validation.render())
                    put("takes_effect", "next turn")
                }.toString()
            ))
        }
    },
)

private fun createPluginTool(registry: PluginRegistry, store: PluginStore, json: Json): Tool = Tool(
    name = "plugin_create",
    description = """
        Create a new plugin (manifest.json + optional main.js) and validate it.
        manifest.json keys: id (lowercase [a-z0-9._-]), name, version (x.y.z), description,
        entry (default "main.js"), permissions (subset of: http, storage, host_tools, app_read, app_write),
        prompt {text|file}, tools [{name, description, parameters (JSON Schema object), needs_approval}],
        ui {panel, title, slots} — panel is an HTML file rendered in-app (Plugins page → Panel),
        its relative css/js/images resolve as https://myagent.local/plugin/<id>/<file>, and it gets
        window.MyAgentHost {theme(), toast(msg), close(), plugin()} plus injected --rk-* theme vars.
        slots are in-chat surfaces: [{where: "composer"|"message", type: "chips"|"html", title?,
        file?, height? (24-400dp), all_messages?, items?}]. A chips item is {label, action?, panel?, icon?}:
        action runs a function registered with plugin.action(name, fn), panel (empty string = this plugin)
        opens a panel. An action returns {insert?, send?, toast?, panel?} — fill the composer, send now,
        toast, or open a panel. type=html renders `file` (same directory, relative assets work) and the page
        gets MyAgentHost {theme(), args() (conversation_id/message_id/role/text), call(action, argsJson),
        insert(text), send(text), openPanel(id), toast(msg)}. Message slots show under the newest message
        unless all_messages=true (each instance is a WebView, so keep that off for long chats).
        hooks {before_turn: "<js fn>", after_tool: "<js fn>"} — before_turn's returned string is appended to
        that turn's system prompt (volatile section, 1.2s budget, 4KB cap, failures never block the turn);
        after_tool receives {tool, args, output} and a non-empty return REPLACES that tool's text (2s, 32KB).
        main.js contract (synchronous): call plugin.tool('name', function(args, ctx){ ... }),
        plugin.action('name', function(args, ctx){ ... }) and/or plugin.hook('beforeTurn'|'afterTool', fn)
        at load time.
        ctx exposes: log(msg), now(), kv.get/set (needs storage), http(url, {method,headers,body}) (needs http),
        tool(name, args) to call another app tool (needs host_tools),
        app.get(op) / app.set(op, patch) to read or change whitelisted app state (needs app_read / app_write;
        ops: 'display' = UI settings such as cleanLayout, fontSizeRatio, bubbleOpacity, codeBlockAutoCollapse,
        inlineWebPreview, toolCallStyle; 'assistants' = read-only list). app.set('display', {...}) returns
        {applied, display}; unknown/out-of-range keys are ignored or clamped, never fatal.
        Return a string, or an object {text, image_base64?, error?}. Handlers MUST NOT use async/await or return a Promise.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("plugin_id", buildJsonObject { put("type", "string"); put("description", "Lowercase id; also the directory name.") })
                put("manifest_json", buildJsonObject { put("type", "string"); put("description", "Full manifest.json content.") })
                put("entry_js", buildJsonObject { put("type", "string"); put("description", "Optional main.js content.") })
            },
            required = listOf("plugin_id", "manifest_json"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val obj = args.jsonObject
        val id = obj["plugin_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val manifestJson = obj["manifest_json"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val entry = obj["entry_js"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        if (id.isBlank() || manifestJson.isBlank()) {
            return@Tool err("missing_argument", "plugin_id and manifest_json are required")
        }
        withContext(Dispatchers.IO) {
            val manifestProblem = runCatching {
                json.decodeFromString(PluginManifest.serializer(), manifestJson)
            }.exceptionOrNull()?.message
            if (manifestProblem != null) {
                return@withContext err("invalid_manifest_json", manifestProblem)
            }
            val validation = store.createPlugin(id, manifestJson, entry)
            registry.reload()
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("plugin", id)
                    put("validation", validation.render())
                    put("takes_effect", "next turn")
                }.toString()
            ))
        }
    },
)

private fun checkPluginTool(registry: PluginRegistry): Tool = Tool(
    name = "plugin_check",
    description = "Dry-run one installed plugin: load its JS entry, report which tools it registered vs. " +
        "what the manifest declares, plus any load error or console output. Does not execute a tool.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("plugin_id", buildJsonObject { put("type", "string"); put("description", "Plugin id.") })
            },
            required = listOf("plugin_id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["plugin_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (id.isBlank()) return@Tool err("missing_plugin_id", "plugin_id is required")
        val report = withContext(Dispatchers.IO) { registry.inspect(id) }
        listOf(UIMessagePart.Text(report))
    },
)

private fun setPluginEnabledTool(registry: PluginRegistry, store: PluginStore, json: Json): Tool = Tool(
    name = "plugin_set_enabled",
    description = "Enable or disable an installed plugin. Disabling removes its tools and prompt from the " +
        "surface from the next turn on; enabling retries it even after it was auto-disabled.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("plugin_id", buildJsonObject { put("type", "string"); put("description", "Plugin id.") })
                put("enabled", buildJsonObject { put("type", "boolean"); put("description", "true to enable.") })
            },
            required = listOf("plugin_id", "enabled"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val id = obj["plugin_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val enabled = obj["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: return@Tool err("missing_argument", "plugin_id and enabled are required")
        withContext(Dispatchers.IO) {
            store.setEnabled(id, enabled)
            if (enabled) store.clearFailures(id)
            registry.reload()
            listOf(UIMessagePart.Text(
                buildJsonObject { put("plugin", id); put("enabled", enabled) }.toString()
            ))
        }
    },
)

private fun deletePluginTool(registry: PluginRegistry, store: PluginStore, json: Json): Tool = Tool(
    name = "plugin_delete",
    description = "Delete an installed plugin directory. A snapshot is kept first, so plugin_rollback can " +
        "restore it. Removing a plugin is permanent from the model's point of view unless it rewrites it.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("plugin_id", buildJsonObject { put("type", "string"); put("description", "Plugin id.") })
            },
            required = listOf("plugin_id"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val id = args.jsonObject["plugin_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (id.isBlank()) return@Tool err("missing_plugin_id", "plugin_id is required")
        withContext(Dispatchers.IO) {
            val deleted = store.deletePlugin(id)
            registry.reload()
            if (!deleted) err("plugin_not_found", "no plugin directory '$id'")
            else listOf(UIMessagePart.Text(buildJsonObject { put("deleted", id) }.toString()))
        }
    },
)

private fun rollbackPluginTool(registry: PluginRegistry, store: PluginStore, json: Json): Tool = Tool(
    name = "plugin_rollback",
    description = "Restore the most recent snapshot of a plugin directory (taken before each write/create/" +
        "delete). Use after a bad edit breaks loading.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("plugin_id", buildJsonObject { put("type", "string"); put("description", "Plugin id.") })
            },
            required = listOf("plugin_id"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val id = args.jsonObject["plugin_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (id.isBlank()) return@Tool err("missing_plugin_id", "plugin_id is required")
        withContext(Dispatchers.IO) {
            val restored = store.rollback(id)
            registry.reload()
            if (!restored) err("no_snapshot", "plugin '$id' has no snapshot to restore")
            else listOf(UIMessagePart.Text(buildJsonObject { put("rolled_back", id) }.toString()))
        }
    },
)

private fun reloadPluginsTool(registry: PluginRegistry, json: Json): Tool = Tool(
    name = "plugin_reload",
    description = "Force a re-scan of the plugin directory (normally unnecessary: the registry already " +
        "reloads per turn when files change). Returns counts.",
    parameters = { InputSchema.Obj(properties = kotlinx.serialization.json.JsonObject(emptyMap())) },
    execute = {
        val snap = withContext(Dispatchers.IO) { registry.reload() }
        listOf(UIMessagePart.Text(
            buildJsonObject {
                put("loaded", snap.plugins.size)
                put("errors", snap.errors.size)
                snap.errors.take(10).forEach { put("error_${it.id}", it.message) }
            }.toString()
        ))
    },
)

private fun err(code: String, detail: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(buildJsonObject { put("error", code); put("detail", detail) }.toString())
)
