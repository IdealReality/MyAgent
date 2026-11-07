package me.rerere.rikkahub.plugin

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
)

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

    val ALL = setOf(HTTP, STORAGE, HOST_TOOLS)
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

    private val ID_RE = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val VERSION_RE = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+([-.+][A-Za-z0-9.+-]+)?$")
    private val TOOL_NAME_RE = Regex("^[a-z][a-z0-9_]{1,63}$")

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
        if (manifest.tools.isEmpty() && manifest.prompt == null && manifest.ui == null) {
            warnings += "plugin contributes nothing (no tools, prompt or ui)"
        }

        return PluginValidation(errors = errors, warnings = warnings)
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
