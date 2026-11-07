package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

// internal (not private) because ChatService imports TOOL_CATALOG_MAX_SUMMARY_CHARS to build
// catalog summaries of the same length this file enforces when it serialises search hits.
internal const val TOOL_CATALOG_MAX_SEARCH_RESULTS = 8
internal const val TOOL_CATALOG_MAX_OPEN_PER_CALL = 4
// Raised from 6. The old value equalled the count a session pre-loads, so tool_open could
// never activate anything: every call came back rejectedOverCap and the model was left
// improvising without the tool it had just found. 20 leaves room for the pre-loaded set plus
// the extra tools a long session actually reaches for.
internal const val TOOL_CATALOG_MAX_ACTIVE_SCHEMAS = 20

/**
 * How many search hits are activated without an explicit `tool_open`.
 *
 * Two, not more: each activated schema is real tokens on every subsequent request, and a
 * search that returns eight plausible names rarely means "I want all eight". The cap also
 * keeps `tool_search` inside the same per-call activation budget `tool_open` obeys.
 */
internal const val TOOL_CATALOG_AUTO_ACTIVATE_LIMIT = 2
internal const val TOOL_CATALOG_MAX_SUMMARY_CHARS = 180

/**
 * How tools are surfaced to the model.
 *
 * [DIRECT] injects every tool's schema up front; [PROGRESSIVE_CATALOG] injects only
 * the search/open pair, so the request stays small until the model explicitly opts in.
 */
enum class ToolSurfaceMode { DIRECT, PROGRESSIVE_CATALOG }

/** Where a catalogued tool comes from; used for scoring and serialized in search hits. */
enum class ToolCatalogSource { LOCAL, MCP }

/**
 * One catalog entry.
 *
 * [summary] is authored by the caller. This file never truncates the stored value;
 * it only enforces the 180-char cap at serialization time. [tool] is carried so the
 * caller can materialize a real schema once the name is activated — it is never
 * serialized here (it contains lambdas).
 */
data class ToolCatalogEntry(
    val name: String,
    val summary: String,
    val source: ToolCatalogSource,
    val tool: Tool,
)

/**
 * Immutable, order-preserving catalog.
 *
 * Why a class rather than a bare list: O(1) name lookup plus the scoring logic lives
 * next to the data it ranks. Fully immutable, so no locking is needed.
 */
class ToolCatalog(entries: List<ToolCatalogEntry>) {
    val entries: List<ToolCatalogEntry> = entries.toList()
    private val byName: Map<String, ToolCatalogEntry> = entries.associateBy { it.name }
    val size: Int get() = entries.size

    fun entry(name: String): ToolCatalogEntry? = byName[name]

    fun search(query: String, limit: Int = TOOL_CATALOG_MAX_SEARCH_RESULTS): List<ToolCatalogEntry> =
        matchAll(query).take(limit.coerceIn(1, TOOL_CATALOG_MAX_SEARCH_RESULTS))
}

/**
 * Result of one `tool_open` call, bucketed so the model can tell exactly why each
 * requested name did or did not become active.
 */
data class ToolOpenOutcome(
    val activated: List<String>,
    val alreadyActive: List<String>,
    val unknown: List<String>,
    val rejectedOverCap: List<String>,
    val activeCount: Int,
) {
    fun toJson(): String {
        val note = buildString {
            append("Activated schemas become callable from your NEXT turn, not this one.")
            if (activated.isEmpty()) {
                if (unknown.isEmpty() && alreadyActive.isEmpty() && rejectedOverCap.isEmpty()) {
                    append(" No tool names were provided, so nothing was activated. Call tool_search")
                    append(" first to get exact names, then pass them in the names array.")
                } else {
                    append(" Nothing was activated. Retry: run tool_search first to get exact names,")
                    append(" then call tool_open with those names.")
                }
            }
        }
        return Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("activated", JsonArray(activated.map { JsonPrimitive(it) }))
                put("alreadyActive", JsonArray(alreadyActive.map { JsonPrimitive(it) }))
                put("unknown", JsonArray(unknown.map { JsonPrimitive(it) }))
                put("rejectedOverCap", JsonArray(rejectedOverCap.map { JsonPrimitive(it) }))
                put("activeCount", activeCount)
                put("note", note)
            },
        )
    }
}

/** A ranked search hit — name + summary + source only, deliberately schema-free. */
data class ToolSearchHit(val name: String, val summary: String, val source: ToolCatalogSource)

data class ToolSearchOutcome(
    val hits: List<ToolSearchHit>,
    val total: Int,
    val truncated: Boolean,
    /**
     * Names activated as a side effect of this search.
     *
     * Searching for a tool and then having to call `tool_open` before it can be used costs a
     * whole round-trip for information the search already had. The top hits are therefore
     * activated immediately; `tool_open` remains for the cases the search could not predict
     * (and for re-activating after the cap evicted something).
     */
    val autoActivated: List<String> = emptyList(),
) {
    fun toJson(): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("total", total)
            put("returned", hits.size)
            put("truncated", truncated)
            put("results", JsonArray(hits.map { hit ->
                buildJsonObject {
                    put("name", hit.name)
                    put("summary", hit.summary.take(TOOL_CATALOG_MAX_SUMMARY_CHARS))
                    put("source", hit.source.name)
                }
            }))
            if (autoActivated.isNotEmpty()) {
                put("autoActivated", JsonArray(autoActivated.map { JsonPrimitive(it) }))
                put(
                    "note",
                    "The top matches were activated automatically and are callable from your " +
                        "NEXT turn. Use tool_open only for names not activated here.",
                )
            }
            if (hits.isEmpty()) {
                put("note", "No tools matched. Try different keywords, or use broader terms.")
            }
        },
    )
}

/**
 * Session-scoped, thread-safe set of activated tool names. Never persisted — it lives
 * only for the lifetime of the conversation, which is exactly the "progressive"
 * contract (activation is per-session state, not data).
 */
/**
 * Result of one tool_close call - the inverse bookkeeping of [ToolOpenOutcome], so the
 * model can tell "released", "you never opened that" and "no such tool" apart.
 *
 * 2026-11-07: this type did not exist. tool_close had no schema, no method and no result
 * type; only the open half of the progressive-catalog contract was implemented, which made
 * the cap a one-way ratchet.
 */
data class ToolCloseOutcome(
    val closed: List<String>,
    val notActive: List<String>,
    val unknown: List<String>,
    val activeCount: Int,
) {
    fun toJson(): String = Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("closed", JsonArray(closed.map { JsonPrimitive(it) }))
            put("notActive", JsonArray(notActive.map { JsonPrimitive(it) }))
            put("unknown", JsonArray(unknown.map { JsonPrimitive(it) }))
            put("activeCount", activeCount)
            put(
                "note",
                "Released schemas stop being sent from the next turn; the slots are free now.",
            )
        },
    )
}

class ToolActivationState {
    private val lock = Any()
    private val activeNames = LinkedHashSet<String>()

    /** Snapshot in insertion order (fresh copy, safe for the caller to mutate). */
    fun active(): Set<String> = synchronized(lock) { activeNames.toSet() }

    fun clear() = synchronized(lock) { activeNames.clear() }

    /** Drop active names that fell out of the catalog (e.g. an MCP server disconnected). */
    fun retain(knownNames: Set<String>) = synchronized(lock) { activeNames.retainAll(knownNames) }

    /**
     * Activate [names] in the order given.
     *
     * Duplicate handling: the first occurrence is the one that activates; any later
     * occurrence is reported in [ToolOpenOutcome.alreadyActive], because by then the
     * name is already (or just became) active. Unknown names never consume the per-call
     * budget, and the per-call budget is applied first-come-first-served.
     */
    fun activate(catalog: ToolCatalog, names: List<String>): ToolOpenOutcome = synchronized(lock) {
        val activated = mutableListOf<String>()
        val alreadyActive = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        val rejectedOverCap = mutableListOf<String>()
        var openedThisCall = 0
        for (name in names) {
            if (catalog.entry(name) == null) {
                unknown.add(name)
                continue
            }
            if (name in activeNames) {
                alreadyActive.add(name)
                continue
            }
            if (openedThisCall >= TOOL_CATALOG_MAX_OPEN_PER_CALL) {
                rejectedOverCap.add(name)
                continue
            }
            if (activeNames.size >= TOOL_CATALOG_MAX_ACTIVE_SCHEMAS) {
                rejectedOverCap.add(name)
                continue
            }
            activeNames.add(name)
            activated.add(name)
            openedThisCall++
        }
        ToolOpenOutcome(
            activated = activated,
            alreadyActive = alreadyActive,
            unknown = unknown,
            rejectedOverCap = rejectedOverCap,
            activeCount = activeNames.size,
        )
    }

    /**
     * Release the names in [names] - the inverse of activate().
     *
     * 2026-11-07: both this method and the tool_close registration were missing.
     * ToolCloseOutcome existed with a toJson() but had no producer, so an activated
     * schema could never be released short of a process restart, and the cap could only
     * ever fill up.
     */
    fun close(catalog: ToolCatalog, names: List<String>): ToolCloseOutcome = synchronized(lock) {
        val closed = mutableListOf<String>()
        val notActive = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        for (name in names) {
            if (catalog.entry(name) == null) {
                unknown.add(name)
                continue
            }
            if (activeNames.remove(name)) {
                closed.add(name)
            } else {
                notActive.add(name)
            }
        }
        ToolCloseOutcome(
            closed = closed,
            notActive = notActive,
            unknown = unknown,
            activeCount = activeNames.size,
        )
    }
}

/**
 * The two catalogue tools that replace a full schema dump.
 *
 * Returns exactly two tools: `tool_search` (find names, no schema) and `tool_open`
 * (activate names, no schema). Real schemas surface only on the model's next turn,
 * which is what keeps the request small.
 */
fun buildToolCatalogTools(
    catalog: ToolCatalog,
    activation: ToolActivationState,
): List<Tool> = listOf(
    Tool(
        name = "tool_search",
        description = """
            Search the tool catalog by keyword and return matching tool names with short
            summaries. Use this whenever you are unsure which tools exist. It returns
            names and summaries — never full schemas. The strongest matches are activated
            automatically (see the autoActivated field); anything else needs tool_open.
            Activation takes effect on your NEXT turn, not the current one.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Keywords to match against tool names, summaries, and sources.")
                    })
                },
                required = listOf("query"),
            )
        },
        execute = {
            val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val all = catalog.matchAll(query)
            val limited = all.take(TOOL_CATALOG_MAX_SEARCH_RESULTS)
            // Activate the strongest hits immediately. The caller then needs only one
            // round-trip (search) instead of two (search, then open) for the common case of
            // "I know roughly what I need and the search found it".
            val activationOutcome = activation.activate(
                catalog = catalog,
                names = limited.take(TOOL_CATALOG_AUTO_ACTIVATE_LIMIT).map { e -> e.name },
            )
            val outcome = ToolSearchOutcome(
                hits = limited.map { e -> ToolSearchHit(e.name, e.summary, e.source) },
                total = all.size,
                truncated = all.size > limited.size,
                autoActivated = activationOutcome.activated,
            )
            listOf(UIMessagePart.Text(outcome.toJson()))
        },
    ),
    Tool(
        name = "tool_open",
        description = """
            Activate tools by name so their schemas become callable. Names must be exact
            matches returned by tool_search. Activation takes effect on your NEXT turn,
            not the current one. Do not re-open a tool that is already active.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("names", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Exact tool names to activate, as returned by tool_search.")
                    })
                },
                required = listOf("names"),
            )
        },
        execute = {
            val names = parseNames(it)
            listOf(UIMessagePart.Text(activation.activate(catalog, names).toJson()))
        },
    ),
    Tool(
        name = "tool_close",
        description = """
            Release tools you no longer need, freeing a slot for this conversation. Use this
            when you are done with a capability and the active set is getting large, or before
            tool_open if the cap is already held. The slot frees NOW, so a tool_open in the same
            turn can reuse it.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("names", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Exact tool names to release.")
                    })
                },
                required = listOf("names"),
            )
        },
        execute = {
            val names = parseNames(it)
            listOf(UIMessagePart.Text(activation.close(catalog, names).toJson()))
        },
    ),
)

/**
 * Full, untruncated, ranked match list — the single home of the scoring logic.
 *
 * [ToolCatalog.search] truncates it, and `tool_search` reuses it to compute the untruncated
 * `total` without duplicating the scoring. P2-05 widened it from `private` to `internal` for one
 * more caller: [LocalToolPalette], the expert editor's palette. The 8-hit cap is a *response*
 * budget for a model, not a bound a human scrolling a list should inherit, so the palette ranks
 * with this and applies its own (larger) bound instead. Nothing about the function changed — the
 * body, the ordering and the tie-break are exactly what they were.
 */
internal fun ToolCatalog.matchAll(query: String): List<ToolCatalogEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val tokens = q.split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }
    return entries
        .mapNotNull { entry ->
            val score = matchScore(q, tokens, entry)
            if (score > 0) entry to score else null
        }
        .sortedWith(compareByDescending<Pair<ToolCatalogEntry, Int>> { it.second }.thenBy { it.first.name })
        .map { it.first }
}

private fun matchScore(query: String, tokens: List<String>, entry: ToolCatalogEntry): Int {
    val name = entry.name.lowercase()
    val summary = entry.summary.lowercase()
    val sourceName = entry.source.name.lowercase()
    var score = 0
    if (query.isNotEmpty()) {
        if (name == query) {
            score += 100
        } else if (name.contains(query)) {
            score += 40
        }
    }
    for (token in tokens) {
        if (name.contains(token)) score += 10
        if (summary.contains(token)) score += 4
        if (sourceName.contains(token)) score += 2
    }
    return score
}

private fun parseNames(element: JsonElement): List<String> {
    val namesElement = element.jsonObject["names"] ?: return emptyList()
    if (namesElement !is JsonArray) return emptyList()
    return namesElement
        .mapNotNull { item -> (item as? JsonPrimitive)?.contentOrNull }
        .filter { it.isNotBlank() }
}
