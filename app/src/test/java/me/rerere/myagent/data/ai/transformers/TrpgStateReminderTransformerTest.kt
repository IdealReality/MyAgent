package me.rerere.myagent.data.ai.transformers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Coverage for [buildTrpgStateBlock] — the read-only `<trpg_state>` snapshot the transformer
 * builds from the `trpg` plugin's key/value file.
 *
 * Contract: nothing is produced when the plugin has written no state (a session that never
 * started must cost zero tokens), and what IS produced carries the numbers that stop the model
 * contradicting itself: HP/SAN bars, clocks (with an explicit "full" marker), and the last roll.
 */
class TrpgStateReminderTransformerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var fileCounter = 0
    private fun kvFile(content: String): File =
        tmp.newFile("trpg-${fileCounter++}.json").apply { writeText(content) }

    @Test
    fun `missing file yields no block`() {
        val missing = File(tmp.root, "nope.json")
        assertNull(buildTrpgStateBlock(missing, maxChars = 1200))
    }

    @Test
    fun `empty plugin store yields no block`() {
        val file = kvFile("""{"rng.counter":"7"}""")
        assertNull(buildTrpgStateBlock(file, maxChars = 1200))
    }

    @Test
    fun `sheet scene clocks and last roll are all rendered`() {
        val file = kvFile(
            """
            {
              "rng.counter": "12",
              "trpg.sheets": "{\"凯尔\":{\"system\":\"coc7e\",\"hp\":{\"cur\":9,\"max\":12},\"san\":{\"cur\":42,\"max\":60},\"luck\":45,\"status\":[\"轻伤\"],\"items\":[\"手电筒\",\"左轮\"]}}",
              "trpg.scene": "{\"campaign\":\"暗夜之门\",\"act\":2,\"location\":\"地下室\",\"time\":\"03:20\",\"npcs\":[\"管家莫里斯\"],\"clues\":[\"地毯上的泥脚印\"],\"facts\":[{\"n\":1,\"text\":\"挂钟停在 3:07\"}],\"clocks\":{\"仪式进度\":{\"value\":3,\"max\":6}}}",
              "trpg.rolls": "[{\"system\":\"coc7e\",\"label\":\"侦查\",\"target\":55,\"roll\":37,\"level\":\"困难成功\"}]"
            }
            """.trimIndent()
        )

        val block = buildTrpgStateBlock(file, maxChars = 4000)
        assertNotNull(block)
        val text = block!!

        assertTrue(text, text.startsWith("<trpg_state>"))
        assertTrue(text, text.trimEnd().endsWith("不要在回复里复述本块。"))
        assertTrue("scene line", text.contains("暗夜之门 · 第2幕 · @地下室 · 🕒 03:20"))
        assertTrue("npc", text.contains("在场: 管家莫里斯"))
        assertTrue("clue", text.contains("线索: 地毯上的泥脚印"))
        assertTrue("fact", text.contains("最近事实: 挂钟停在 3:07"))
        assertTrue("clock", text.contains("钟: 仪式进度 3/6"))
        assertTrue("hp/san", text.contains("HP 9/12 SAN 42/60"))
        assertTrue("status/items", text.contains("状态: 轻伤") && text.contains("物品: 手电筒、左轮"))
        assertTrue("last roll", text.contains("最近掷骰: 侦查 目标 55 → 37 困难成功"))
    }

    @Test
    fun `a full clock is called out`() {
        val file = kvFile(
            """{"trpg.scene":"{\"campaign\":\"X\",\"clocks\":{\"追兵\":{\"value\":4,\"max\":4}}}"}"""
        )
        val text = buildTrpgStateBlock(file, maxChars = 2000)!!
        assertTrue(text, text.contains("追兵 4/4（已满）"))
    }

    @Test
    fun `scene without campaign still reports what it has`() {
        val file = kvFile("""{"trpg.scene":"{\"location\":\"门厅\"}"}""")
        val text = buildTrpgStateBlock(file, maxChars = 2000)!!
        assertTrue(text, text.contains("场景: @门厅"))
    }

    @Test
    fun `malformed json fails closed instead of throwing`() {
        assertNull(buildTrpgStateBlock(kvFile("not json"), maxChars = 1200))
        // A valid KV envelope with broken inner JSON must also stay silent.
        assertNull(buildTrpgStateBlock(kvFile("""{"trpg.scene":"{oops"}"""), maxChars = 1200))
    }

    @Test
    fun `over-long state is truncated and still closed`() {
        val longItems = (1..60).joinToString(",") { "\"物品$it\"" }
        val sheets = "{\"凯尔\":{\"hp\":{\"cur\":1,\"max\":10},\"items\":[$longItems]}}"
        val facts = (1..40).joinToString(",") { "{\"n\":$it,\"text\":\"很长的已发生事实第 $it 条，用来把快照撑长\"}" }
        val scene = "{\"campaign\":\"长模组\",\"facts\":[$facts]}"
        val file = kvFile("{\"trpg.sheets\":\"${sheets.replace("\"", "\\\"")}\",\"trpg.scene\":\"${scene.replace("\"", "\\\"")}\"}")

        val text = buildTrpgStateBlock(file, maxChars = 260)!!
        assertTrue("within budget: ${text.length}", text.length <= 260 + 64)
        assertTrue("keeps the sheet half", text.contains("HP 1/10"))
        assertTrue("closes the block", text.contains("</trpg_state>"))
        assertTrue("marks truncation", text.contains("已截断"))
        assertFalse("does not double-close", text.contains("</trpg_state></trpg_state>"))
    }
}
