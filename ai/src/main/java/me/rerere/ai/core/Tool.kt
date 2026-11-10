package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = { false },
    /**
     * Wall-clock ceiling for ONE call of this tool, in ms. `null` (default) means only the
     * turn-wide budget applies — the historical behaviour. When set, the executor uses
     * `min(toolTimeoutMs, turnRemainingMs)` and reports a distinct
     * `tool_cancelled_timeout` envelope, so the model can tell "this tool took too long"
     * apart from "the whole turn ran out of time".
     */
    val timeoutMs: Long? = null,
    val execute: suspend (JsonElement) -> List<UIMessagePart>
)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
