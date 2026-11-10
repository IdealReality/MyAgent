package me.rerere.myagent.data.datastore

import me.rerere.asr.ASRProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chat voice input must work on a fresh install: no cloud provider, no API key. These
 * assertions pin that contract — if the defaults ever go back to an empty list the
 * microphone silently disappears from the composer again.
 */
class DefaultASRProvidersTest {
    @Test
    fun `fresh settings select the system recognizer`() {
        val settings = Settings()

        val selected = settings.getSelectedASRProvider()
        assertNotNull("a fresh install must have a usable ASR provider", selected)
        assertTrue(
            "default voice input must not require an API key, was ${selected!!::class.simpleName}",
            selected is ASRProviderSetting.System,
        )
        assertEquals(selected.id, settings.selectedASRProviderId)
    }

    @Test
    fun `system recognizer default name falls back to the localized label`() {
        // The settings page renders R.string.setting_asr_page_default_name when the name is
        // blank, so the default provider must stay nameless rather than hardcoding English.
        val system = Settings().asrProviders.filterIsInstance<ASRProviderSetting.System>()
        assertEquals(1, system.size)
        assertEquals("", system.single().name)
    }
}
