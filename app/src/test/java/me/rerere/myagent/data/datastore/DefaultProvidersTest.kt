package me.rerere.myagent.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class DefaultProvidersTest {
    @Test
    fun `no providers ship pre-seeded`() {
        // The app ships no bundled providers: every row in the list is one the user created.
        // A non-empty seed list would put vendor rows back on fresh installs, and the
        // PreferencesStore re-seed pass would then also inject them into existing installs.
        assertEquals(emptyList<ProviderSetting>(), DEFAULT_PROVIDERS)
    }

    @Test
    fun `both sweep sets are non-empty and disjoint`() {
        // Two independent sources feed the one-shot cleanup: providers this fork used to seed
        // and the upstream sponsored shelf that was dropped before that. Both must survive as
        // literals — an id leaving either set means an upgraded install keeps that dead row.
        assertTrue(LEGACY_BUNDLED_PROVIDER_IDS.isNotEmpty())
        assertTrue(REMOVED_SPONSORED_PROVIDER_IDS.isNotEmpty())
        assertTrue(LEGACY_BUNDLED_PROVIDER_IDS.intersect(REMOVED_SPONSORED_PROVIDER_IDS).isEmpty())
    }

    @Test
    fun `legacy sweep covers every provider type the old build seeded`() {
        // The sweep must be able to recognise a leftover row of each protocol, including the
        // on-device singletons (whose ids are also the ones the runtime UI looks up).
        val expectedTypes = setOf(
            ProviderSetting.OpenAI::class,
            ProviderSetting.Google::class,
            ProviderSetting.Claude::class,
            ProviderSetting.Codex::class,
            ProviderSetting.Grok::class,
            ProviderSetting.GeminiOAuth::class,
            ProviderSetting.AICore::class,
            ProviderSetting.LiteRtLocal::class,
            ProviderSetting.LlamaCppLocal::class,
        )
        // Every creatable type must be creatable, or a capability is unreachable now that the
        // provider list starts empty.
        val creatable = ProviderSetting.creatableTypes.map { it()::class }.toSet()
        assertEquals(expectedTypes, creatable)

        // On-device runtimes are singletons: the runtime screens look their row up by a fixed
        // id, so those ids must resolve while the row is missing (see updateMyProvider).
        assertTrue(LOCAL_RUNTIME_PROVIDER_IDS.containsAll(
            listOf(
                me.rerere.ai.provider.AICORE_PROVIDER_ID,
                me.rerere.ai.provider.LITERT_PROVIDER_ID,
                me.rerere.ai.provider.LLAMACPP_PROVIDER_ID,
            )
        ))
    }

    @Test
    fun `creatable types produce rows the user can hold on to`() {
        // A creatable row must carry a usable id and a non-blank default name; a factory that
        // returned an anonymous/degenerate row would show up as a blank card in the list.
        ProviderSetting.creatableTypes.forEach { factory ->
            val provider = factory()
            assertTrue(
                "blank default name for ${provider::class.simpleName}",
                provider.name.isNotBlank(),
            )
            assertTrue(
                "id must parse round-trip for ${provider::class.simpleName}",
                Uuid.parse(provider.id.toString()) == provider.id,
            )
        }
    }
}
