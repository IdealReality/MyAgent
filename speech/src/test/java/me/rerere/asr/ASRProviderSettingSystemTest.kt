package me.rerere.asr

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * The system recognizer is the zero-configuration default for chat voice input, so two
 * properties matter: it must round-trip through persisted settings, and it must not be
 * mistaken for a server-VAD provider (the voice-mode path assumes a cloud stream).
 */
class ASRProviderSettingSystemTest {
    @Test fun `defaults follow device locale and prefer on-device recognition`() {
        val provider = ASRProviderSetting.System()
        assertEquals("", provider.language)
        assertTrue(provider.useOnDevice)
        assertFalse(provider.supportsServerVadVoiceMode)
    }

    @Test fun `settings round trip through json`() {
        val original = ASRProviderSetting.System(
            name = "phone",
            language = "zh-CN",
            useOnDevice = false,
        )
        val encoded = Json.encodeToString<ASRProviderSetting>(original)
        val decoded = Json.decodeFromString<ASRProviderSetting>(encoded)
        assertEquals(original, decoded)
    }

    @Test fun `copyProvider keeps id and settings while renaming`() {
        val provider = ASRProviderSetting.System(language = "ja-JP")
        val copy = provider.copyProvider(name = "renamed") as ASRProviderSetting.System
        assertEquals("renamed", copy.name)
        assertEquals(provider.id, copy.id)
        assertEquals("ja-JP", copy.language)
        assertTrue(copy.useOnDevice)
    }
}
