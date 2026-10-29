package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid

@Serializable
data class BalanceOption(
    val enabled: Boolean = false, // 是否开启余额获取功能
    val apiPath: String = "/credits", // 余额获取API路径
    val resultPath: String = "data.total_usage", // 余额获取JSON路径
)

@Serializable
enum class ClaudePromptCacheTtl(val apiValue: String?) {
    @SerialName("5m")
    FIVE_MINUTES(null),

    @SerialName("1h")
    ONE_HOUR("1h")
}

@Serializable
sealed class ProviderSetting {
    abstract val id: Uuid
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>
    abstract val balanceOption: BalanceOption
    abstract val customHeaders: List<CustomHeader>

    abstract val builtIn: Boolean
    abstract val description: @Composable() () -> Unit
    abstract val shortDescription: @Composable() () -> Unit

    abstract fun addModel(model: Model): ProviderSetting
    abstract fun editModel(model: Model): ProviderSetting
    abstract fun delModel(model: Model): ProviderSetting
    abstract fun moveMove(from: Int, to: Int): ProviderSetting
    abstract fun copyProvider(
        id: Uuid = this.id,
        enabled: Boolean = this.enabled,
        name: String = this.name,
        models: List<Model> = this.models,
        balanceOption: BalanceOption = this.balanceOption,
        customHeaders: List<CustomHeader> = this.customHeaders,
        builtIn: Boolean = this.builtIn,
        description: @Composable (() -> Unit) = this.description,
        shortDescription: @Composable (() -> Unit) = this.shortDescription,
    ): ProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
        // OpenRouter only: emit per-block cache_control breakpoints. Anthropic/Gemini/Qwen
        // need them explicitly; auto-caching providers have the field stripped upstream, so
        // it is applied to every model on the OpenRouter host. See ChatCompletionsAPI.
        var promptCaching: Boolean = true,
        var includeHistoryReasoning: Boolean = true,
        // OpenRouter only: provider-routing preferences emitted as the `provider` object.
        var routing: OpenRouterRouting = OpenRouterRouting(),
        var responsesPath: String = "/responses",
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                balanceOption = balanceOption,
                shortDescription = shortDescription
            )
        }
    }

    @Serializable
    @SerialName("google")
    data class Google(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Google",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        var vertexAI: Boolean = false,
        var useServiceAccount: Boolean = false,
        var privateKey: String = "", // only for vertex AI service account
        var serviceAccountEmail: String = "", // only for vertex AI service account
        var location: String = "us-central1", // only for vertex AI service account
        var projectId: String = "", // only for vertex AI service account
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
                balanceOption = balanceOption
            )
        }
    }

    @Serializable
    @SerialName("claude")
    data class Claude(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Claude",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.anthropic.com/v1",
        var promptCaching: Boolean = true,  // ~10% input rate on cache hits, near-pure win
        var promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    @Serializable
    @SerialName("aicore")
    data class AICore(
        override var id: Uuid = AICORE_PROVIDER_ID,
        override var enabled: Boolean = true,
        override var name: String = "AICore (on-device)",
        override var models: List<Model> = AICORE_DEFAULT_MODELS,
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        // Defaults to PREVIEW because the STABLE feature ID is missing on most current
        // AICore beta channels — PREVIEW is what actually resolves to a working model on
        // Pixel 8/9/10 today. Users can flip back to STABLE once Google promotes it.
        var releaseStage: AICoreReleaseStage = AICoreReleaseStage.PREVIEW,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = this // synthetic models, no add
        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model else it })
        }

        override fun delModel(model: Model): ProviderSetting = this // synthetic models, no delete

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val m = removeAt(from)
                add(to, m)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
                balanceOption = balanceOption,

                customHeaders = customHeaders,
            )
        }
    }

    @Serializable
    @SerialName("local_litert")
    data class LiteRtLocal(
        override var id: Uuid = LITERT_PROVIDER_ID,
        override var enabled: Boolean = false,
        override var name: String = "Local · LiteRT",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)
        override fun editModel(model: Model): ProviderSetting =
            copy(models = models.map { if (it.id == model.id) model else it })
        override fun delModel(model: Model): ProviderSetting =
            copy(models = models.filter { it.id != model.id })
        override fun moveMove(from: Int, to: Int): ProviderSetting =
            copy(models = models.toMutableList().apply { add(to, removeAt(from)) })
        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting = copy(
            id = id, enabled = enabled, name = name, models = models,
            builtIn = builtIn, description = description, shortDescription = shortDescription,
            balanceOption = balanceOption,

            customHeaders = customHeaders,
        )
    }

    @Serializable
    @SerialName("local_llamacpp")
    data class LlamaCppLocal(
        override var id: Uuid = LLAMACPP_PROVIDER_ID,
        override var enabled: Boolean = false,
        override var name: String = "Local · llama.cpp",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)
        override fun editModel(model: Model): ProviderSetting =
            copy(models = models.map { if (it.id == model.id) model else it })
        override fun delModel(model: Model): ProviderSetting =
            copy(models = models.filter { it.id != model.id })
        override fun moveMove(from: Int, to: Int): ProviderSetting =
            copy(models = models.toMutableList().apply { add(to, removeAt(from)) })
        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting = copy(
            id = id, enabled = enabled, name = name, models = models,
            builtIn = builtIn, description = description, shortDescription = shortDescription,
            balanceOption = balanceOption,

            customHeaders = customHeaders,
        )
    }

    @Serializable
    @SerialName("codex")
    data class Codex(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = false,
        override var name: String = "Codex",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)

        override fun editModel(model: Model): ProviderSetting =
            copy(models = models.map { if (it.id == model.id) model.copy() else it })

        override fun delModel(model: Model): ProviderSetting =
            copy(models = models.filter { it.id != model.id })

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,

                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    @Serializable
    @SerialName("grok")
    data class Grok(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = false,
        override var name: String = "Grok",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)

        override fun editModel(model: Model): ProviderSetting =
            copy(models = models.map { if (it.id == model.id) model.copy() else it })

        override fun delModel(model: Model): ProviderSetting =
            copy(models = models.filter { it.id != model.id })

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,

                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    @Serializable
    @SerialName("gemini_oauth")
    data class GeminiOAuth(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = false,
        override var name: String = "Gemini OAuth",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)

        override fun editModel(model: Model): ProviderSetting =
            copy(models = models.map { if (it.id == model.id) model.copy() else it })

        override fun delModel(model: Model): ProviderSetting =
            copy(models = models.filter { it.id != model.id })

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,

                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    /**
     * Agents Anywhere — 把另一台机器上的完整 agent（AA 托管的 omp / codex / claude）**直接当成
     * 一个模型**来对话。
     *
     * 为什么是 Provider 而不是工具：工具形态下本地模型是「中间人」——它读到远端答复后再转述一遍，
     * 既多烧一轮 token，又把远端的原始流式输出、推理过程和工具卡片全部压成一段二手文本。做成
     * provider 后，选中这个「模型」就是在和远端 agent 直接对话：远端 token 直出到聊天流，
     * 远端的 reasoning 与工具条目也原样成为消息 part。
     *
     * 协议限制（实测 AA v2）：没有 SSE，真正的增量靠 `timeline?mode=changes&afterSeq=` 轮询；
     * timeline 条目是**累积全文 + revision 递增**，所以「增量」由客户端按已发送长度裁剪得到。
     */
    @Serializable
    @SerialName("agents_anywhere")
    data class AgentsAnywhere(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = false,
        override var name: String = "Agents Anywhere",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        /** 形如 `https://host:8443`：只填 origin，AA 的 normalizeServerOrigin 拒收带路径的地址。 */
        var serverOrigin: String = "",
        /** `/api/v2/auth/login` 返回的 accessToken。 */
        var accessToken: String = "",
        /** 三者定住「哪台机器上的哪个 runtime」：AA 的会话必须落在一个具体 runtime 上。 */
        var connectorId: String = "",
        var projectId: String = "",
        var runtimeId: String = "",
        /** 远端工作目录，必须与 project 的 workspacePath 一致，否则 422。空 = 用 project 默认。 */
        var cwd: String = "",
        /** 远端权限档位（catalog permission 的 selectionId）。全自动 = `full-auto`。 */
        var permissionSelectionId: String = "full-auto",
        /** 单轮等待上限（毫秒）。远端 agent 一轮通常 10–60 秒，工具循环长任务可达数分钟。 */
        var turnTimeoutMillis: Long = 600_000,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)

        override fun editModel(model: Model): ProviderSetting =
            copy(models = models.map { if (it.id == model.id) model.copy() else it })

        override fun delModel(model: Model): ProviderSetting =
            copy(models = models.filter { it.id != model.id })

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,
                customHeaders = customHeaders,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }

        /** 归一化成 `scheme://host[:port]`，去掉误粘的路径与尾部斜杠。 */
        fun normalizedOrigin(): String {
            var value = serverOrigin.trim()
            if (value.isEmpty()) return ""
            if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://$value"
            val schemeEnd = value.indexOf("://") + 3
            val pathStart = value.indexOf('/', schemeEnd)
            if (pathStart >= 0) value = value.substring(0, pathStart)
            return value.trimEnd('/')
        }

        /** 定位信息齐全才发请求。accessToken 可以为空——自托管实例也允许匿名 mode 的部署。 */
        fun isReady(): Boolean =
            normalizedOrigin().isNotEmpty() &&
                connectorId.isNotBlank() && projectId.isNotBlank() && runtimeId.isNotBlank()
    }

    companion object {
        // Types presented to the user when adding / converting a provider. AICore is
        // intentionally NOT in this list: it is a singleton built-in (one per device,
        // synthesized from the AICore system app), so the "type segmented row" inside
        // the Add-Provider dialog and ProviderConfigure should not offer it as a choice.
        // Including it overflowed the dialog width and wrapped the OpenAI / Google labels
        // onto two lines on a Pixel 10 Pro.
        val Types by lazy {
            listOf(
                OpenAI::class,
                Google::class,
                Claude::class,
            )
        }
    }
}

@Serializable
enum class AICoreReleaseStage { STABLE, PREVIEW }

// Stable IDs for the synthetic AICore provider + models so saved settings and
// conversations referencing them survive app re-installs and provider re-seeds.
val AICORE_PROVIDER_ID: Uuid = Uuid.parse("a1c0a1c0-1234-4111-a000-000000000001")
val LITERT_PROVIDER_ID: Uuid = Uuid.parse("11111111-aaaa-bbbb-cccc-000000000002")
val LLAMACPP_PROVIDER_ID: Uuid = Uuid.parse("11111111-aaaa-bbbb-cccc-000000000003")
private val AICORE_NANO_FAST_ID: Uuid = Uuid.parse("a1c0a1c0-1234-4111-a000-000000000002")
private val AICORE_NANO_FULL_ID: Uuid = Uuid.parse("a1c0a1c0-1234-4111-a000-000000000003")

val AICORE_NANO_FAST_MODEL: Model = Model(
    id = AICORE_NANO_FAST_ID,
    modelId = "nano-fast",
    displayName = "Gemini Nano (FAST)",
    abilities = listOf(ModelAbility.TOOL),
)

val AICORE_NANO_FULL_MODEL: Model = Model(
    id = AICORE_NANO_FULL_ID,
    modelId = "nano-full",
    displayName = "Gemini Nano (FULL)",
    abilities = listOf(ModelAbility.TOOL),
)

val AICORE_DEFAULT_MODELS: List<Model> = listOf(AICORE_NANO_FAST_MODEL, AICORE_NANO_FULL_MODEL)
