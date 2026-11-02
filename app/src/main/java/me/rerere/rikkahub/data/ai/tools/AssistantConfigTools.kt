package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AssistantResolver
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import kotlin.uuid.Uuid

/**
 * 助手配置自管理工具组（LocalToolOption.AssistantConfig）。
 *
 * 让 LLM 在聊天中读取并修改自己助手的系统提示词、技能开关，以及全局清单中的
 * 模式注入 / 世界书。助手本体与全局清单都持久化在 [SettingsStore]（DataStore），
 * 所有写入走 `settingsStore.update { }` 的互斥锁，与 UI 侧的修改天然串行。
 *
 * 写入顺序铁律：新建 ModeInjection / Lorebook 必须先写进全局清单（Settings.modeInjections /
 * Settings.lorebooks）拿到稳定 ID，再在同一事务内关联回当前助手——PreferencesStore 的保存
 * 管道会按全局清单过滤助手上无效的引用 ID，顺序反了关联会被直接清掉。
 *
 * 删除注入 / 世界书时，同步摘除所有助手（含当前助手）上的关联 ID，避免悬空引用。
 */
object AssistantConfigTools {

    /** 按行 diff，输出统一的 +/- 前缀格式；无差异时返回空串。 */
    fun lineDiff(before: List<String>, after: List<String>): String {
        val out = StringBuilder()
        val max = maxOf(before.size, after.size)
        for (i in 0 until max) {
            val b = before.getOrNull(i)
            val a = after.getOrNull(i)
            if (b == a) continue
            if (b != null) out.appendLine("- $b")
            if (a != null) out.appendLine("+ $a")
        }
        return out.toString().trimEnd()
    }

    private fun JsonArrayBuilder.addStrings(items: List<String>) {
        items.forEach { add(it) }
    }

    private fun assistantJson(a: Assistant): JsonObject = buildJsonObject {
        put("id", a.id.toString())
        put("name", a.name)
        put("system_prompt", a.systemPrompt)
        putJsonArray("enabled_skills") { addStrings(a.enabledSkills.sorted()) }
        putJsonArray("mode_injection_ids") { addStrings(a.modeInjectionIds.map { it.toString() }) }
        putJsonArray("lorebook_ids") { addStrings(a.lorebookIds.map { it.toString() }) }
    }

    private fun injectionJson(i: PromptInjection.ModeInjection, attached: Boolean): JsonObject = buildJsonObject {
        put("id", i.id.toString())
        put("name", i.name)
        put("enabled", i.enabled)
        put("priority", i.priority)
        put("position", i.position.name)
        put("role", i.role.name)
        put("inject_depth", i.injectDepth)
        put("attached_to_assistant", attached)
        put("content", i.content)
    }

    private fun lorebookJson(l: Lorebook, attached: Boolean): JsonObject = buildJsonObject {
        put("id", l.id.toString())
        put("name", l.name)
        put("description", l.description)
        put("enabled", l.enabled)
        put("entry_count", l.entries.size)
        put("attached_to_assistant", attached)
        putJsonArray("entries") {
            l.entries.forEach { e ->
                add(buildJsonObject {
                    put("id", e.id.toString())
                    put("name", e.name)
                    put("enabled", e.enabled)
                    put("keywords", JsonArray(e.keywords.map { JsonPrimitive(it) }))
                    put("use_regex", e.useRegex)
                    put("case_sensitive", e.caseSensitive)
                    put("scan_depth", e.scanDepth)
                    put("constant_active", e.constantActive)
                    put("priority", e.priority)
                    put("position", e.position.name)
                    put("role", e.role.name)
                    put("content", e.content)
                })
            }
        }
    }

    private fun skillsJson(skillManager: SkillManager, assistant: Assistant): JsonObject = buildJsonObject {
        putJsonArray("skills") {
            skillManager.listSkills().forEach { s ->
                add(buildJsonObject {
                    put("name", s.name)
                    put("description", s.description)
                    put("enabled", s.name in assistant.enabledSkills)
                })
            }
        }
    }

    /** 当前助手上已关联的 ModeInjection/Lorebook 完整内容。 */
    private fun attachedInjectionsJson(settings: Settings, assistant: Assistant): JsonObject = buildJsonObject {
        putJsonArray("mode_injections") {
            settings.modeInjections
                .filter { it.id in assistant.modeInjectionIds }
                .forEach { add(injectionJson(it, attached = true)) }
        }
        putJsonArray("lorebooks") {
            settings.lorebooks
                .filter { it.id in assistant.lorebookIds }
                .forEach { add(lorebookJson(it, attached = true)) }
        }
    }

    private fun err(error: String, detail: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", error)
        put("detail", detail)
    }

    private fun ok(vararg pairs: Pair<String, JsonElement>): JsonObject = buildJsonObject {
        put("ok", true)
        pairs.forEach { (k, v) -> put(k, v) }
    }

    private fun strArg(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull

    private fun boolArg(args: JsonObject, key: String, default: Boolean): Boolean =
        (args[key] as? JsonPrimitive)?.booleanOrNull ?: default

    private fun intArg(args: JsonObject, key: String): Int? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun stringsArg(args: JsonObject, key: String): List<String>? =
        (args[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun tool(
        name: String,
        description: String,
        properties: JsonObjectBuilder.() -> Unit = {},
        required: List<String> = emptyList(),
        execute: suspend (args: JsonObject) -> JsonObject,
    ): Tool = Tool(
        name = name,
        description = description.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject(properties),
                required = required.ifEmpty { null },
            )
        },
        execute = { raw ->
            val args = raw as? JsonObject ?: buildJsonObject { }
            val result = runCatching { execute(args) }
            result.getOrElse { failure ->
                err("tool_failed", failure.message ?: failure::class.simpleName.orEmpty())
            }.let { listOf(UIMessagePart.Text(it.toString())) }
        },
    )

    /**
     * 构建本组全部工具。
     *
     * @param assistantId 当前会话助手的 ID，来自调用方的 ToolInvocationContext；为 null
     *   （历史/无上下文调用路径）时所有工具直接拒绝——猜「当前助手」会把改动落到错误的助手上。
     */
    fun createTools(
        settingsStore: SettingsStore,
        skillManager: SkillManager,
        assistantId: String?,
    ): List<Tool> {
        fun resolveAssistant(settings: Settings, id: String?): Assistant? {
            val uuid = id?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: return null
            return AssistantResolver.byId(settings.assistants, uuid)
        }

        suspend fun refreshAssistant(): Assistant? =
            resolveAssistant(settingsStore.settingsFlow.first(), assistantId)

        return listOf(
            // ---------------------------------------------------------------- read
            tool(
                name = "read_assistant_config",
                description = """
                    Read this assistant's own configuration: system prompt (full text), enabled skills,
                    attached mode injections and lorebooks (full content), plus the global inventory of
                    all mode injections / lorebooks (id + name) and all available skills.
                """,
            ) {
                val settings = settingsStore.settingsFlow.first()
                val assistant = resolveAssistant(settings, assistantId)
                    ?: return@tool err("no_assistant", "The calling assistant could not be resolved for this session.")
                val attached = attachedInjectionsJson(settings, assistant)
                buildJsonObject {
                    put("ok", true)
                    put("assistant", assistantJson(assistant))
                    put("attached_mode_injections", attached["mode_injections"] ?: JsonArray(emptyList()))
                    put("attached_lorebooks", attached["lorebooks"] ?: JsonArray(emptyList()))
                    putJsonArray("global_mode_injections") {
                        settings.modeInjections.forEach { add(injectionJson(it, attached = it.id in assistant.modeInjectionIds)) }
                    }
                    putJsonArray("global_lorebooks") {
                        settings.lorebooks.forEach { add(lorebookJson(it, attached = it.id in assistant.lorebookIds)) }
                    }
                    put("skills", skillsJson(skillManager, assistant))
                }
            },
            // ------------------------------------------------- update_system_prompt
            tool(
                name = "update_system_prompt",
                description = """
                    Replace this assistant's system prompt. Returns a line-by-line diff (lines prefixed
                    with '-' were removed, '+' added). Pass the complete new prompt; there is no partial edit.
                """,
                properties = {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "The complete new system prompt text.")
                    }
                },
                required = listOf("text"),
            ) { args ->
                val text = strArg(args, "text")
                    ?: return@tool err("missing_argument", "text is required.")
                val before = resolveAssistant(settingsStore.settingsFlow.first(), assistantId)
                    ?: return@tool err("no_assistant", "The calling assistant could not be resolved for this session.")
                val next = before.copy(systemPrompt = text)
                settingsStore.update { s ->
                    s.copy(assistants = s.assistants.map { if (it.id == next.id) next else it })
                }
                ok(
                    "detail" to JsonPrimitive("system prompt updated"),
                    "diff" to JsonPrimitive(lineDiff(before.systemPrompt.lines(), text.lines())),
                )
            },
            // --------------------------------------------------------- list_skills
            tool(
                name = "list_skills",
                description = """
                    List all available skills with their name, description and whether they are currently
                    enabled on this assistant. Skill files are read-only here; use toggle_skill to enable
                    or disable one for this assistant.
                """,
            ) {
                val assistant = refreshAssistant()
                    ?: return@tool err("no_assistant", "The calling assistant could not be resolved for this session.")
                ok(
                    "skills" to (skillsJson(skillManager, assistant)["skills"] ?: JsonArray(emptyList())),
                    "enabled_skills" to JsonArray(assistant.enabledSkills.sorted().map { JsonPrimitive(it) }),
                )
            },
            // -------------------------------------------------------- toggle_skill
            tool(
                name = "toggle_skill",
                description = """
                    Enable or disable a skill for this assistant by name (see list_skills for valid names).
                    Only flips Assistant.enabledSkills; the skill file on disk is never modified.
                """,
                properties = {
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Skill name exactly as listed by list_skills.")
                    }
                    putJsonObject("enabled") {
                        put("type", "boolean")
                        put("description", "true to enable, false to disable.")
                    }
                },
                required = listOf("name", "enabled"),
            ) { args ->
                val name = strArg(args, "name")?.trim().orEmpty()
                val enabled = boolArg(args, "enabled", default = true)
                if (name.isEmpty()) return@tool err("missing_argument", "name is required.")
                val assistant = refreshAssistant()
                    ?: return@tool err("no_assistant", "The calling assistant could not be resolved for this session.")
                val known = skillManager.listSkills().map { it.name }.toSet()
                if (name !in known) {
                    return@tool err("unknown_skill", "No skill named '$name'. Call list_skills for the available names.")
                }
                if (enabled == (name in assistant.enabledSkills)) {
                    return@tool ok(
                        "detail" to JsonPrimitive("skill '$name' already ${if (enabled) "enabled" else "disabled"}"),
                        "enabled_skills" to JsonArray(assistant.enabledSkills.sorted().map { JsonPrimitive(it) }),
                    )
                }
                val next = assistant.copy(
                    enabledSkills = if (enabled) assistant.enabledSkills + name else assistant.enabledSkills - name
                )
                settingsStore.update { s ->
                    s.copy(assistants = s.assistants.map { if (it.id == next.id) next else it })
                }
                ok(
                    "detail" to JsonPrimitive("skill '$name' ${if (enabled) "enabled" else "disabled"}"),
                    "enabled_skills" to JsonArray(next.enabledSkills.sorted().map { JsonPrimitive(it) }),
                )
            },
            // ----------------------------------------------------- upsert_lorebook
            tool(
                name = "upsert_lorebook",
                description = """
                    Create or update a lorebook in the GLOBAL lorebook list, optionally attaching it to this
                    assistant. Pass id to update an existing lorebook; omit id to create one (a new id is
                    assigned). entries_json replaces the whole entry list; attach=true also links the lorebook
                    to this assistant, attach=false detaches it; omit attach to leave attachments untouched.
                """,
                properties = {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "Uuid of an existing lorebook to update. Omit to create a new one.")
                    }
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Display name. Required when creating.")
                    }
                    putJsonObject("description") {
                        put("type", "string")
                        put("description", "Short description.")
                    }
                    putJsonObject("enabled") {
                        put("type", "boolean")
                        put("description", "Master switch. Default true.")
                    }
                    putJsonObject("attach") {
                        put("type", "boolean")
                        put("description", "Also attach to (true) or detach from (false) this assistant. Omit to keep current attachments.")
                    }
                    putJsonObject("entries_json") {
                        put("type", "string")
                        put("description", "Optional JSON array of entries; each object: {name, content, keywords[], useRegex, caseSensitive, scanDepth, constantActive, priority, position(BEFORE_SYSTEM_PROMPT|AFTER_SYSTEM_PROMPT|TOP_OF_CHAT|BOTTOM_OF_CHAT|AT_DEPTH), role(USER|ASSISTANT), injectDepth, enabled}. Omit to keep existing entries.")
                    }
                },
            ) { args ->
                val settings = settingsStore.settingsFlow.first()
                val assistant = resolveAssistant(settings, assistantId)
                    ?: return@tool err("no_assistant", "The calling assistant could not be resolved for this session.")
                val idArg = strArg(args, "id")?.trim().orEmpty()
                val name = strArg(args, "name")?.trim().orEmpty()
                val description = strArg(args, "description")
                val enabled = boolArg(args, "enabled", default = true)
                val attach = (args["attach"] as? JsonPrimitive)?.booleanOrNull
                val entriesJson = strArg(args, "entries_json")
                val create = idArg.isEmpty()
                if (create && name.isEmpty()) {
                    return@tool err("missing_argument", "Provide id (update) or name (create).")
                }
                val entries: List<PromptInjection.RegexInjection>? = entriesJson?.let { raw ->
                    runCatching { lorebookEntriesFromJson(raw) }.getOrElse {
                        return@tool err("bad_entries_json", it.message ?: "failed to parse entries_json")
                    }
                }
                var resultId: String? = null
                settingsStore.update { s ->
                    val existing = if (create) null else
                        s.lorebooks.firstOrNull { it.id.toString() == idArg } ?: return@update s
                    val target = (existing ?: Lorebook(name = name)).copy(
                        name = name.ifEmpty { existing?.name.orEmpty() },
                        description = description ?: existing?.description.orEmpty(),
                        enabled = enabled,
                        entries = entries ?: (existing?.entries ?: emptyList()),
                    )
                    resultId = target.id.toString()
                    val lorebooks = if (create) s.lorebooks + target
                    else s.lorebooks.map { if (it.id == target.id) target else it }
                    val assistants = when (attach) {
                        true -> s.assistants.map { if (it.id == assistant.id) it.copy(lorebookIds = it.lorebookIds + target.id) else it }
                        false -> s.assistants.map { if (it.id == assistant.id) it.copy(lorebookIds = it.lorebookIds - target.id) else it }
                        null -> s.assistants
                    }
                    s.copy(lorebooks = lorebooks, assistants = assistants)
                }
                val savedId = resultId
                    ?: return@tool err("not_found", "No lorebook with id $idArg in the global list.")
                val after = resolveAssistant(settingsStore.settingsFlow.first(), assistantId) ?: assistant
                ok(
                    "detail" to JsonPrimitive(if (create) "lorebook created" else "lorebook updated"),
                    "id" to JsonPrimitive(savedId),
                    "attached" to JsonPrimitive(savedId in after.lorebookIds.map { it.toString() }),
                )
            },
            // ----------------------------------------------------- delete_lorebook
            tool(
                name = "delete_lorebook",
                description = """
                    Delete a lorebook from the GLOBAL list by id. If any assistant (including this one) has
                    it attached, the attachment is removed too, so no dangling reference is left behind.
                """,
                properties = {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "Uuid of the lorebook to delete.")
                    }
                },
                required = listOf("id"),
            ) { args ->
                val idArg = strArg(args, "id")?.trim().orEmpty()
                if (idArg.isEmpty()) return@tool err("missing_argument", "id is required.")
                val uuid = runCatching { Uuid.parse(idArg) }.getOrNull()
                    ?: return@tool err("bad_argument", "id is not a valid Uuid.")
                var removed = false
                settingsStore.update { s ->
                    if (s.lorebooks.none { it.id == uuid }) return@update s
                    removed = true
                    s.copy(
                        lorebooks = s.lorebooks.filterNot { it.id == uuid },
                        assistants = s.assistants.map {
                            if (uuid in it.lorebookIds) it.copy(lorebookIds = it.lorebookIds - uuid) else it
                        },
                    )
                }
                if (!removed) return@tool err("not_found", "No lorebook with id $idArg.")
                ok("detail" to JsonPrimitive("lorebook deleted"))
            },
            // ---------------------------------------------- upsert_mode_injection
            tool(
                name = "upsert_mode_injection",
                description = """
                    Create or update a mode injection in the GLOBAL injection list, optionally attaching it
                    to this assistant. Pass id to update; omit id to create. attach=true links it to this
                    assistant, attach=false detaches. Content is the injected prompt text.
                """,
                properties = {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "Uuid of an existing mode injection to update. Omit to create a new one.")
                    }
                    putJsonObject("name") {
                        put("type", "string")
                        put("description", "Display name. Required when creating.")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "Injected prompt text.")
                    }
                    putJsonObject("enabled") {
                        put("type", "boolean")
                        put("description", "Master switch. Default true.")
                    }
                    putJsonObject("priority") {
                        put("type", "integer")
                        put("description", "Sort priority. Default 0.")
                    }
                    putJsonObject("position") {
                        put("type", "string")
                        put("description", "BEFORE_SYSTEM_PROMPT, AFTER_SYSTEM_PROMPT (default), AT_DEPTH or IN_MESSAGES.")
                    }
                    putJsonObject("role") {
                        put("type", "string")
                        put("description", "USER (default) or ASSISTANT.")
                    }
                    putJsonObject("inject_depth") {
                        put("type", "integer")
                        put("description", "Messages back from the latest, for AT_DEPTH. Default 4.")
                    }
                    putJsonObject("attach") {
                        put("type", "boolean")
                        put("description", "Also attach to (true) or detach from (false) this assistant. Omit to keep current attachments.")
                    }
                },
            ) { args ->
                val settings = settingsStore.settingsFlow.first()
                val assistant = resolveAssistant(settings, assistantId)
                    ?: return@tool err("no_assistant", "The calling assistant could not be resolved for this session.")
                val idArg = strArg(args, "id")?.trim().orEmpty()
                val name = strArg(args, "name")?.trim().orEmpty()
                val content = strArg(args, "content")
                val enabled = boolArg(args, "enabled", default = true)
                val priority = intArg(args, "priority")
                val position = strArg(args, "position")?.let { p ->
                    runCatching { InjectionPosition.valueOf(p) }.getOrNull()
                }
                val role = strArg(args, "role")?.let { r ->
                    runCatching { me.rerere.ai.core.MessageRole.valueOf(r) }.getOrNull()
                }
                val injectDepth = intArg(args, "inject_depth")
                val attach = (args["attach"] as? JsonPrimitive)?.booleanOrNull
                val create = idArg.isEmpty()
                if (create && name.isEmpty()) {
                    return@tool err("missing_argument", "Provide id (update) or name (create).")
                }
                var resultId: String? = null
                settingsStore.update { s ->
                    val existing = if (create) null else
                        s.modeInjections.firstOrNull { it.id.toString() == idArg } ?: return@update s
                    val base = existing ?: PromptInjection.ModeInjection(name = name)
                    val target = base.copy(
                        name = name.ifEmpty { existing?.name.orEmpty() },
                        content = content ?: existing?.content.orEmpty(),
                        enabled = enabled,
                        priority = priority ?: existing?.priority ?: 0,
                        position = position ?: existing?.position ?: InjectionPosition.AFTER_SYSTEM_PROMPT,
                        role = role ?: existing?.role ?: me.rerere.ai.core.MessageRole.USER,
                        injectDepth = injectDepth ?: existing?.injectDepth ?: 4,
                    )
                    resultId = target.id.toString()
                    val injections = if (create) s.modeInjections + target
                    else s.modeInjections.map { if (it.id == target.id) target else it }
                    val assistants = when (attach) {
                        true -> s.assistants.map { if (it.id == assistant.id) it.copy(modeInjectionIds = it.modeInjectionIds + target.id) else it }
                        false -> s.assistants.map { if (it.id == assistant.id) it.copy(modeInjectionIds = it.modeInjectionIds - target.id) else it }
                        null -> s.assistants
                    }
                    s.copy(modeInjections = injections, assistants = assistants)
                }
                val savedId = resultId
                    ?: return@tool err("not_found", "No mode injection with id $idArg in the global list.")
                val after = resolveAssistant(settingsStore.settingsFlow.first(), assistantId) ?: assistant
                ok(
                    "detail" to JsonPrimitive(if (create) "mode injection created" else "mode injection updated"),
                    "id" to JsonPrimitive(savedId),
                    "attached" to JsonPrimitive(savedId in after.modeInjectionIds.map { it.toString() }),
                )
            },
            // ---------------------------------------------- delete_mode_injection
            tool(
                name = "delete_mode_injection",
                description = """
                    Delete a mode injection from the GLOBAL list by id. If any assistant (including this
                    one) has it attached, the attachment is removed too, so no dangling reference remains.
                """,
                properties = {
                    putJsonObject("id") {
                        put("type", "string")
                        put("description", "Uuid of the mode injection to delete.")
                    }
                },
                required = listOf("id"),
            ) { args ->
                val idArg = strArg(args, "id")?.trim().orEmpty()
                if (idArg.isEmpty()) return@tool err("missing_argument", "id is required.")
                val uuid = runCatching { Uuid.parse(idArg) }.getOrNull()
                    ?: return@tool err("bad_argument", "id is not a valid Uuid.")
                var removed = false
                settingsStore.update { s ->
                    if (s.modeInjections.none { it.id == uuid }) return@update s
                    removed = true
                    s.copy(
                        modeInjections = s.modeInjections.filterNot { it.id == uuid },
                        assistants = s.assistants.map {
                            if (uuid in it.modeInjectionIds) it.copy(modeInjectionIds = it.modeInjectionIds - uuid) else it
                        },
                    )
                }
                if (!removed) return@tool err("not_found", "No mode injection with id $idArg.")
                ok("detail" to JsonPrimitive("mode injection deleted"))
            },
        )
    }

    /** 解析 entries_json 为 RegexInjection 列表（宽松：未知字段忽略，缺省用默认值）。 */
    private fun lorebookEntriesFromJson(raw: String): List<PromptInjection.RegexInjection> {
        val arr = Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray
            ?: throw IllegalArgumentException("entries_json must be a JSON array")
        return arr.map { el ->
            val o = el as? JsonObject ?: throw IllegalArgumentException("each entry must be an object")
            PromptInjection.RegexInjection(
                name = strArg(o, "name").orEmpty(),
                content = strArg(o, "content").orEmpty(),
                keywords = stringsArg(o, "keywords") ?: emptyList(),
                useRegex = boolArg(o, "useRegex", default = false),
                caseSensitive = boolArg(o, "caseSensitive", default = false),
                scanDepth = intArg(o, "scanDepth") ?: 4,
                constantActive = boolArg(o, "constantActive", default = false),
                priority = intArg(o, "priority") ?: 0,
                position = strArg(o, "position")?.let { p ->
                    runCatching { InjectionPosition.valueOf(p) }.getOrNull()
                } ?: InjectionPosition.AFTER_SYSTEM_PROMPT,
                role = strArg(o, "role")?.let { r ->
                    runCatching { me.rerere.ai.core.MessageRole.valueOf(r) }.getOrNull()
                } ?: me.rerere.ai.core.MessageRole.USER,
                injectDepth = intArg(o, "injectDepth") ?: 4,
                enabled = boolArg(o, "enabled", default = true),
            )
        }
    }
}

/**
 * 系统提示词尾部追加的说明：告知模型本组工具能查看/修改哪些自身配置。文案刻意克制。
 * 仅当助手启用了 AssistantConfig 工具组时注入。
 */
fun assistantConfigPromptHint(): String =
    "助手配置自管理：可用 read_assistant_config 查看自己的系统提示词、技能开关、关联的模式注入/世界书；" +
        "可用 update_system_prompt、toggle_skill、upsert_lorebook、delete_lorebook、upsert_mode_injection、" +
        "delete_mode_injection 修改它们。修改立即生效并持久化；改动前先读取现状，重大改动先向用户确认。"
