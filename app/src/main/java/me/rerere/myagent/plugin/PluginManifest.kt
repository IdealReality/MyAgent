package me.rerere.myagent.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * P2-40 — plugin kernel, part 1: the on-disk contract.
 *
 * A plugin is a directory under `files/plugins/<id>/` holding a `manifest.json` plus the
 * JS entry file it names. Nothing here is compiled into the APK: the same manifest the
 * host parses at boot is what the LLM writes when it decides to extend itself, which is
 * the whole point of the layer (see docs/engineering/PLUGIN-KERNEL.md).
 *
 * Deliberately data-only: no code paths, no class names, no reflection. The loader
 * ([PluginRegistry]) is the only place that turns a manifest into runtime behaviour, so a
 * malformed or hostile manifest can only ever fail validation — it can never execute
 * anything while being parsed.
 */
@Serializable
data class PluginManifest(
    @SerialName("schema_version")
    val schemaVersion: Int = 1,
    val id: String = "",
    val name: String = "",
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = "",
    /** Path inside the plugin directory. Must exist and stay inside the directory. */
    val entry: String = "main.js",
    /** Declared capabilities; unknown values are dropped with a warning. */
    val permissions: List<String> = emptyList(),
    /** Stable system-prompt contribution (inline text or a file inside the plugin dir). */
    val prompt: PluginPromptSpec? = null,
    val tools: List<PluginToolSpec> = emptyList(),
    /** Optional HTML panel opened from the Plugins page. */
    val ui: PluginUiSpec? = null,
    /** Lifecycle hooks the plugin wants the host to call. */
    val hooks: PluginHooksSpec? = null,
)

/**
 * 生命周期钩子。
 *
 * 刻意只开了「只读、可失败」的一种：`before_turn` 在每轮生成前跑，返回值作为**额外的
 * 系统提示**追加进去。它不能改用户消息、不能否决本轮、不能碰对话数据 —— 于是插件崩了、
 * 超时了，结果只是这轮少一段提示，聊天照常。
 *
 * 同名 JS 函数用 `plugin.hook('beforeTurn', fn)` 在加载时注册；未注册等于没有输出。
 */
@Serializable
data class PluginHooksSpec(
    @SerialName("before_turn")
    val beforeTurn: String = "",
    /**
     * 每次工具执行完跑一次：参数是 `{tool, args, output}`，返回值**替换**该工具交回模型
     * 的文本。用途是后处理（去 ANSI、压缩、抽出关键行），不是拦截 —— 工具已经跑完，
     * 副作用无法撤销。
     *
     * 刻意没有 `before_tool`：那等于让插件伪造/改写任意工具的入参，绕过工具自己的审批
     * 与权限边界（一个声明了 `storage` 的插件就能借 shell 工具为所欲为）。
     */
    @SerialName("after_tool")
    val afterTool: String = "",
) {
    fun functionFor(hook: String): String? = when (hook) {
        "before_turn" -> beforeTurn.takeIf { it.isNotBlank() }
        "after_tool" -> afterTool.takeIf { it.isNotBlank() }
        else -> null
    }

    /** 所有声明了处理函数的钩子名（供校验与自检报告使用）。 */
    val declaredHooks: List<String>
        get() = listOfNotNull(
            "before_turn".takeIf { beforeTurn.isNotBlank() },
            "after_tool".takeIf { afterTool.isNotBlank() },
        )
}

@Serializable
data class PluginPromptSpec(
    val text: String? = null,
    val file: String? = null,
)

@Serializable
data class PluginToolSpec(
    val name: String = "",
    val description: String = "",
    /**
     * JSON Schema for the tool arguments. Only the `properties` / `required` shape the
     * app's [me.rerere.ai.core.InputSchema.Obj] can express is honoured; the rest of the
     * document is kept for display but not sent to the model.
     */
    val parameters: JsonObject? = null,
    /** Default true: plugin tools can reach the network, so they ask unless waived. */
    @SerialName("needs_approval")
    val needsApproval: Boolean = true,
) {
    val propertyMap: JsonObject
        get() = parameters?.get("properties")?.jsonObjectOrNull ?: JsonObject(emptyMap())

    val requiredNames: List<String>
        get() = (parameters?.get("required") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
}

@Serializable
data class PluginUiSpec(
    val panel: String = "",
    val title: String = "",
    /**
     * 聊天内槽位：输入框上方（composer）与消息下方（message）。
     *
     * 全部内容来自磁盘：`chips` 只是按钮清单，`html` 是插件目录里的网页。改这两个文件
     * 写什么，界面就变成什么 —— 这就是「对话把应用改成什么样」里唯一不需要重装 APK 的
     * 那一层界面。
     */
    val slots: List<PluginSlotSpec> = emptyList(),
)

/** 槽位位置。新增位置需要同时在 UI 里接一个渲染点，所以是封闭枚举而不是自由字符串。 */
object PluginSlotWhere {
    const val COMPOSER = "composer"
    const val MESSAGE = "message"
    val ALL = setOf(COMPOSER, MESSAGE)
}

@Serializable
data class PluginSlotSpec(
    /** [PluginSlotWhere] 之一。 */
    val where: String = "",
    /** `chips`（原生按钮行）或 `html`（插件目录里的网页）。 */
    val type: String = "chips",
    /** chips 行首的前缀标签；html 不显示。 */
    val title: String = "",
    /** type=html 的入口文件，相对插件目录。 */
    val file: String = "",
    /** type=html 的渲染高度（dp），clamp 到 [PluginValidationRules.MIN_SLOT_HEIGHT]..MAX。 */
    val height: Int = 64,
    /**
     * where=message：是否挂在每条消息下。默认 false —— 只挂在最新一条助手消息下，
     * 否则一段长对话会同时存在几十个 WebView。
     */
    @SerialName("all_messages")
    val allMessages: Boolean = false,
    /** type=chips 的按钮；点一下调一次 `plugin.action(name, fn)`。 */
    val items: List<PluginSlotItem> = emptyList(),
)

@Serializable
data class PluginSlotItem(
    val label: String = "",
    /** 注册在 `plugin.action(name, fn)` 的函数名。与 [panel] 二选一。 */
    val action: String = "",
    /**
     * 直接打开一个插件面板；空字符串表示「本插件自己的 `ui.panel`」，null 表示按钮不是
     * 打开面板。这样「从聊天里点开面板」不需要写一行 JS。
     */
    val panel: String? = null,
    /** 可选前缀（emoji 或单个符号），渲染在文字左侧。 */
    val icon: String = "",
)

/**
 * Capabilities a plugin may request. Anything not in this set is refused at load time so
 * an old/new host can never silently grant an undeclared power.
 */
object PluginPermissions {
    /** Outbound HTTP through the host's OkHttp client (proxy + DNS guards included). */
    const val HTTP = "http"

    /** A private key/value store scoped to the plugin id. */
    const val STORAGE = "storage"

    /** Call other registered tools (built-in or plugin) by name. */
    const val HOST_TOOLS = "host_tools"

    /** Read app state (display settings, assistants) through `ctx.app.get(...)`. */
    const val APP_READ = "app_read"

    /** Write the whitelisted subset of app state through `ctx.app.set(...)`. */
    const val APP_WRITE = "app_write"

    val ALL = setOf(HTTP, STORAGE, HOST_TOOLS, APP_READ, APP_WRITE)
}

data class PluginValidation(
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val ok: Boolean get() = errors.isEmpty()

    fun render(): String = buildString {
        if (errors.isEmpty() && warnings.isEmpty()) append("ok")
        errors.forEach { append("error: ").appendLine(it) }
        warnings.forEach { append("warning: ").appendLine(it) }
    }.trim()
}

object PluginValidationRules {
    const val MAX_TOOLS = 40
    const val MAX_ENTRY_BYTES = 2L * 1024L * 1024L
    const val MAX_PROMPT_BYTES = 32L * 1024L
    const val MAX_WRITE_BYTES = 4L * 1024L * 1024L

    /** 槽位：一个插件最多几个、网页槽位渲染高度上限、按钮行最多几个。 */
    const val MAX_SLOTS = 8
    const val MAX_SLOT_ITEMS = 12
    const val MIN_SLOT_HEIGHT = 24
    const val MAX_SLOT_HEIGHT = 400
    const val SLOT_TYPE_CHIPS = "chips"
    const val SLOT_TYPE_HTML = "html"
    private val SLOT_TYPES = setOf(SLOT_TYPE_CHIPS, SLOT_TYPE_HTML)

    private val ID_RE = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val VERSION_RE = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+([-.+][A-Za-z0-9.+-]+)?$")
    private val TOOL_NAME_RE = Regex("^[a-z][a-z0-9_]{1,63}$")
    /** JS 标识符级别：钩子名必须是插件里真实存在的函数名。 */
    private val HOOK_NAME_RE = Regex("^[A-Za-z_$][A-Za-z0-9_$]{0,63}$")

    /** Ids that would collide with host-owned namespaces. */
    private val RESERVED_IDS = setOf("builtin", "core", "system", "host", "kernel")

    fun validate(manifest: PluginManifest, dir: File?): PluginValidation {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (manifest.schemaVersion != 1) {
            errors += "unsupported schema_version=${manifest.schemaVersion} (expected 1)"
        }
        if (!ID_RE.matches(manifest.id)) {
            errors += "id '${manifest.id}' must match ${ID_RE.pattern}"
        } else if (manifest.id in RESERVED_IDS) {
            errors += "id '${manifest.id}' is reserved by the host"
        }
        if (manifest.name.isBlank()) warnings += "name is blank; the UI will fall back to the id"
        if (!VERSION_RE.matches(manifest.version)) {
            errors += "version '${manifest.version}' is not semver-like (e.g. 1.0.0)"
        }
        if (manifest.description.length > 4_000) warnings += "description is very long (${manifest.description.length} chars)"
        if (manifest.permissions.any { it.isBlank() }) errors += "permissions contain a blank entry"
        val unknown = manifest.permissions.filter { it.isNotBlank() && it !in PluginPermissions.ALL }
        if (unknown.isNotEmpty()) {
            warnings += "unknown permissions dropped: ${unknown.joinToString(", ")}"
        }

        if (manifest.tools.size > MAX_TOOLS) {
            errors += "too many tools (${manifest.tools.size} > $MAX_TOOLS)"
        }
        val seen = mutableSetOf<String>()
        manifest.tools.forEach { tool ->
            if (!TOOL_NAME_RE.matches(tool.name)) {
                errors += "tool name '${tool.name}' must match ${TOOL_NAME_RE.pattern}"
            } else if (!seen.add(tool.name)) {
                errors += "duplicate tool name '${tool.name}'"
            }
            if (tool.description.isBlank()) warnings += "tool '${tool.name}' has no description"
            val params = tool.parameters
            if (params != null) {
                val type = (params["type"] as? JsonPrimitive)?.contentOrNull
                if (type != null && type != "object") {
                    errors += "tool '${tool.name}' parameters.type must be \"object\""
                }
                if (params["properties"] != null && params["properties"] !is JsonObject) {
                    errors += "tool '${tool.name}' parameters.properties must be an object"
                }
            }
        }

        // Path-bearing fields: everything must resolve inside the plugin directory.
        listOfNotNull(
            "entry" to manifest.entry.takeIf { it.isNotBlank() },
            "prompt.file" to manifest.prompt?.file?.takeIf { it.isNotBlank() },
            "ui.panel" to manifest.ui?.panel?.takeIf { it.isNotBlank() },
        ).plus(
            manifest.ui?.slots.orEmpty().mapIndexedNotNull { index, slot ->
                slot.file.takeIf { it.isNotBlank() && slot.type == SLOT_TYPE_HTML }
                    ?.let { "ui.slots[$index].file" to it }
            }
        ).forEach { (label, path) ->
            if (path == null) return@forEach
            val problem = unsafeRelativePath(path)
            if (problem != null) {
                errors += "$label: $problem"
                return@forEach
            }
            if (dir != null) {
                val file = File(dir, path)
                if (!file.isFile) errors += "$label: file '$path' does not exist"
                else if (file.length() > MAX_ENTRY_BYTES) {
                    errors += "$label: '$path' is larger than $MAX_ENTRY_BYTES bytes"
                }
            }
        }

        val promptText = manifest.prompt?.text
        if (promptText != null && promptText.length > MAX_PROMPT_BYTES) {
            errors += "prompt.text exceeds $MAX_PROMPT_BYTES chars"
        }
        if (manifest.prompt?.text != null && manifest.prompt.file != null) {
            warnings += "prompt has both text and file; file wins"
        }
        manifest.hooks?.declaredHooks?.forEach { hook ->
            val fn = manifest.hooks.functionFor(hook).orEmpty()
            if (!HOOK_NAME_RE.matches(fn)) {
                errors += "hooks.$hook '$fn' must match ${HOOK_NAME_RE.pattern}"
            }
        }
        validateSlots(manifest, errors, warnings)
        if (manifest.tools.isEmpty() && manifest.prompt == null && manifest.ui == null && manifest.hooks == null) {
            warnings += "plugin contributes nothing (no tools, prompt, ui or hooks)"
        }

        return PluginValidation(errors = errors, warnings = warnings)
    }

    /**
     * 槽位校验。
     *
     * 这里的错误都会让整个插件不加载（和其他 manifest 错误一致）：一个 where/type 拼错
     * 的槽位是永久不显示的界面，静默忽略只会让人以为「插件坏了但不知道坏在哪」。
     */
    private fun validateSlots(
        manifest: PluginManifest,
        errors: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val slots = manifest.ui?.slots.orEmpty()
        if (slots.isEmpty()) return
        if (slots.size > MAX_SLOTS) {
            errors += "too many ui.slots (${slots.size} > $MAX_SLOTS)"
            return
        }
        slots.forEachIndexed { index, slot ->
            val label = "ui.slots[$index]"
            if (slot.where !in PluginSlotWhere.ALL) {
                errors += "$label.where '${slot.where}' must be one of ${PluginSlotWhere.ALL.sorted()}"
            }
            if (slot.type !in SLOT_TYPES) {
                errors += "$label.type '${slot.type}' must be one of ${SLOT_TYPES.sorted()}"
            }
            when (slot.type) {
                SLOT_TYPE_HTML -> {
                    if (slot.file.isBlank()) {
                        errors += "$label.file is required for type=html"
                    }
                    if (slot.height !in MIN_SLOT_HEIGHT..MAX_SLOT_HEIGHT) {
                        warnings += "$label.height=${slot.height} clamped to $MIN_SLOT_HEIGHT..$MAX_SLOT_HEIGHT"
                    }
                    if (slot.items.size > MAX_SLOT_ITEMS) {
                        errors += "$label has ${slot.items.size} items (> $MAX_SLOT_ITEMS)"
                    }
                    // html 槽位不渲染按钮，但 items[].action 仍然有用：它是「这个网页被允许
                    // 调哪些函数」的声明。没有这一条，纯网页插件只能靠另一个 chips 槽位声明。
                    slot.items.forEachIndexed { itemIndex, item ->
                        val itemLabel = "$label.items[$itemIndex]"
                        if (item.action.isBlank()) {
                            errors += "$itemLabel.action is required for type=html"
                        } else if (!HOOK_NAME_RE.matches(item.action)) {
                            errors += "$itemLabel.action '${item.action}' must match ${HOOK_NAME_RE.pattern}"
                        }
                        if (item.panel != null) {
                            errors += "$itemLabel.panel is not allowed for type=html"
                        }
                    }
                }

                SLOT_TYPE_CHIPS -> {
                    if (slot.file.isNotBlank()) {
                        warnings += "$label.file ignored for type=chips"
                    }
                    if (slot.items.isEmpty()) {
                        errors += "$label.items is required for type=chips"
                    }
                    if (slot.items.size > MAX_SLOT_ITEMS) {
                        errors += "$label has ${slot.items.size} items (> $MAX_SLOT_ITEMS)"
                    }
                    slot.items.forEachIndexed { itemIndex, item ->
                        val itemLabel = "$label.items[$itemIndex]"
                        if (item.label.isBlank()) errors += "$itemLabel.label is required"
                        val hasPanel = item.panel != null
                        if (item.action.isBlank() && !hasPanel) {
                            errors += "$itemLabel needs either action or panel"
                        }
                        if (item.action.isNotBlank() && hasPanel) {
                            errors += "$itemLabel: action and panel are mutually exclusive"
                        }
                        if (item.action.isNotBlank() && !HOOK_NAME_RE.matches(item.action)) {
                            errors += "$itemLabel.action '${item.action}' must match ${HOOK_NAME_RE.pattern}"
                        }
                        val panel = item.panel
                        if (panel != null && panel.isNotBlank() && !ID_RE.matches(panel)) {
                            errors += "$itemLabel.panel '$panel' must be a plugin id"
                        }
                    }
                }
            }
            if (slot.allMessages && slot.where != PluginSlotWhere.MESSAGE) {
                warnings += "$label.all_messages only applies to where=message"
            }
        }
    }

    /**
     * Returns a human-readable problem, or null when [path] is a plain relative path that
     * cannot escape its base directory (no absolute prefix, drive letters, backslashes or
     * `..` segments).
     */
    fun unsafeRelativePath(path: String): String? {
        if (path.startsWith("/") || path.startsWith("\\")) return "must be relative"
        if (Regex("^[A-Za-z]:").containsMatchIn(path)) return "must not contain a drive letter"
        if (path.contains('\\')) return "must use '/' separators"
        if (path.any { it == '\u0000' }) return "contains a NUL byte"
        val segments = path.split('/')
        if (segments.any { it == ".." }) return "must not contain '..'"
        if (segments.any { it.isBlank() }) return "contains an empty segment"
        return null
    }
}

internal val kotlinx.serialization.json.JsonElement.jsonObjectOrNull: JsonObject?
    get() = this as? JsonObject

/** Convenience for the UI/admin tools: `tools` as one readable line. */
fun PluginManifest.toolSummary(): String =
    if (tools.isEmpty()) "(none)" else tools.joinToString(", ") { it.name }
