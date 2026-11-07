package me.rerere.ai.provider

import kotlinx.serialization.EncodeDefault
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
        var apiKey: String = "",
        var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
        // OpenRouter only: emit per-block cache_control breakpoints. Anthropic/Gemini/Qwen
        // need them explicitly; auto-caching providers have the field stripped upstream, so
        // it is applied to every model on the OpenRouter host. See ChatCompletionsAPI.
        var promptCaching: Boolean = true,
        /**
         * Replay stored `reasoning_content` on historical assistant messages.
         *
         * Kept as the on/off master switch (existing stores depend on the shape), but the
         * amount is bounded by [historyReasoningTurns]: replaying EVERY historical thinking
         * block is both expensive and actively harmful — the model starts continuing an old
         * line of reasoning instead of re-deriving one, and old thinking blocks can dominate
         * the request. See [historyReasoningTurns].
         */
        var includeHistoryReasoning: Boolean = true,
        /**
         * How many of the most recent assistant messages may replay their reasoning.
         *
         * `null` = unlimited (the pre-2026-11 behaviour, kept for wire compatibility with
         * stores written before this field existed). `0` = behave as if
         * [includeHistoryReasoning] were false. Any other value keeps reasoning on the newest
         * N assistant turns only, which is where it actually helps: the immediately preceding
         * step of a tool loop.
         */
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        var historyReasoningTurns: Int? = DEFAULT_HISTORY_REASONING_TURNS,
        // OpenRouter only: provider-routing preferences emitted as the `provider` object.
        var routing: OpenRouterRouting = OpenRouterRouting(),
        var responsesPath: String = "/responses",
    ) : ProviderSetting() {
        companion object {
            /**
             * Three turns of replay: enough for the model to see how it reasoned through the
             * immediately preceding tool steps (which is what keeps multi-step tool loops
             * coherent), far enough from "the entire history" that old thinking cannot crowd
             * out the live task. Measured on a real device, unlimited replay was a major
             * contributor to prompts averaging ~165k tokens.
             */
            const val DEFAULT_HISTORY_REASONING_TURNS = 3
        }

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
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                balanceOption = balanceOption,
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
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
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
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,
                customHeaders = customHeaders,
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
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
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
        ): ProviderSetting = copy(
            id = id, enabled = enabled, name = name, models = models,
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
        ): ProviderSetting = copy(
            id = id, enabled = enabled, name = name, models = models,
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
        ): ProviderSetting {
            return copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,

                customHeaders = customHeaders,
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
        ): ProviderSetting {
            return copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,

                customHeaders = customHeaders,
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
        ): ProviderSetting {
            return copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,

                customHeaders = customHeaders,
            )
        }
    }

    companion object {
        /**
         * Types the user can switch between inside an existing provider ("type" segmented
         * row in the Add-Provider dialog and ProviderConfigure). Deliberately limited to the
         * three key-based protocols: converting between them keeps the API key and rewrites
         * the endpoint path, which is the only conversion that means anything.
         *
         * This is NOT the list of creatable providers — see [creatableTypes].
         */
        val Types by lazy {
            listOf(
                OpenAI::class,
                Google::class,
                Claude::class,
            )
        }

        /**
         * Every provider type the user can create from scratch, in the order the "add
         * provider" dialog shows them.
         *
         * The app ships no pre-seeded providers, so this list is the ONLY way to reach the
         * OAuth providers (Codex / Grok / Gemini) and the on-device runtimes (AICore / LiteRT /
         * llama.cpp). Losing an entry here silently removes a whole capability from the build.
         */
        val creatableTypes: List<() -> ProviderSetting> by lazy {
            listOf(
                { OpenAI() },
                { Google() },
                { Claude() },
                { Codex() },
                { Grok() },
                { GeminiOAuth() },
                { AICore() },
                { LiteRtLocal() },
                { LlamaCppLocal() },
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
