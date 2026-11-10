package me.rerere.myagent.data.ai.tools

/**
 * Which local tool groups get a full schema up front, and which become searchable catalog
 * entries instead.
 *
 * ## Why this exists
 *
 * `ToolSurfaceMode.PROGRESSIVE_CATALOG` already replaces MCP schemas with a `tool_search` /
 * `tool_open` pair, but it deliberately left local tools alone ("only MCP tools — so the blast
 * radius of this phase stays small"). On a real device that left the expensive half in place:
 * one assistant enabled **61 groups / 196 tools**, every one of them serialized into every
 * request.
 *
 * Measured against agent2api's request log (26,595 requests), prompt size and answer length are
 * inversely related past a threshold — every extra kilobyte of stable schema spends context that
 * the actual conversation needs, and a large stable prefix also competes with the cache hit rate.
 *
 * ## The split
 *
 * [ALWAYS_DIRECT] are tools a turn reaches for constantly and cheaply — time, clipboard, the
 * approval pair, memory/compaction, the shell/file basics. Cataloguing them would cost a wasted
 * round-trip (`tool_search` → `tool_open` → next turn) on nearly every conversation, which is a
 * worse trade than the tokens they cost.
 *
 * Everything else is catalogued. The model still sees the full list through `tool_search`, and
 * an activated schema is injected from the following turn — the same contract MCP tools already
 * follow, so there is one mental model, not two.
 *
 * ## Stability
 *
 * The direct set is derived from the group enum, never from conversation state, so the stable
 * prefix of the prompt stays byte-identical between turns and prompt caching keeps working.
 * Adding a group to [ALWAYS_DIRECT] is a deliberate prompt-prefix change; that is expected and
 * fine, but it should be a decision, not a side effect.
 */
object LocalToolGroupPolicy {

    /**
     * Groups that keep a full schema on every request.
     *
     * Chosen for "used in most turns, and cheap": each is 1-3 tools. Anything larger or more
     * situational belongs in the catalog — the model can still find it, it just has to say so.
     */
    val ALWAYS_DIRECT: Set<LocalToolOption> = setOf(
        // Orientation: nearly every turn benefits from knowing the clock.
        LocalToolOption.TimeInfo,
        // Small, universally useful quick actions.
        LocalToolOption.Clipboard,
        LocalToolOption.JavascriptEngine,
        LocalToolOption.Battery,
        // The two human-input tools: they are the only way to ask the user something, so a
        // discovery round-trip before "may I ask a question" is pure friction.
        LocalToolOption.AskUser,
        LocalToolOption.Secrets,
        // Shell/file basics — the backbone of any real task. (Workspace, memory and
        // compaction tools are added by ChatService outside this option list, so they are
        // unaffected by this policy and always stay direct.)
        LocalToolOption.Files,
        LocalToolOption.Ssh,
        // Small orientation/status helpers used reactively all the time.
        LocalToolOption.StorageInfo,
        LocalToolOption.WifiInfo,
    )

    /** True when [option] may be replaced by a catalog entry instead of a full schema. */
    fun isCatalogued(option: LocalToolOption): Boolean = option !in ALWAYS_DIRECT

    /**
     * Split [options] into what stays direct and what the catalog will hold.
     *
     * Order is preserved on both sides so the stable prompt prefix does not depend on set
     * iteration order.
     */
    fun partition(options: List<LocalToolOption>): Partition = Partition(
        direct = options.filterNot(::isCatalogued),
        catalogued = options.filter(::isCatalogued),
    )

    data class Partition(
        val direct: List<LocalToolOption>,
        val catalogued: List<LocalToolOption>,
    )
}
