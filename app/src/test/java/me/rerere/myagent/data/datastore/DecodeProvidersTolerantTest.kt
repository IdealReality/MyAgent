package me.rerere.myagent.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Covers `decodeProvidersTolerant`, the per-entry decode of the persisted `providers` array.
 *
 * Regression this pins: removing a provider type from `ProviderSetting` (Agents Anywhere was
 * deleted along with its `@SerialName("agents_anywhere")` subclass) leaves that discriminator in
 * the stored JSON of every install that ever created such a row. A list-level
 * `decodeFromString<List<ProviderSetting>>` throws on the unknown discriminator and takes the
 * whole list with it — the user loses every saved provider (API keys, custom models). The
 * per-entry decode must skip the dead row and keep the rest exactly as stored.
 */
class DecodeProvidersTolerantTest {

    private val preferencesStoreKt = Class.forName("me.rerere.myagent.data.datastore.PreferencesStoreKt")

    private fun decode(raw: String): List<ProviderSetting> {
        val method = preferencesStoreKt.getDeclaredMethod("decodeProvidersTolerant", String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(null, raw) as List<ProviderSetting>
    }

    @Test
    fun `a row of a removed provider type is skipped, the rest survive`() {
        val keptId = Uuid.parse("11111111-2222-3333-4444-555555555555")
        val json = """
            [
              {"type":"agents_anywhere","id":"9d3f5c71-8b24-4a6e-b0d5-3c7e91a24f68",
               "enabled":false,"name":"Agents Anywhere","models":[],
               "serverOrigin":"https://arch-linux.example:8443","accessToken":"t",
               "connectorId":"c","projectId":"p","runtimeId":"r"},
              {"type":"openai","id":"$keptId","enabled":true,"name":"我的中转",
               "apiKey":"sk-real","models":[]}
            ]
        """.trimIndent()

        val decoded = decode(json)

        assertEquals(1, decoded.size)
        assertEquals(keptId, decoded.single().id)
        assertTrue(decoded.single() is ProviderSetting.OpenAI)
        assertEquals("sk-real", (decoded.single() as ProviderSetting.OpenAI).apiKey)
    }

    @Test
    fun `a list that only held the removed row decodes to empty instead of throwing`() {
        val json = """[{"type":"agents_anywhere","id":"9d3f5c71-8b24-4a6e-b0d5-3c7e91a24f68"}]"""

        assertEquals(emptyList<ProviderSetting>(), decode(json))
    }

    @Test
    fun `blank and non-array payloads decode to empty`() {
        assertEquals(emptyList<ProviderSetting>(), decode(""))
        assertEquals(emptyList<ProviderSetting>(), decode("{}"))
    }
}
