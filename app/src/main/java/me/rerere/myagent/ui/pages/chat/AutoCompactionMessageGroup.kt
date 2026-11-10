package me.rerere.myagent.ui.pages.chat

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.myagent.data.ai.ContextCompactionPresentation
import me.rerere.myagent.data.model.MessageNode
import kotlin.uuid.Uuid

/**
 * A display-only grouping of assistant nodes separated by automatic context compression,
 * and of interjection sandwiches (assistant half + user interjection + assistant half).
 *
 * Generation still retains every node independently, which keeps tool replay and provider
 * history correct. The UI renders the cards and continuation text as one assistant message.
 */
internal data class AutoCompactionMessageGroup(
    val nodes: List<MessageNode>,
) {
    init {
        require(nodes.isNotEmpty())
    }

    val id: Uuid = nodes.first().id
    val terminalNode: MessageNode = nodes.last()

    val displayMessage: UIMessage = terminalNode.currentMessage.copy(
        parts = nodes.flatMapIndexed { index, node ->
            val message = node.currentMessage
            if (message.role == MessageRole.USER && nodes.size > 1 && index != nodes.lastIndex) {
                // 插话：转成带 `myagent.interjection` 标记的 Text part，
                // 渲染层（ChatMessage）把它画成内嵌卡片，前后两段 assistant
                // 在视觉上保持同一条连续消息。
                listOf(interjectionPart(message))
            } else {
                message.parts
            }
        },
        annotations = nodes.flatMap { it.currentMessage.annotations },
        createdAt = nodes.first().currentMessage.createdAt,
    )
}

/** 插话内容压成一段文本；非文本附件降级为占位符，卡片里至少能看到用户说了什么。 */
private fun interjectionPart(message: UIMessage): UIMessagePart.Text {
    // Attachments are no longer flattened into placeholder text. They ride in metadata and get
    // drawn as thumbnails when the card is expanded (see ChatMessage.InterjectionCard).
    val images = message.parts.filterIsInstance<UIMessagePart.Image>().map { it.url }
    val text = message.parts.joinToString("\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            else -> ""
        }
    }.trim().ifBlank { if (images.isEmpty()) "." else "" }
    val metadata = buildJsonObject {
        put("myagent.interjection", JsonPrimitive(true))
        if (images.isNotEmpty()) {
            put(
                "myagent.interjection.images",
                buildJsonArray { images.forEach { add(JsonPrimitive(it)) } },
            )
        }
    }
    return UIMessagePart.Text(text = text, metadata = metadata)
}

internal fun List<MessageNode>.groupAutomaticCompactionMessages(): List<AutoCompactionMessageGroup> {
    if (isEmpty()) return emptyList()

    val groups = mutableListOf<AutoCompactionMessageGroup>()
    var index = 0
    while (index < size) {
        val groupedNodes = mutableListOf(this[index])
        while (true) {
            val last = groupedNodes.last().currentMessage
            val next = getOrNull(index + 1)?.currentMessage
            val nextNext = getOrNull(index + 2)?.currentMessage
            when {
                // 自动压缩续写：assistant(压缩工具) + assistant
                last.role == MessageRole.ASSISTANT &&
                    ContextCompactionPresentation.hasDisplayTool(last) &&
                    next?.role == MessageRole.ASSISTANT -> {
                    index++
                    groupedNodes += this[index]
                }

                // 插话三明治：assistant(未 finalize) + user + assistant。
                // 前半 finishedAt == null 是插话打断的标记（GenerationLoop 只在整轮
                // 真正结束时才设置它），据此把三段并成一组渲染为一条连续消息。
                // 停止/失败的消息会被 finalize，不会误并入。
                last.role == MessageRole.ASSISTANT &&
                    last.finishedAt == null &&
                    next?.role == MessageRole.USER &&
                    nextNext?.role == MessageRole.ASSISTANT -> {
                    groupedNodes += this[index + 1]
                    groupedNodes += this[index + 2]
                    index += 2
                }

                else -> break
            }
        }
        groups += AutoCompactionMessageGroup(groupedNodes)
        index++
    }
    return groups
}
