package me.rerere.rikkahub.data.ai.limits

import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * App-wide @Volatile runtime holder for tool execution limits that span all tool families.
 * Holds the per-turn wall-clock budget and the per-turn tool-call step cap.
 *
 * Values are seeded and kept live by [GenerationLimitsPreferences]; non-suspend callers
 * (tool execution, the generation loop) read them without needing a coroutine context.
 */
object ToolRuntimeLimits {
    @Volatile var turnBudgetMs: Long = GenerationLimits.DEFAULT_TURN_BUDGET_MS

    @Volatile var maxToolSteps: Int = GenerationLimits.DEFAULT_MAX_TOOL_STEPS

    /** Push a persisted snapshot into the holders. Both values are clamped by the caller. */
    fun apply(turnBudgetMs: Long, maxToolSteps: Int) {
        this.turnBudgetMs = GenerationLimits.clampTurnBudgetMs(turnBudgetMs)
        this.maxToolSteps = GenerationLimits.clampMaxToolSteps(maxToolSteps)
    }
}
