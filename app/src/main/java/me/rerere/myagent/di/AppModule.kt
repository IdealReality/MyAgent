package me.rerere.myagent.di

import kotlinx.serialization.json.Json
import me.rerere.myagent.AppScope
import me.rerere.myagent.data.ai.AILoggingManager
import me.rerere.myagent.data.ai.tools.ChatToolFactory
import me.rerere.myagent.data.ai.tools.LocalTools
import me.rerere.myagent.data.ai.tools.local.BiometricResultBuffer
import me.rerere.myagent.data.ai.tools.local.CameraResultBuffer
import me.rerere.myagent.data.event.AppEventBus
import me.rerere.myagent.data.ai.tools.local.InteractiveToolStreamer
import me.rerere.myagent.data.repository.ScheduledJobRepository
import me.rerere.myagent.data.repository.SshHostRepository
import me.rerere.myagent.data.repository.TelegramChatRepository
import me.rerere.myagent.data.notifications.NotificationListenerPreferences
import me.rerere.myagent.data.telegram.TelegramBotClient
import me.rerere.myagent.data.telegram.TelegramBotPreferences
import me.rerere.myagent.service.ChatService
import me.rerere.myagent.service.CronJobScheduler
import me.rerere.myagent.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import me.rerere.myagent.ui.pages.setting.SshTerminalSessionManager
import me.rerere.myagent.utils.EmojiData
import me.rerere.myagent.utils.EmojiUtils
import me.rerere.myagent.utils.JsonInstant
import me.rerere.myagent.utils.SoundEffectPlayer
import me.rerere.myagent.web.WebServerManager
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single { CameraResultBuffer() }
    single { BiometricResultBuffer() }
    // Phase 25 — NFC reader-mode + SAF directory-picker Activity bridges, and the SAF
    // tree-grant store backing the ExternalStorage tools.
    single { me.rerere.myagent.data.ai.tools.local.NfcResultBuffer() }
    single { me.rerere.myagent.data.ai.tools.local.SafPickerResultBuffer() }
    single { me.rerere.myagent.data.storage.StorageVolumeGrantStore(get()) }

    single { ScheduledJobRepository(get<me.rerere.myagent.data.db.AppDatabase>().scheduledJobDao()) }
    single { me.rerere.myagent.data.repository.ScheduledJobRunRepository(get<me.rerere.myagent.data.db.AppDatabase>().scheduledJobRunDao()) }
    single { me.rerere.myagent.service.DirectModeActionRunner(get()) }
    single { CronJobScheduler(get(), get()) }
    single { SshHostRepository(get<me.rerere.myagent.data.db.AppDatabase>().sshHostDao()) }
    single { TelegramChatRepository(get<me.rerere.myagent.data.db.AppDatabase>().telegramChatDao()) }
    single { TelegramBotPreferences(get()) }
    single { me.rerere.myagent.browser.BrowserPreferences(get()) }
    // Generation limits (per-turn wall-clock budget + tool-step cap). Replaces the store that
    // used to live inside the removed Termux preferences; the init{} block seeds
    // ToolRuntimeLimits, so this must stay eagerly constructed (see MyAgentApp).
    single { me.rerere.myagent.data.ai.limits.GenerationLimitsPreferences(get()) }
    // Pass 3: Telegram-bound screenshot streamer for headless browser mode. Bound to the
    // [BrowserScreenshotStreamer] interface so [BrowserController.streamScreenshotIfHeadless]
    // can resolve it lazily via Koin without taking a constructor dep — avoids a cycle
    // through TelegramBotClient → TelegramBotPreferences → ... → LocalTools → controller.
    single<me.rerere.myagent.browser.BrowserScreenshotStreamer> {
        me.rerere.myagent.data.telegram.TelegramBrowserScreenshotStreamer(get(), get(), get())
    }
    // Interactive-tool post-action screenshot streamer for headless mode (Telegram bot /
    // cron / sub-agent). Resolves lazily inside each interactive tool's execute lambda so
    // there's no DI cycle through LocalTools → ChatService → ... → TelegramBotClient.
    single<InteractiveToolStreamer> {
        me.rerere.myagent.data.telegram.TelegramInteractiveToolStreamer(get(), get(), get(), get())
    }
    single { me.rerere.myagent.data.preferences.ToolApprovalPreferences(get()) }
    single {
        TelegramBotClient(
            tokenProvider = { runCatching { kotlinx.coroutines.runBlocking { get<TelegramBotPreferences>().current().token } }.getOrDefault("") },
            proxyConfigProvider = {
                runCatching {
                    kotlinx.coroutines.runBlocking { get<TelegramBotPreferences>().current() }
                }.getOrDefault(me.rerere.myagent.data.telegram.TelegramBotConfig())
            },
        )
    }
    // Phase 24 — Telegram long-poll stall tracker. Shared singleton: TelegramBotService's
    // poll loop calls markUpdate() on every getUpdates; the in-service stall checker and
    // DoctorChecks read it. No cross-dependencies, so no DI-cycle risk.
    single { me.rerere.myagent.data.telegram.TelegramPollStallTracker() }
    single { NotificationListenerPreferences(get()) }

    // Phase 13: External Automation Intent API
    single { me.rerere.myagent.automation.ExternalAutomationConfig(get()) }
    single {
        me.rerere.myagent.automation.ExternalAutomationDispatcher(
            context = get(),
            config = get(),
            chatService = get(),
            conversationRepo = get(),
            settingsStore = get(),
            appScope = get(),
            // Phase 24 — unified AgentRun ledger writer.
            agentRunRepo = get(),
        )
    }

    // Phase 14: Reliability bundle
    single { me.rerere.myagent.reliability.BugReportBuilder(get()) }

    // Phase 11: Sub-agents
    single { me.rerere.myagent.subagent.SubAgentRegistry() }
    single {
        me.rerere.myagent.subagent.SubAgentEngine(
            registry = get(),
            // chatService is resolved lazily inside SubAgentEngine to break the
            // ChatService→LocalTools→SubAgentEngine→ChatService cycle. See SubAgentEngine kdoc.
            conversationRepo = get(),
            settingsStore = get(),
            appScope = get(),
            // Phase 24 — unified AgentRun ledger writer. No DI cycle: AgentRunRepository
            // depends only on its DAO.
            agentRunRepo = get(),
            // P2-06b — the expert library, the source of truth for a dispatch's `agent` name.
            agentDefinitionRepository = get(),
            // P2-23 — read-only: only asked whether the configured archive folder still exists.
            folderRepository = get(),
            // P2-13 — read-only: the budget gate and the run usage read-back.
            usageLedger = get(),
        )
    }

    // Phase 16: Skill URL-import
    single {
        me.rerere.myagent.skills.SkillUrlImporter(
            skillManager = get<me.rerere.myagent.data.files.SkillManager>(),
        )
    }

    // Phase 19B: Skill isolation tester. Eager construction is safe here — ChatService
    // doesn't reach back into SkillTestRunner anywhere, so no DI cycle.
    single {
        me.rerere.myagent.skills.SkillTestRunner(
            chatService = get(),
            skillManager = get(),
            conversationRepo = get(),
            settingsStore = get(),
        )
    }

    // Phase 18: JS skills (run_js + secrets store)
    single { me.rerere.myagent.skills.js.JsSkillRunner(get()) }
    single { me.rerere.myagent.skills.js.SkillSecretsStore(get()) }

    // T-07: named secrets a workflow action can reference as {{secret:NAME}} — keystore-backed
    // so the token never has to live inside the (plaintext, model-visible) workflow JSON.
    single { me.rerere.myagent.workflow.secrets.WorkflowSecretsStore(get()) }
    single { me.rerere.myagent.data.secrets.AgentSecretsStore(get()) }

    // Phase 12: Workflows
    single {
        me.rerere.myagent.workflow.repository.WorkflowRepository(
            workflowDao = get<me.rerere.myagent.data.db.AppDatabase>().workflowDao(),
            workflowRunDao = get<me.rerere.myagent.data.db.AppDatabase>().workflowRunDao(),
        )
    }
    single { me.rerere.myagent.workflow.condition.ContextProvider(get()) }
    single { me.rerere.myagent.workflow.execution.WorkflowActionRunner() }
    single {
        me.rerere.myagent.workflow.execution.WorkflowEngine(
            repository = get(),
            settingsStore = get(),
            contextProvider = get(),
            actionRunner = get(),
        ).also { engine ->
            // Bridge for the repo to notify the engine on delete so the engine's per-workflow
            // lock map doesn't leak. Lazy because both singletons have to exist first.
            get<me.rerere.myagent.workflow.repository.WorkflowRepository>().bindEngine(engine)
        }
    }
    single {
        me.rerere.myagent.workflow.trigger.TriggerRegistry(
            context = get(),
            appScope = get(),
            workflowRepository = get(),
        )
    }

    single { me.rerere.myagent.data.keyboard.KeyboardApiClient(get()) }

    single {
        LocalTools(
            usageRecordDao = get(),
            agentRunRepository = get(),
            context = get(),
            eventBus = get(),
            cameraResultBuffer = get(),
            biometricResultBuffer = get(),
            scheduledJobRepository = get(),
            scheduledJobRunRepository = get(),
            cronJobScheduler = get(),
            settingsStore = get(),
            sshHostRepository = get(),
            agentSecretsStore = get(),
            telegramBotPreferences = get(),
            telegramBotClient = get(),
            notificationListenerPreferences = get(),
            mcpManager = get(),
            externalAutomationConfig = get(),
            bugReportBuilder = get(),
            agentDefinitionRepository = get(),
            subAgentEngine = get(),
            subAgentRegistry = get(),
            conversationRepo = get(),
            workflowRepository = get(),
            workflowEngine = get(),
            skillUrlImporter = get(),
            skillManager = get(),
            jsSkillRunner = get(),
            skillSecretsStore = get(),
            browserPreferences = get(),
            interactiveToolStreamer = get(),
            nfcResultBuffer = get(),
            safPickerResultBuffer = get(),
            storageVolumeGrantStore = get(),
            okHttpClient = get(),
            keyboardApiClient = get(),
            // P2-33 image generation tools
            providerManager = get(),
            filesManager = get(),
            genMediaRepository = get(),
        )
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        SoundEffectPlayer(get())
    }

    single {
        AILoggingManager(get(), get())
    }

    single {
        WorkspaceTerminalSessionManager(get(), get())
    }

    // SSH terminals live for the whole process: leaving the page must not close the channels,
    // and the tabs are persisted to filesDir so a restart brings them back.
    single {
        SshTerminalSessionManager(get(), get(), get())
    }

    // Phase 22A: Local-LLM on-device providers
    single { me.rerere.locallm.LocalRuntimePreferences(get()) }
    single { me.rerere.locallm.litert.LiteRtRuntime(get()) }
    single { me.rerere.llamacpp.LlamaCppRuntime() }

    // P2-40 — plugin kernel. One store + one registry for the process: the registry is the
    // single place that parses plugin dirs, so tools, prompt and the admin surface all see
    // the same snapshot within a turn.
    single {
        val context: android.content.Context = get()
        val json: kotlinx.serialization.json.Json = get()
        val store = me.rerere.myagent.plugin.PluginStore(
            rootDir = java.io.File(context.filesDir, "plugins"),
            json = json,
        )
        // 插件面板可以像普通网页一样引用同目录的 css/js/图片；
        // 路径穿越由 store.resolveFile 的校验兜住。
        me.rerere.myagent.ui.components.webview.WebViewLocalAssets.pluginFileResolver =
            { id, rel -> store.resolveFile(id, rel) }
        val bridge = me.rerere.myagent.plugin.PluginHostToolBridge()
        val runtime = me.rerere.myagent.plugin.PluginJsRuntime(
            store = store,
            json = json,
            httpClientProvider = { get<okhttp3.OkHttpClient>() },
            hostToolInvoker = { name, args ->
                bridge.invoker?.invoke(name, args)
                    ?: """{"ok":false,"error":"host tool bridge not ready"}"""
            },
            // ctx.app：插件读/写应用界面状态的白名单入口（权限 app_read / app_write）。
            appHost = me.rerere.myagent.plugin.SettingsPluginAppHost(
                settingsStore = get(),
                json = json,
            ),
        )
        me.rerere.myagent.plugin.PluginRegistry(
            store = store,
            runtime = runtime,
            json = json,
            hostToolBridge = bridge,
            seeder = { me.rerere.myagent.plugin.PluginSamples.seedBundled(context, it) },
            imageResolver = { b64 -> me.rerere.myagent.plugin.pluginImageUrl(context, b64) },
        )
    }

    single {
        ChatToolFactory(
            json = get(),
            memoryEngine = get(),
            conversationRepository = get(),
            localTools = get(),
            mcpManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            vmRepository = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryEngine = get(),
            generationLoop = get(),
            translationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            chatToolFactory = get(),
            mcpManager = get(),
            filesManager = get(),
            skillManager = get(),
            toolApprovalPreferences = get(),
            workspaceRepository = get(),
            vmRepository = get(),
            folderRepository = get()
        )
    }

    // One handler per process, not per connection: it owns the change-listener registry that
    // fans `hosts_changed` out to every open socket.
    single {
        me.rerere.myagent.web.SshHostSocketHandler(
            backend = me.rerere.myagent.web.RepositorySshHostBackend(
                context = get(),
                repository = get(),
            ),
            json = get<Json>(),
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get(),
            sshHostSocket = get()
        )
    }

    single {
        me.rerere.myagent.ui.pages.setting.doctor.DoctorChecks(
            context = get(),
            settingsStore = get(),
            telegramPrefs = get(),
            workflowRepository = get(),
            scheduledJobRepository = get(),
            scheduledJobRunRepository = get(),
            conversationRepository = get(),
            database = get(),
            // Pass 3: surface the browser write-tools-enabled INFO row + profile-dir AutoFix.
            browserPreferences = get(),
            // Phase 25: surface the SAF granted-directories live count.
            storageVolumeGrantStore = get(),
            // LiteRT accelerator status row in the Doctor: shows the persisted backend
            // decision so a silent GPU -> CPU fallback is visible.
            localRuntimePreferences = get(),
            // Doctor refresh: skills.* and service.mcp_servers rows.
            skillManager = get(),
            mcpManager = get(),
            // P2-06b: the expert library behind the assistant.subagent_profiles row.
            agentDefinitionRepository = get(),
        )
    }
}
