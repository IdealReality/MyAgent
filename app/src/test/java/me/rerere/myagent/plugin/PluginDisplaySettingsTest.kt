package me.rerere.myagent.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.myagent.data.datastore.DisplaySetting
import me.rerere.myagent.data.datastore.ToolCallStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginDisplaySettingsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun patch(raw: String) = json.parseToJsonElement(raw).jsonObject

    @Test
    fun `describe exposes exactly the writable keys`() {
        val described = PluginDisplaySettings.describe(DisplaySetting()).keys
        assertEquals(PluginDisplaySettings.writableKeys, described)
    }

    @Test
    fun `booleans floats ints and enums are applied`() {
        val (next, applied) = PluginDisplaySettings.apply(
            DisplaySetting(),
            patch(
                """
                {"cleanLayout":true,"codeBlockAutoCollapse":true,"bubbleOpacity":0.42,
                 "fontSizeRatio":1.25,"thinkingStepsVisibleCount":5}
                """,
            ),
        )
        assertTrue(next.cleanLayout)
        assertTrue(next.codeBlockAutoCollapse)
        assertEquals(0.42f, next.bubbleOpacity, 0.0001f)
        assertEquals(1.25f, next.fontSizeRatio, 0.0001f)
        assertEquals(5, next.thinkingStepsVisibleCount)
        assertEquals(
            setOf("cleanLayout", "codeBlockAutoCollapse", "bubbleOpacity", "fontSizeRatio", "thinkingStepsVisibleCount"),
            applied.toSet(),
        )
    }

    @Test
    fun `numeric values are clamped instead of trusted`() {
        val (next, _) = PluginDisplaySettings.apply(
            DisplaySetting(),
            patch("""{"fontSizeRatio":99,"bubbleOpacity":-3,"cleanLayoutTopHeight":1,"thinkingStepsVisibleCount":999}"""),
        )
        assertEquals(1.6f, next.fontSizeRatio, 0.0001f)
        assertEquals(0f, next.bubbleOpacity, 0.0001f)
        assertEquals(40f, next.cleanLayoutTopHeight, 0.0001f)
        assertEquals(20, next.thinkingStepsVisibleCount)
    }

    @Test
    fun `unknown keys are ignored and reported as not applied`() {
        val before = DisplaySetting()
        val (next, applied) = PluginDisplaySettings.apply(
            before,
            patch("""{"modelId":"x","apiKey":"y","workspacePath":"/etc","cleanLayout":true}"""),
        )
        assertEquals(before.copy(cleanLayout = true), next)
        assertEquals(listOf("cleanLayout"), applied)
    }

    @Test
    fun `enum keys accept case-insensitive names and ignore garbage`() {
        val target = ToolCallStyle.entries.last()
        val (next, applied) = PluginDisplaySettings.apply(
            DisplaySetting(),
            patch("""{"toolCallStyle":"${target.name.lowercase()}"}"""),
        )
        assertEquals(target, next.toolCallStyle)
        assertEquals(listOf("toolCallStyle"), applied)

        val (bad, badApplied) = PluginDisplaySettings.apply(
            DisplaySetting(),
            patch("""{"toolCallStyle":"not-a-style"}"""),
        )
        assertEquals(DisplaySetting(), bad)
        assertTrue(badApplied.isEmpty())
    }

    @Test
    fun `type mismatches do not throw or change anything`() {
        val before = DisplaySetting()
        val (next, applied) = PluginDisplaySettings.apply(
            before,
            patch("""{"cleanLayout":"yes","fontSizeRatio":"big","inlineWebPreview":1}"""),
        )
        assertEquals(before, next)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `writing the current value is not reported as applied`() {
        val before = DisplaySetting(cleanLayout = true)
        val (_, applied) = PluginDisplaySettings.apply(before, patch("""{"cleanLayout":true}"""))
        assertFalse(applied.contains("cleanLayout"))
    }
}
