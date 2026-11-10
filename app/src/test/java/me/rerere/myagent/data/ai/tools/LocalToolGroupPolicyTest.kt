package me.rerere.myagent.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The direct/catalogued split decides what every single request has to carry, so its two
 * invariants are worth pinning down:
 *
 *  1. Nothing is ever dropped — every enabled option lands on exactly one side. A group that
 *     fell through both sides would silently remove tools from the model's reach.
 *  2. Order is stable. The direct list becomes the prompt prefix, so shuffling it per run
 *     would break prompt caching without changing behaviour.
 */
class LocalToolGroupPolicyTest {

    @Test
    fun `a group is in exactly one side`() {
        val options = listOf(
            LocalToolOption.TimeInfo,
            LocalToolOption.TelegramBot,
            LocalToolOption.Ssh,
            LocalToolOption.Shizuku,
        )
        val partition = LocalToolGroupPolicy.partition(options)
        assertEquals(options.size, partition.direct.size + partition.catalogued.size)
        assertTrue(partition.direct.contains(LocalToolOption.TimeInfo))
        assertTrue(partition.direct.contains(LocalToolOption.Ssh))
        assertTrue(partition.catalogued.contains(LocalToolOption.TelegramBot))
        assertTrue(partition.catalogued.contains(LocalToolOption.Shizuku))
    }

    @Test
    fun `partition preserves the caller's order on both sides`() {
        val options = listOf(
            LocalToolOption.TelegramBot,
            LocalToolOption.TimeInfo,
            LocalToolOption.Shizuku,
            LocalToolOption.Clipboard,
        )
        val partition = LocalToolGroupPolicy.partition(options)
        assertEquals(listOf(LocalToolOption.TimeInfo, LocalToolOption.Clipboard), partition.direct)
        assertEquals(listOf(LocalToolOption.TelegramBot, LocalToolOption.Shizuku), partition.catalogued)
    }

    @Test
    fun `the expensive groups are catalogued`() {
        // These three account for 34 of the 196 tool definitions. Keeping any of them direct
        // would reintroduce the per-request schema cost this policy exists to remove.
        for (heavy in listOf(
            LocalToolOption.TelegramBot,
            LocalToolOption.ScreenAutomation,
            LocalToolOption.McpControl,
        )) {
            assertTrue("$heavy should be catalogued", LocalToolGroupPolicy.isCatalogued(heavy))
        }
    }

    @Test
    fun `files is direct because most tasks touch it`() {
        assertFalse(LocalToolGroupPolicy.isCatalogued(LocalToolOption.Files))
    }

    @Test
    fun `an empty option list yields two empty sides`() {
        val partition = LocalToolGroupPolicy.partition(emptyList())
        assertTrue(partition.direct.isEmpty())
        assertTrue(partition.catalogued.isEmpty())
    }

    @Test
    fun `the direct set is small enough to stay a cheap prefix`() {
        // A guard against future drift: the whole point is that the always-on surface stays
        // small. If this trips, the new entry needs a justification, not a bigger bound.
        assertTrue(
            "ALWAYS_DIRECT grew to ${LocalToolGroupPolicy.ALWAYS_DIRECT.size} groups",
            LocalToolGroupPolicy.ALWAYS_DIRECT.size <= 12,
        )
    }
}
