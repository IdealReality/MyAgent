package me.rerere.myagent.plugin

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `ui.slots` 是聊天内界面的契约：位置/类型拼错必须报错（否则是永久不显示的界面），
 * HTML 入口必须真的存在，按钮动作与面板入口二选一。
 */
class PluginSlotsManifestTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun manifest(ui: String, extra: String = ""): String =
        """{"schema_version":1,"id":"demo","name":"Demo","version":"1.0.0","entry":"main.js",
           "permissions":[],"ui":$ui$extra}"""

    private fun validate(raw: String, dir: File? = null): PluginValidation =
        PluginValidationRules.validate(json.decodeFromString(PluginManifest.serializer(), raw), dir)

    private fun pluginDir(withSlotHtml: Boolean): File {
        val dir = tempFolder.newFolder("demo-${System.nanoTime()}")
        File(dir, "main.js").writeText("// js")
        if (withSlotHtml) File(dir, "card.html").writeText("<div>hi</div>")
        return dir
    }

    @Test
    fun `chips slot with declared actions validates`() {
        val validation = validate(
            manifest(
                """{"slots":[{"where":"composer","type":"chips","title":"快捷",
                    "items":[{"label":"重写","action":"rewrite"},{"label":"面板","panel":""}]}]}"""
            ),
            pluginDir(withSlotHtml = false),
        )
        assertTrue(validation.render(), validation.ok)
    }

    @Test
    fun `html slot requires an existing file`() {
        val dir = pluginDir(withSlotHtml = true)
        val ok = validate(
            manifest("""{"slots":[{"where":"message","type":"html","file":"card.html","height":80}]}"""),
            dir,
        )
        assertTrue(ok.render(), ok.ok)

        val missing = validate(
            manifest("""{"slots":[{"where":"message","type":"html","file":"nope.html"}]}"""),
            dir,
        )
        assertFalse(missing.ok)
        assertTrue(missing.render(), missing.render().contains("ui.slots[0].file"))
    }

    @Test
    fun `unknown where and type are errors`() {
        val where = validate(manifest("""{"slots":[{"where":"sidebar","type":"chips","items":[{"label":"a","action":"a"}]}]}"""))
        assertFalse(where.ok)
        assertTrue(where.render(), where.render().contains("ui.slots[0].where"))

        val type = validate(manifest("""{"slots":[{"where":"composer","type":"canvas","items":[{"label":"a","action":"a"}]}]}"""))
        assertFalse(type.ok)
        assertTrue(type.render(), type.render().contains("ui.slots[0].type"))
    }

    @Test
    fun `chip item must pick exactly one of action or panel`() {
        val neither = validate(manifest("""{"slots":[{"where":"composer","type":"chips","items":[{"label":"a"}]}]}"""))
        assertFalse(neither.ok)
        assertTrue(neither.render(), neither.render().contains("needs either action or panel"))

        val both = validate(
            manifest(
                """{"slots":[{"where":"composer","type":"chips","items":[{"label":"a","action":"a","panel":"demo"}]}]}"""
            )
        )
        assertFalse(both.ok)
        assertTrue(both.render(), both.render().contains("mutually exclusive"))
    }

    @Test
    fun `action must be a js identifier and panel must be a plugin id`() {
        val badAction = validate(
            manifest("""{"slots":[{"where":"composer","type":"chips","items":[{"label":"a","action":"not valid!"}]}]}""")
        )
        assertFalse(badAction.ok)
        assertTrue(badAction.render(), badAction.render().contains("items[0].action"))

        val badPanel = validate(
            manifest("""{"slots":[{"where":"composer","type":"chips","items":[{"label":"a","panel":"Bad/Id"}]}]}""")
        )
        assertFalse(badPanel.ok)
        assertTrue(badPanel.render(), badPanel.render().contains("items[0].panel"))
    }

    @Test
    fun `height is clamped with a warning not an error`() {
        val validation = validate(manifest("""{"slots":[{"where":"composer","type":"html","file":"card.html","height":9999}]}"""), pluginDir(withSlotHtml = true))
        assertTrue(validation.render(), validation.ok)
        assertTrue(validation.render(), validation.render().contains("height=9999 clamped"))
    }

    @Test
    fun `all_messages only applies to message slots`() {
        val validation = validate(
            manifest("""{"slots":[{"where":"composer","type":"chips","all_messages":true,"items":[{"label":"a","action":"a"}]}]}""")
        )
        assertTrue(validation.ok)
        assertTrue(validation.render(), validation.render().contains("all_messages only applies"))
    }

    @Test
    fun `html slot may declare actions but not panels`() {
        val dir = pluginDir(withSlotHtml = true)
        val ok = validate(
            manifest("""{"slots":[{"where":"message","type":"html","file":"card.html",
                "items":[{"action":"refresh"}]}]}"""),
            dir,
        )
        assertTrue(ok.render(), ok.ok)

        val blank = validate(
            manifest("""{"slots":[{"where":"message","type":"html","file":"card.html","items":[{"label":"x"}]}]}"""),
            dir,
        )
        assertFalse(blank.ok)
        assertTrue(blank.render(), blank.render().contains("items[0].action is required"))

        val withPanel = validate(
            manifest("""{"slots":[{"where":"message","type":"html","file":"card.html","items":[{"action":"a","panel":"demo"}]}]}"""),
            dir,
        )
        assertFalse(withPanel.ok)
        assertTrue(withPanel.render(), withPanel.render().contains("panel is not allowed"))
    }

    @Test
    fun `too many slots is an error`() {
        val slots = (0 until PluginValidationRules.MAX_SLOTS + 1).joinToString(",") {
            """{"where":"composer","type":"chips","items":[{"label":"a$it","action":"a$it"}]}"""
        }
        val validation = validate(manifest("""{"slots":[$slots]}"""))
        assertFalse(validation.ok)
        assertTrue(validation.render(), validation.render().contains("too many ui.slots"))
    }

    @Test
    fun `slots alone count as a contribution`() {
        val validation = validate(
            manifest("""{"slots":[{"where":"composer","type":"chips","items":[{"label":"a","action":"a"}]}]}""")
        )
        assertTrue(validation.ok)
        assertFalse(validation.render(), validation.render().contains("contributes nothing"))
    }

    @Test
    fun `slot fields survive decoding`() {
        val decoded = json.decodeFromString(
            PluginManifest.serializer(),
            manifest(
                """{"panel":"panel.html","slots":[{"where":"message","type":"html","file":"card.html",
                    "height":96,"all_messages":true,"items":[]}]}"""
            ),
        )
        val slot = decoded.ui!!.slots.single()
        assertTrue(slot.where == PluginSlotWhere.MESSAGE)
        assertTrue(slot.allMessages)
        assertTrue(slot.height == 96)
    }
}
