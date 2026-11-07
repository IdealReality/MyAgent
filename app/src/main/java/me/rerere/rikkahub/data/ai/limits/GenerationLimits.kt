package me.rerere.rikkahub.data.ai.limits

/**
 * Authoritative defaults and clamp helpers for the app-wide generation limits: the per-turn
 * wall-clock budget and the tool-call step cap. These used to live in the Termux settings
 * store ([me.rerere.rikkahub.data.preferences.TermuxDefaults]); when the Termux integration
 * was removed they moved here so the limits stay configurable without that dependency.
 *
 * Persistence is [me.rerere.rikkahub.data.datastore.Settings] (`turnBudgetMs` /
 * `maxToolSteps`), surfaced on Settings -> Model -> Generation limits.
 */
object GenerationLimits {

    // --- Per-turn wall-clock budget (app-wide) ---------------------------------------------
    /** Default per-turn wall-clock budget in ms. */
    const val DEFAULT_TURN_BUDGET_MS = 30L * 60L * 1_000L   // 30 min
    const val MIN_TURN_BUDGET_MS     =  1L * 60L * 1_000L   //  1 min
    const val MAX_TURN_BUDGET_MS     = 720L * 60L * 1_000L  // 12 h

    // --- Per-turn tool-call step cap (app-wide) --------------------------------------------
    /** Backstop against runaway loops; the wall-clock budget is the primary limit. */
    const val DEFAULT_MAX_TOOL_STEPS = 90
    const val MIN_MAX_TOOL_STEPS     =  1
    const val MAX_MAX_TOOL_STEPS     = 1500

    // --- Clamp helpers ---------------------------------------------------------------------

    fun clampTurnBudgetMs(ms: Long): Long =
        ms.coerceIn(MIN_TURN_BUDGET_MS, MAX_TURN_BUDGET_MS)

    fun clampMaxToolSteps(steps: Int): Int =
        steps.coerceIn(MIN_MAX_TOOL_STEPS, MAX_MAX_TOOL_STEPS)
}
