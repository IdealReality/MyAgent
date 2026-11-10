package me.rerere.myagent.data.ai.limits

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private val Context.generationLimitsDataStore by preferencesDataStore(name = "generation_limits")

/**
 * DataStore-backed store for the app-wide generation limits: the per-turn wall-clock budget
 * and the per-turn tool-call step cap.
 *
 * Formerly these lived in the Termux preference store (the Termux settings page was where
 * users configured them). When that integration was removed they moved here, keeping the
 * same semantics: values are clamped on read and on write, and the [init] block seeds
 * [ToolRuntimeLimits] synchronously so non-suspend callers (the generation loop, tool
 * execution) always see the persisted value even before the async collectors emit.
 */
class GenerationLimitsPreferences(private val context: Context) {

    private val store = context.generationLimitsDataStore

    private val turnBudgetKey = longPreferencesKey("turn_budget_ms")
    private val maxToolStepsKey = intPreferencesKey("max_tool_steps")

    init {
        val initial = snapshotBlocking()
        ToolRuntimeLimits.apply(
            turnBudgetMs = initial.turnBudgetMs,
            maxToolSteps = initial.maxToolSteps,
        )

        // Keep the holders live on subsequent edits. Process-lived scope by design; a
        // SupervisorJob means one failing collector cannot take the other down.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            turnBudgetFlow().distinctUntilChanged().onEach { ToolRuntimeLimits.turnBudgetMs = it }.collect {}
        }
        scope.launch {
            maxToolStepsFlow().distinctUntilChanged().onEach { ToolRuntimeLimits.maxToolSteps = it }.collect {}
        }
    }

    fun turnBudgetFlow(): Flow<Long> = store.data.map { prefs ->
        GenerationLimits.clampTurnBudgetMs(
            prefs[turnBudgetKey] ?: GenerationLimits.DEFAULT_TURN_BUDGET_MS
        )
    }

    fun maxToolStepsFlow(): Flow<Int> = store.data.map { prefs ->
        GenerationLimits.clampMaxToolSteps(
            prefs[maxToolStepsKey] ?: GenerationLimits.DEFAULT_MAX_TOOL_STEPS
        )
    }

    suspend fun setTurnBudgetMs(ms: Long) {
        store.edit { it[turnBudgetKey] = GenerationLimits.clampTurnBudgetMs(ms) }
    }

    suspend fun setMaxToolSteps(steps: Int) {
        store.edit { it[maxToolStepsKey] = GenerationLimits.clampMaxToolSteps(steps) }
    }

    /** One-shot suspend snapshot of both limits, clamped on read. */
    suspend fun snapshot(): GenerationLimitsConfig {
        val prefs = store.data.first()
        return GenerationLimitsConfig(
            turnBudgetMs = GenerationLimits.clampTurnBudgetMs(
                prefs[turnBudgetKey] ?: GenerationLimits.DEFAULT_TURN_BUDGET_MS
            ),
            maxToolSteps = GenerationLimits.clampMaxToolSteps(
                prefs[maxToolStepsKey] ?: GenerationLimits.DEFAULT_MAX_TOOL_STEPS
            ),
        )
    }

    /** Blocking variant for non-suspend callers. */
    fun snapshotBlocking(): GenerationLimitsConfig = kotlinx.coroutines.runBlocking { snapshot() }
}

/** Immutable snapshot of both generation limits. */
data class GenerationLimitsConfig(
    val turnBudgetMs: Long,
    val maxToolSteps: Int,
)
