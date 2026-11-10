package me.rerere.myagent.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.myagent.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginToolVisibilityTest {

    private fun tool(name: String) = Tool(
        name = name,
        description = name,
        parameters = { null },
        execute = { emptyList() },
    )

    private val surface = listOf(
        tool("trpg_dice"),
        tool("trpg_sheet"),
        tool("trpg_scene"),
        tool("trpg_save"),
        tool("plugin_clock"),
        tool("trpgx_not_a_prefix_match"),
    )

    @Test
    fun `trpg tools are hidden unless the assistant opted in`() {
        val visible = PluginToolVisibility.forAssistant(surface, Assistant())
        assertEquals(
            listOf("plugin_clock", "trpgx_not_a_prefix_match"),
            visible.map { it.name },
        )
    }

    @Test
    fun `opting in exposes the whole surface`() {
        val assistant = Assistant(trpgStateInjection = true)
        assertEquals(surface, PluginToolVisibility.forAssistant(surface, assistant))
    }

    @Test
    fun `filtering never adds anything`() {
        val empty = PluginToolVisibility.forAssistant(emptyList(), Assistant())
        assertTrue(empty.isEmpty())
    }
}
