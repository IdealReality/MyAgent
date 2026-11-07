package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.limits.GenerationLimits
import me.rerere.rikkahub.data.ai.limits.GenerationLimitsConfig
import me.rerere.rikkahub.data.ai.limits.GenerationLimitsPreferences

/**
 * Backs the "Generation limits" group on Settings -> Model: the per-turn wall-clock budget
 * and the tool-call step cap. Both are app-wide (they govern every tool family and the model
 * loop itself, see [me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits]).
 */
class GenerationLimitsViewModel(
    private val prefs: GenerationLimitsPreferences,
) : ViewModel() {

    val config: StateFlow<GenerationLimitsConfig> = combine(
        prefs.turnBudgetFlow(),
        prefs.maxToolStepsFlow(),
    ) { turnBudget, maxToolSteps ->
        GenerationLimitsConfig(turnBudgetMs = turnBudget, maxToolSteps = maxToolSteps)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GenerationLimitsConfig(
            turnBudgetMs = GenerationLimits.DEFAULT_TURN_BUDGET_MS,
            maxToolSteps = GenerationLimits.DEFAULT_MAX_TOOL_STEPS,
        ),
    )

    /** [minutes] is the UI display unit for turn budget; clamping happens in the store. */
    fun setTurnBudgetMinutes(minutes: Long) {
        viewModelScope.launch { prefs.setTurnBudgetMs(minutes * 60_000L) }
    }

    /** Tool-call iterations allowed in one turn; clamping happens in the store. */
    fun setMaxToolSteps(steps: Long) {
        viewModelScope.launch { prefs.setMaxToolSteps(steps.toInt()) }
    }
}
