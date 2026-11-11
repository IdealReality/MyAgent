package me.rerere.myagent.ui.components.ai

import me.rerere.myagent.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 两个开关连点时必须累积。曾经的写法是 `assistant.copy(...)`（值版本）：第二次点击用的是
 * 同一份过期快照，结果只剩后点的那一个 —— 这正是 AssistantDetailVM.updateAssistant 注释里
 * 点名的场景，也是「已有助手加外部工具」这条链最容易被踩坏的一步。
 */
class McpToggleTest {
    private val a = Uuid.random()
    private val b = Uuid.random()
    private val known = setOf(a, b)

    @Test
    fun `two rapid toggles accumulate`() {
        var assistant = Assistant()
        assistant = assistant.applyMcpToggle(a, enable = true, knownServerIds = known)
        assistant = assistant.applyMcpToggle(b, enable = true, knownServerIds = known)
        assertEquals(setOf(a, b), assistant.mcpServers)
    }

    @Test
    fun `toggling off keeps the other one`() {
        var assistant = Assistant(mcpServers = setOf(a, b))
        assistant = assistant.applyMcpToggle(a, enable = false, knownServerIds = known)
        assertEquals(setOf(b), assistant.mcpServers)
    }

    @Test
    fun `toggling prunes servers that no longer exist`() {
        val removed = Uuid.random()
        val assistant = Assistant(mcpServers = setOf(a, removed))
            .applyMcpToggle(b, enable = true, knownServerIds = known)
        assertEquals(setOf(a, b), assistant.mcpServers)
    }
}
