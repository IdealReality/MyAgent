package me.rerere.myagent.data.ai.transformers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessage
import java.io.File

/**
 * Injects a compact, read-only snapshot of the tabletop state that the `trpg` plugin keeps
 * in its key/value store (`files/plugins/.kv/trpg.json`).
 *
 * Why this exists at all: the plugin is the only writer of HP/SAN/clue/clock state, but the
 * model can only see that state when it calls `trpg_sheet` / `trpg_scene`. In a long session
 * it forgets to, and then narrates a character at full health who is at 3/12. Putting the
 * numbers in front of it every turn — in the *volatile* prompt section, so the stable prefix
 * still caches — is the difference between "state that exists" and "state that is used".
 *
 * Zero-noise by construction: nothing is injected unless the assistant opted in AND the
 * plugin has actually written state. A session that never started costs nothing.
 *
 * The block is marked clearly as a device-side snapshot and tells the model not to echo it;
 * models otherwise copy structured annotations straight into their prose.
 */
class TrpgStateReminderTransformer(
    private val pluginsDir: File,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) : InputMessageTransformer {

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.assistant.trpgStateInjection) return messages
        val block = buildTrpgStateBlock(
            kvFile = File(File(pluginsDir, KV_DIR_NAME), "$DEFAULT_PLUGIN_ID.json"),
            maxChars = maxChars,
        ) ?: return messages
        return injectContextReminder(messages, block)
    }

    companion object {
        const val DEFAULT_PLUGIN_ID = "trpg"
        const val KV_DIR_NAME = ".kv"
        /** Roughly 300 CJK characters — one screen, and cheap against a cached prefix. */
        const val DEFAULT_MAX_CHARS = 1200
    }
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Pure function over the plugin's KV file: returns the reminder block, or null when there is
 * no tabletop state worth injecting. Kept file-based (not store-based) so the transformer
 * needs no dependency on the plugin registry and can run on any dispatcher.
 */
internal fun buildTrpgStateBlock(kvFile: File, maxChars: Int): String? {
    if (!kvFile.isFile) return null
    val kv = runCatching { lenientJson.parseToJsonElement(kvFile.readText()).jsonObject }
        .getOrNull() ?: return null

    fun raw(key: String): String? =
        (kv[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    val scene = raw("trpg.scene")?.let { runCatching { lenientJson.parseToJsonElement(it).jsonObject }.getOrNull() }
    val sheets = raw("trpg.sheets")?.let { runCatching { lenientJson.parseToJsonElement(it).jsonObject }.getOrNull() }
    val rolls = raw("trpg.rolls")?.let { runCatching { lenientJson.parseToJsonElement(it).jsonArray }.getOrNull() }

    // Priority order, most-important-first, because the budget may cut the tail: the numbers
    // that prevent contradictions (bars, clocks, where we are) go in before the long-form
    // prose (clues, facts), which the model can always re-read with trpg_scene status.
    val lines = buildList {
        scene?.let(::renderSceneHeader)?.let(::add)
        scene?.let(::renderClockLine)?.let(::add)
        sheets?.forEach { (name, value) ->
            (value as? JsonObject)?.let { add(renderSheetLine(name, it)) }
        }
        scene?.let(::renderSceneDetail)?.let(::add)
        rolls?.lastOrNull()?.let { renderRollLine(it as? JsonObject) }?.let(::add)
    }

    if (lines.isEmpty()) return null

    val header = "<trpg_state>\n"
    val footer = "</trpg_state>\n" + SNAPSHOT_FOOTER
    val body = StringBuilder()
    var truncated = false
    for (line in lines) {
        val candidate = body.length + line.length + 2
        if (header.length + candidate + footer.length > maxChars && body.isNotEmpty()) {
            truncated = true
            break
        }
        body.append(line).append('\n')
    }
    if (truncated) {
        body.append("…（状态过长已截断，完整内容用 trpg_scene status 查看）\n")
    }
    return header + body + footer
}

private const val SNAPSHOT_FOOTER =
    "以上是设备侧的只读状态快照，用于保持前后一致：叙述与它冲突时以它为准。" +
        "需要改变 HP/SAN/线索/钟时调用 trpg_sheet / trpg_scene 落地；不要在回复里复述本块。"

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.num(key: String): Double? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

/** "场景: … | 在场: …" — short, high-value; never cut by the budget if anything survives. */
private fun renderSceneHeader(scene: JsonObject): String? {
    val parts = mutableListOf<String>()
    scene.str("campaign")?.let { parts += it }
    scene.num("act")?.takeIf { it > 0 }?.let { parts += "第${it.toInt()}幕" }
    scene.str("location")?.let { parts += "@$it" }
    scene.str("time")?.let { parts += "🕒 $it" }
    val npcs = (scene["npcs"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    if (parts.isEmpty() && npcs.isEmpty()) return null
    val line = StringBuilder()
    if (parts.isNotEmpty()) line.append("场景: ").append(parts.joinToString(" · "))
    if (npcs.isNotEmpty()) {
        if (line.isNotEmpty()) line.append(" | ")
        line.append("在场: ").append(npcs.take(6).joinToString("、"))
    }
    return line.toString()
}

/** Also short: a full clock is the one thing the model must not overlook. */
private fun renderClockLine(scene: JsonObject): String? {
    val clocks = scene["clocks"] as? JsonObject ?: return null
    if (clocks.isEmpty()) return null
    val rendered = clocks.mapNotNull { (name, value) ->
        val obj = value as? JsonObject ?: return@mapNotNull null
        val current = obj.num("value")?.toInt() ?: 0
        val max = obj.num("max")?.toInt() ?: 6
        "$name $current/$max" + if (current >= max) "（已满）" else ""
    }
    if (rendered.isEmpty()) return null
    return "钟: " + rendered.joinToString("  ")
}

/** The long-form tail: clues and facts, the first thing to be dropped when over budget. */
private fun renderSceneDetail(scene: JsonObject): String? {
    val clues = (scene["clues"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    val facts = (scene["facts"] as? JsonArray)?.mapNotNull { entry ->
        when (entry) {
            is JsonObject -> entry.str("text")
            is JsonPrimitive -> entry.contentOrNull
            else -> null
        }
    }.orEmpty()
    val chunks = mutableListOf<String>()
    if (clues.isNotEmpty()) chunks += "线索: " + clues.takeLast(5).joinToString("；")
    if (facts.isNotEmpty()) chunks += "最近事实: " + facts.takeLast(5).joinToString("；")
    return chunks.takeIf { it.isNotEmpty() }?.joinToString(" | ")
}

private fun renderSheetLine(name: String, sheet: JsonObject): String {
    val stats = mutableListOf<String>()
    listOf("hp", "mp", "san").forEach { key ->
        val bar = sheet[key] as? JsonObject ?: return@forEach
        val cur = bar.num("cur")?.toInt() ?: return@forEach
        val max = bar.num("max")?.toInt() ?: return@forEach
        stats += "${key.uppercase()} $cur/$max"
    }
    sheet.num("luck")?.let { stats += "LUCK ${it.toInt()}" }
    val status = (sheet["status"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    val items = (sheet["items"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    val line = StringBuilder(name)
    if (stats.isNotEmpty()) line.append(": ").append(stats.joinToString(" "))
    if (status.isNotEmpty()) line.append(" | 状态: ").append(status.joinToString("、"))
    if (items.isNotEmpty()) {
        line.append(" | 物品: ").append(
            items.take(6).joinToString("、") + if (items.size > 6) " 等${items.size}件" else ""
        )
    }
    return line.toString()
}

private fun renderRollLine(roll: JsonObject?): String? {
    if (roll == null) return null
    val label = roll.str("label") ?: "掷骰"
    val target = roll.num("target")?.toInt()
    val value = roll.num("roll")?.toInt() ?: roll.num("total")?.toInt() ?: return null
    val level = roll.str("level") ?: roll.str("verdict")
    val tail = buildString {
        if (target != null) append("目标 $target → ")
        append(value)
        if (level != null) append(" $level")
    }
    return "最近掷骰: $label $tail"
}
