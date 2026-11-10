package me.rerere.myagent.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.myagent.data.ai.ReviewStateBus
import me.rerere.myagent.data.datastore.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val SIDE_REVIEW_SYSTEM = "You are an independent reviewer. You have no context " +
    "other than what is pasted below. Report only fatal and important problems. For each one give: " +
    "the exact quoted fragment, why it is wrong, what breaks if left unfixed, and the fix. " +
    "No praise, no restating the input, no style preferences. " +
    "If you find no fatal or important issue, say so explicitly."

private fun pickOpenAiProvider(providers: List<ProviderSetting>): Pair<ProviderSetting.OpenAI, Model>? {
    val candidates = providers.filterIsInstance<ProviderSetting.OpenAI>()
        .filter { it.enabled && it.apiKey.isNotBlank() && it.models.isNotEmpty() }
    for (p in candidates) {
        val m = p.models.firstOrNull { it.modelId.isNotBlank() }
        if (m != null) return p to m
    }
    return null
}

private fun callSideChannel(
    provider: ProviderSetting.OpenAI,
    model: Model,
    content: String,
    focus: String?,
): String {
    val base = provider.baseUrl.trimEnd('/')
    if (base.isEmpty()) return "ERROR: provider baseUrl empty"
    val path = provider.chatCompletionsPath.ifBlank { "/chat/completions" }
    val url = base + path

    val userText = buildString {
        append("Content under review:\n\n")
        append(content)
        if (!focus.isNullOrBlank()) {
            append("\n\nReview focus: ")
            append(focus)
        }
    }

    val payload = buildJsonObject {
        put("model", model.modelId)
        put("stream", false)
        put("messages", buildJsonArray {
            addJsonObject {
                put("role", "system")
                put("content", SIDE_REVIEW_SYSTEM)
            }
            addJsonObject {
                put("role", "user")
                put("content", userText)
            }
        })
    }

    val request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer " + provider.apiKey)
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .build()

    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    client.newCall(request).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            return "ERROR: side channel HTTP " + resp.code + " " + body.take(400)
        }
        val text = runCatching {
            Json.parseToJsonElement(body).jsonObject["choices"]
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return text ?: body.take(1200)
    }
}

fun sideReviewTool(settingsStore: SettingsStore): Tool = Tool(
    name = "side_review",
    description = "Send text to an INDEPENDENT review channel: a separate model call with a fresh " +
        "context containing nothing but the text you pass in. Use it to have your own plan, " +
        "conclusion or code reviewed by something that does not share this conversation. " +
        "The UI shows a reviewing indicator only while this call is running.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "The exact text to review: plan, conclusion, code or diff")
                })
                put("focus", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional: what the reviewer should focus on")
                })
            }
        )
    },
    execute = {
        val params = it.jsonObject
        val content = params["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val focus = params["focus"]?.jsonPrimitive?.contentOrNull
        if (content.isBlank()) {
            listOf(UIMessagePart.Text("ERROR: content is empty"))
        } else {
            val settings = settingsStore.settingsFlow.first()
            val picked = pickOpenAiProvider(settings.providers)
            if (picked == null) {
                listOf(UIMessagePart.Text(
                    "ERROR: no enabled OpenAI-compatible provider with an apiKey; cannot open side channel"
                ))
            } else {
                val provider = picked.first
                val model = picked.second
                ReviewStateBus.start(focus?.take(24))
                try {
                    val out = withContext(Dispatchers.IO) {
                        callSideChannel(provider, model, content, focus)
                    }
                    listOf(UIMessagePart.Text("[" + model.modelId + "]\n" + out))
                } catch (e: Exception) {
                    listOf(UIMessagePart.Text("ERROR: side channel failed: " + (e.message ?: e.javaClass.simpleName)))
                } finally {
                    ReviewStateBus.stop()
                }
            }
        }
    }
)
