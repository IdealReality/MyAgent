package me.rerere.myagent.data.memory

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
import me.rerere.myagent.data.datastore.findModelById
import me.rerere.myagent.data.datastore.findProvider
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
    private val settingsStore: me.rerere.myagent.data.datastore.SettingsStore by inject()
    private val providerManager: me.rerere.ai.provider.ProviderManager by inject()

    override suspend fun doWork(): Result {
        return runCatching {
            val settings = settingsStore.settingsFlow.first()
            for (assistant in settings.assistants) {
                if (!assistant.enableMemory) continue
                if (!assistant.autoLearnEnabled) continue // P6：per-assistant autolearn 开关
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
        settings: me.rerere.myagent.data.datastore.Settings,
        assistant: me.rerere.myagent.data.model.Assistant,
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

        // snapshot before the destructive L1 rewrite (safety net; P6: keep count per-assistant)
        MemorySnapshot.create(store, scopeId, System.currentTimeMillis(), keep = assistant.memorySnapshotKeep)

        val handler = providerManager.getProviderByType(provider)
        val params = TextGenerationParams(
            model = model,
            reasoningLevel = ReasoningLevel.OFF,
            customHeaders = model.customHeaders,
            customBody = model.customBodies,
        )

        // P6 · gist 批量生成（一次调用；失败静默跳过，不阻塞 L1 重写）
        val gists: Map<String, String> = runCatching {
            val gistCall = handler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt = AutoLearn.buildGistPrompt(segments))),
                params = params,
            )
            AutoLearn.parseGists(gistCall.message.toText())
        }.getOrDefault(emptyMap())

        val oldL1 = store.readL1(scopeId)
        val prompt = AutoLearn.buildRewritePrompt(oldL1, segments, l1MaxChars = assistant.memoryL1MaxChars)
        val result = handler.generateText(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt = prompt)),
            params = params,
        )
        val newL1 = AutoLearn.parseRewrite(result.message.toText(), maxChars = assistant.memoryL1MaxChars)
        if (newL1.isBlank()) return
        AutoLearn.commit(store, scopeId, segments, newL1, System.currentTimeMillis(), gists = gists)
        MemLog.d(TAG, "autolearn ok scope=$scopeId segs=${segments.size} gists=${gists.size}")
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
