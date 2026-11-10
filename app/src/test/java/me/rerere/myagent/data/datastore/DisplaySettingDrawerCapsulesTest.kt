package me.rerere.myagent.data.datastore

import me.rerere.myagent.utils.JsonInstant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawer's capsule chrome is off by default: the two floating cards are the myagent look.
 * Settings stored before the field existed must read back as off, and switching it on must
 * round-trip so the capsule chrome comes back.
 */
class DisplaySettingDrawerCapsulesTest {

    @Test
    fun `drawer capsules are off by default`() {
        assertFalse(DisplaySetting().drawerCapsules)
    }

    @Test
    fun `stored settings without the key read back as off`() {
        val legacy = """{"cleanLayout":true}"""
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(legacy)
        assertFalse(decoded.drawerCapsules)
    }

    @Test
    fun `turning it on round-trips`() {
        val json = JsonInstant.encodeToString(DisplaySetting(drawerCapsules = true))
        assertTrue(json.contains("\"drawerCapsules\":true"))
        assertTrue(JsonInstant.decodeFromString<DisplaySetting>(json).drawerCapsules)
    }
}