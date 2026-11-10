package me.rerere.myagent.plugin

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.myagent.data.datastore.DisplaySetting
import me.rerere.myagent.data.datastore.LoadingIndicatorStyle
import me.rerere.myagent.data.datastore.PopupMenuStyle
import me.rerere.myagent.data.datastore.Settings
import me.rerere.myagent.data.datastore.SettingsHubStyle
import me.rerere.myagent.data.datastore.SettingsStore
import me.rerere.myagent.data.datastore.ToolCallStyle

/**
 * 插件访问「应用状态」的唯一入口。
 *
 * 实现放在 DI（需要 SettingsStore 这类带 Android 依赖的对象）；注册表/沙箱只认这个接口，
 * 所以 JVM 测试可以塞一个假实现，不需要 Context。
 *
 * 约定：两个方法都返回 JSON 字符串，失败返回 `{"error": "..."}`，永不抛出 —— 抛异常会让
 * QuickJS 侧拿到一个原生异常而不是可读的错误。
 */
interface PluginAppHost {
    fun read(op: String, argsJson: String): String
    fun write(op: String, argsJson: String): String
}

/**
 * 应用状态的可写子集。
 *
 * 只暴露「界面/显示」这类可逆、无破坏性的键：插件能改布局、字号、气泡、代码块行为，
 * 但不能改模型、API key、提供商、工作区路径 —— 那些要用户自己动手，或走有审计的工具。
 * 每个键都在 [describe] 与 [apply] 两边同时登记，加字段时两处一起改（测试会盯住）。
 */
object PluginDisplaySettings {

    private val BOOL_KEYS = listOf(
        "cleanLayout" to { d: DisplaySetting -> d.cleanLayout },
        "cleanLayoutGlass" to { d: DisplaySetting -> d.cleanLayoutGlass },
        "drawerCapsules" to { d: DisplaySetting -> d.drawerCapsules },
        "showUserAvatar" to { d: DisplaySetting -> d.showUserAvatar },
        "showAssistantBubble" to { d: DisplaySetting -> d.showAssistantBubble },
        "showModelIcon" to { d: DisplaySetting -> d.showModelIcon },
        "showModelName" to { d: DisplaySetting -> d.showModelName },
        "showDateTimeInMessage" to { d: DisplaySetting -> d.showDateTimeInMessage },
        "showTokenUsage" to { d: DisplaySetting -> d.showTokenUsage },
        "showThinkingContent" to { d: DisplaySetting -> d.showThinkingContent },
        "autoCloseThinking" to { d: DisplaySetting -> d.autoCloseThinking },
        "foldProcessNarration" to { d: DisplaySetting -> d.foldProcessNarration },
        "showMessageJumper" to { d: DisplaySetting -> d.showMessageJumper },
        "messageJumperOnLeft" to { d: DisplaySetting -> d.messageJumperOnLeft },
        "codeBlockAutoWrap" to { d: DisplaySetting -> d.codeBlockAutoWrap },
        "codeBlockAutoCollapse" to { d: DisplaySetting -> d.codeBlockAutoCollapse },
        "inlineWebPreview" to { d: DisplaySetting -> d.inlineWebPreview },
        "useAppIconStyleLoadingIndicator" to { d: DisplaySetting -> d.useAppIconStyleLoadingIndicator },
    )

    private val FLOAT_KEYS = listOf(
        "bubbleOpacity" to { d: DisplaySetting -> d.bubbleOpacity },
        "cleanLayoutTopOpacity" to { d: DisplaySetting -> d.cleanLayoutTopOpacity },
        "cleanLayoutBottomOpacity" to { d: DisplaySetting -> d.cleanLayoutBottomOpacity },
        "cleanLayoutTopHeight" to { d: DisplaySetting -> d.cleanLayoutTopHeight },
        "cleanLayoutBottomPadding" to { d: DisplaySetting -> d.cleanLayoutBottomPadding },
        "fontSizeRatio" to { d: DisplaySetting -> d.fontSizeRatio },
    )

    private val INT_KEYS = listOf(
        "thinkingStepsVisibleCount" to { d: DisplaySetting -> d.thinkingStepsVisibleCount },
    )

    /** 枚举键：按 name 读写（大小写不敏感），未知值退回当前值。 */
    private val ENUM_KEYS = listOf<Triple<String, (DisplaySetting) -> String, (DisplaySetting, String) -> DisplaySetting>>(
        Triple(
            "toolCallStyle",
            { d -> d.toolCallStyle.name },
            { d, raw -> ToolCallStyle.entries.byName(raw)?.let { d.copy(toolCallStyle = it) } ?: d },
        ),
        Triple(
            "popupMenuStyle",
            { d -> d.popupMenuStyle.name },
            { d, raw -> PopupMenuStyle.entries.byName(raw)?.let { d.copy(popupMenuStyle = it) } ?: d },
        ),
        Triple(
            "settingsHubStyle",
            { d -> d.settingsHubStyle.name },
            { d, raw -> SettingsHubStyle.entries.byName(raw)?.let { d.copy(settingsHubStyle = it) } ?: d },
        ),
        Triple(
            "loadingIndicatorStyle",
            { d -> d.loadingIndicatorStyle?.name ?: "" },
            { d, raw ->
                LoadingIndicatorStyle.entries.byName(raw)?.let { d.copy(loadingIndicatorStyle = it) } ?: d
            },
        ),
    )

    private fun <E : Enum<E>> List<E>.byName(raw: String): E? =
        firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

    private val ENUM_BOOL_FIELDS = ENUM_KEYS.map { it.first }.toSet()

    /** 可写键名（暴露给 `ctx.app.info()` 的提示，也用于校验）。 */
    val writableKeys: Set<String> =
        (BOOL_KEYS.map { it.first } + FLOAT_KEYS.map { it.first } + INT_KEYS.map { it.first } + ENUM_BOOL_FIELDS).toSet()

    /** 当前显示设置 → JSON（只含白名单键）。 */
    fun describe(setting: DisplaySetting): JsonObject = buildJsonObject {
        BOOL_KEYS.forEach { (key, get) -> put(key, get(setting)) }
        FLOAT_KEYS.forEach { (key, get) -> put(key, get(setting)) }
        INT_KEYS.forEach { (key, get) -> put(key, get(setting)) }
        ENUM_KEYS.forEach { (key, get, _) -> put(key, get(setting)) }
    }

    /**
     * 把一段 JSON 补丁合进显示设置。
     *
     * 未知键、类型不符、超范围的值一律忽略（不做部分失败报错）—— 调用方拿 [appliedKeys]
     * 就知道哪些生效了。浮点会 clamp 到合理区间，避免插件把字号写成 0 或 1e9 之后界面不可用。
     */
    fun apply(setting: DisplaySetting, patch: JsonObject): Pair<DisplaySetting, List<String>> {
        var next = setting
        val applied = ArrayList<String>()

        BOOL_KEYS.forEach { (key, get) ->
            val value = (patch[key] as? JsonPrimitive)?.booleanOrNull ?: return@forEach
            if (get(next) == value) return@forEach
            next = when (key) {
                "cleanLayout" -> next.copy(cleanLayout = value)
                "cleanLayoutGlass" -> next.copy(cleanLayoutGlass = value)
                "drawerCapsules" -> next.copy(drawerCapsules = value)
                "showUserAvatar" -> next.copy(showUserAvatar = value)
                "showAssistantBubble" -> next.copy(showAssistantBubble = value)
                "showModelIcon" -> next.copy(showModelIcon = value)
                "showModelName" -> next.copy(showModelName = value)
                "showDateTimeInMessage" -> next.copy(showDateTimeInMessage = value)
                "showTokenUsage" -> next.copy(showTokenUsage = value)
                "showThinkingContent" -> next.copy(showThinkingContent = value)
                "autoCloseThinking" -> next.copy(autoCloseThinking = value)
                "foldProcessNarration" -> next.copy(foldProcessNarration = value)
                "showMessageJumper" -> next.copy(showMessageJumper = value)
                "messageJumperOnLeft" -> next.copy(messageJumperOnLeft = value)
                "codeBlockAutoWrap" -> next.copy(codeBlockAutoWrap = value)
                "codeBlockAutoCollapse" -> next.copy(codeBlockAutoCollapse = value)
                "inlineWebPreview" -> next.copy(inlineWebPreview = value)
                "useAppIconStyleLoadingIndicator" -> next.copy(useAppIconStyleLoadingIndicator = value)
                else -> next
            }
            applied += key
        }

        FLOAT_KEYS.forEach { (key, get) ->
            val value = (patch[key] as? JsonPrimitive)?.floatOrNull ?: return@forEach
            if (!value.isFinite()) return@forEach
            val before = get(next)
            next = when (key) {
                "bubbleOpacity" -> next.copy(bubbleOpacity = value.coerceIn(0f, 1f))
                "cleanLayoutTopOpacity" -> next.copy(cleanLayoutTopOpacity = value.coerceIn(0f, 1f))
                "cleanLayoutBottomOpacity" -> next.copy(cleanLayoutBottomOpacity = value.coerceIn(0f, 1f))
                "cleanLayoutTopHeight" -> next.copy(cleanLayoutTopHeight = value.coerceIn(40f, 72f))
                "cleanLayoutBottomPadding" -> next.copy(cleanLayoutBottomPadding = value.coerceIn(0f, 24f))
                "fontSizeRatio" -> next.copy(fontSizeRatio = value.coerceIn(0.7f, 1.6f))
                else -> next
            }
            if (get(next) != before) applied += key
        }

        INT_KEYS.forEach { (key, _) ->
            val value = (patch[key] as? JsonPrimitive)?.intOrNull ?: return@forEach
            next = next.copy(thinkingStepsVisibleCount = value.coerceIn(0, 20))
            applied += key
        }

        ENUM_KEYS.forEach { (key, _, setter) ->
            val raw = (patch[key] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            val before = next
            next = setter(next, raw)
            if (next != before) applied += key
        }

        return next to applied
    }
}

/**
 * `ctx.app` 的真实实现：落在 SettingsStore 上。
 *
 * 同步签名是 JS 桥的硬要求（QuickJS 的宿主回调不能挂起），所以内部 runBlocking。
 * 调用发生在插件自己的短命线程上（见 PluginJsRuntime 的线程亲和说明），不会卡 UI。
 *
 * 只认白名单：`display` 走 [PluginDisplaySettings]；其余键（模型、key、提供商、
 * 工作区路径）故意不在这里 —— 那些要么用户自己改，要么走有审计的宿主工具。
 */
class SettingsPluginAppHost(
    private val settingsStore: SettingsStore,
    private val json: Json,
) : PluginAppHost {

    override fun read(op: String, argsJson: String): String = runCatching {
        val settings = settingsStore.settingsNow()
        val payload = when (op) {
            "display" -> buildJsonObject {
                put("display", PluginDisplaySettings.describe(settings.displaySetting))
                put("writable_keys", PluginDisplaySettings.writableKeys.joinToString(","))
            }

            "assistants" -> buildJsonObject {
                put(
                    "assistants",
                    buildJsonArray {
                        settings.assistants.forEach { assistant ->
                            add(
                                buildJsonObject {
                                    put("id", assistant.id.toString())
                                    put("name", assistant.name)
                                    put("model_id", assistant.chatModelId?.toString() ?: "")
                                    put("system_prompt_chars", assistant.systemPrompt.length)
                                }
                            )
                        }
                    },
                )
            }

            else -> error("unknown app.read op '$op' (expected display|assistants)")
        }
        okJson(payload)
    }.getOrElse { failJson(it) }

    override fun write(op: String, argsJson: String): String = runCatching {
        val payload = when (op) {
            "display" -> {
                val patch = json.parseToJsonElement(argsJson).jsonObject
                val applied = runBlocking {
                    var keys: List<String> = emptyList()
                    settingsStore.update { settings ->
                        val (next, changed) = PluginDisplaySettings.apply(settings.displaySetting, patch)
                        keys = changed
                        settings.copy(displaySetting = next)
                    }
                    keys
                }
                buildJsonObject {
                    put("applied", applied.joinToString(","))
                    put("display", PluginDisplaySettings.describe(settingsStore.settingsNow().displaySetting))
                }
            }

            else -> error("unknown app.write op '$op' (expected display)")
        }
        okJson(payload)
    }.getOrElse { failJson(it) }

    private fun SettingsStore.settingsNow(): Settings = runBlocking { settingsFlow.first() }

    /** 与 `__omp_unwrap` 对齐的 envelope：`{ok, result}` / `{ok:false, error}`。 */
    private fun okJson(payload: kotlinx.serialization.json.JsonElement): String = buildJsonObject {
        put("ok", true)
        put("result", payload)
    }.toString()

    private fun failJson(error: Throwable): String = buildJsonObject {
        put("ok", false)
        put("error", error.message ?: error::class.simpleName ?: "failed")
    }.toString()
}