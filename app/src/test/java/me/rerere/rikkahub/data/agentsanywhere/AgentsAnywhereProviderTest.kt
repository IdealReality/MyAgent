package me.rerere.rikkahub.data.agentsanywhere

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AA timeline 解析的回归。
 *
 * 这些形状全部来自实测（arch 上的 acp runtime + omp）：
 *  - assistant / reasoning 条目是**累积全文**，随 revision 递增变长；
 *  - reasoning 是 `type=system` 且 `content.kind=reasoning`，不是 `role=assistant`；
 *  - tool 条目把命令放在 `content.command` / `content.title`，输出放在 `content.output`；
 *  - `completedAt` 为 null 时只代表「还没结束」，status 仍是 done 的条目也算结束。
 */
class AgentsAnywhereProviderTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `assistant message item is parsed as accumulated text`() {
        val raw = """
            {
              "id": "assistant-1",
              "type": "message",
              "role": "assistant",
              "status": "done",
              "content": {"format": "markdown", "kind": "markdown", "text": "probe ok"},
              "completedAt": "2026-10-29T19:23:34Z"
            }
        """.trimIndent()
        val entry = AgentsAnywhereProvider.parseTimelineItem(json.parseToJsonElement(raw))!!
        assertEquals(AgentsAnywhereProvider.TimelineKind.ASSISTANT, entry.kind)
        assertEquals("probe ok", entry.text)
        assertTrue(entry.finished)
    }

    @Test
    fun `reasoning item is a system entry with reasoning content`() {
        val raw = """
            {
              "id": "reasoning-1",
              "type": "system",
              "role": null,
              "status": "done",
              "content": {"kind": "reasoning", "text": "thinking..."},
              "completedAt": null
            }
        """.trimIndent()
        val entry = AgentsAnywhereProvider.parseTimelineItem(json.parseToJsonElement(raw))!!
        assertEquals(AgentsAnywhereProvider.TimelineKind.REASONING, entry.kind)
        assertEquals("thinking...", entry.text)
        // status=done 但 completedAt 为 null：仍算结束，否则这一段永远不会发 ReasoningEnd。
        assertTrue(entry.finished)
    }

    @Test
    fun `tool item carries the command as its input`() {
        val raw = """
            {
              "id": "tool-1",
              "type": "tool",
              "role": "tool",
              "status": "done",
              "content": {
                "kind": "command",
                "command": "echo aa-probe-tool",
                "title": "${'$'} echo aa-probe-tool",
                "output": "aa-probe-tool"
              },
              "completedAt": "2026-10-29T19:24:00Z"
            }
        """.trimIndent()
        val entry = AgentsAnywhereProvider.parseTimelineItem(json.parseToJsonElement(raw))!!
        assertEquals(AgentsAnywhereProvider.TimelineKind.TOOL, entry.kind)
        assertEquals("echo aa-probe-tool", entry.toolInput!!.jsonObjectCommand())
        assertEquals("aa-probe-tool", entry.toolOutput?.jsonPrimitive?.content)
        assertTrue(entry.finished)
    }

    @Test
    fun `running assistant item is not finished`() {
        val raw = """
            {
              "id": "assistant-2",
              "type": "message",
              "role": "assistant",
              "status": "running",
              "content": {"kind": "markdown", "text": "partial"},
              "completedAt": null
            }
        """.trimIndent()
        val entry = AgentsAnywhereProvider.parseTimelineItem(json.parseToJsonElement(raw))!!
        assertEquals(false, entry.finished)
    }

    @Test
    fun `user message is not treated as assistant output`() {
        val raw = """
            {
              "id": "user-1",
              "type": "message",
              "role": "user",
              "status": "done",
              "content": {"format": "text", "kind": "text", "text": "hi"},
              "completedAt": "2026-10-29T19:23:31Z"
            }
        """.trimIndent()
        val entry = AgentsAnywhereProvider.parseTimelineItem(json.parseToJsonElement(raw))!!
        assertEquals(AgentsAnywhereProvider.TimelineKind.USER, entry.kind)
    }

    @Test
    fun `item without id is dropped`() {
        assertNull(AgentsAnywhereProvider.parseTimelineItem(json.parseToJsonElement("""{"type":"message"}""")))
    }

    @Test
    fun `only the last user text becomes the prompt`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("first"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("answer"))),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("  second  "))),
        )
        assertEquals("second", AgentsAnywhereProvider.run { messages.lastUserPrompt() })
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectCommand(): String =
    (this as kotlinx.serialization.json.JsonObject)["command"]!!.jsonPrimitive.content
