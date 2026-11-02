package me.rerere.rikkahub.data.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * autolearn Runner (P3). WorkManager hourly check: unlearned segments >= 10 or
 * >= 7 days since last run. Rewrites L1 from the oldest N segments using the
 * memory model role (Assistant.memoryModelId -> settings.memoryModelId ->
 * fastModelId), snapshotting the memory dir first. Model unavailable -> silent
 * skip until the next cycle.
 */
class AutoLearnWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val engine: AssistantMemoryEngine by inject()
    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore by inject()
    private val providerManager: me.rerere.ai.provider.ProviderManager by inject()

    override suspend fun doWork(): Result {
        return runCatching {
            val settings = settingsStore.settingsFlow.first()
            for (assistant in settings.assistants) {
                if (!assistant.enableMemory) continue
                runCatching { learnForAssistant(settings, assistant) }
                    .onFailure { MemLog.w(TAG, "autolearn ${assistant.id}: ${it.message}") }
            }
            Result.success()
        }.getOrElse {
            MemLog.w(TAG, "autolearn worker failed: ${it.message}")
            Result.success()
        }
    }

    private suspend fun learnForAssistant(
        settings: me.rerere.rikkahub.data.datastore.Settings,
        assistant: me.rerere.rikkahub.data.model.Assistant,
    ) {
        val scopeId = engine.scopeIdOf(assistant)
        val store = engine.store(scopeId)
        val trigger = AutoLearn.shouldTrigger(store, scopeId, System.currentTimeMillis())
        if (!trigger.shouldRun) return

        val segments = AutoLearn.pickSegments(store, scopeId)
        if (segments.isEmpty()) return

        val modelId = assistant.memoryModelId ?: settings.memoryModelId ?: settings.fastModelId
        val model: Model = settings.findModelById(modelId) ?: return
        val provider = model.findProvider(settings.providers) ?: return
        if (!provider.enabled) return

        // snapshot before the destructive L1 rewrite (safety net)
        MemorySnapshot.create(store, scopeId, System.currentTimeMillis())

        val oldL1 = store.readL1(scopeId)
        val prompt = AutoLearn.buildRewritePrompt(oldL1, segments)
        val handler = providerManager.getProviderByType(provider)
        val result = handler.generateText(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt = prompt)),
            params = TextGenerationParams(
                model = model,
                reasoningLevel = ReasoningLevel.OFF,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
        val newL1 = AutoLearn.parseRewrite(result.message.toText())
        if (newL1.isBlank()) return
        AutoLearn.commit(store, scopeId, segments, newL1, System.currentTimeMillis())
        MemLog.d(TAG, "autolearn ok scope=$scopeId segs=${segments.size}")
    }

    companion object {
        private const val TAG = "AutoLearnWorker"
        private const val WORK_NAME = "memory_autolearn"

        /** Hourly periodic check. Idempotent (KEEP). Call once from app startup. */
        fun schedule(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<AutoLearnWorker>(1, TimeUnit.HOURS).build(),
                )
            }.onFailure { MemLog.w(TAG, "schedule failed: ${it.message}") }
        }
    }
}
