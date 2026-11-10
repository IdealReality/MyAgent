package me.rerere.myagent.data.datastore

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Companion to [SettingsPersistenceCoverageTest]: a key can be *declared* and *written* by
 * `persistSettings()` yet never read back in `settingsFlowRaw`. The field then keeps its
 * in-memory value until the next launch, where the constructor default silently wins.
 *
 * Shipped bug of exactly that shape: `dynamicColor` — the read line was dropped in #122, so
 * turning dynamic color off reverted to on after every restart.
 *
 * Reflection cannot see which keys the `map { preferences -> Settings(...) }` block reads, so
 * this scans the source: every `preferences[KEY] = ...` write must have a matching read.
 *
 * It deliberately asserts the *pairing*, not the expression each key is decoded with: a
 * `source.contains("dynamicColor = preferences[DYNAMIC_COLOR] != false")` pin used to live
 * here and turned red the moment the default flipped to `== true` — a behaviour change, not a
 * regression, and one the write/read pairing above already guards.
 */
class SettingsReadBackCoverageTest {

    private val source = File("src/main/java/me/rerere/myagent/data/datastore/PreferencesStore.kt").readText()

    private val writes = Regex("""preferences\[([A-Z0-9_]+)]\s*=(?!=)""")
    private val reads = Regex("""preferences\[([A-Z0-9_]+)](?!\s*=(?!=))""")

    @Test
    fun `every persisted preference key is read back`() {
        val written = writes.findAll(source).map { it.groupValues[1] }.toSet()
        val read = reads.findAll(source).map { it.groupValues[1] }.toSet()

        assertEquals(
            "这些 key 在 persistSettings 里写入了, 但 settingsFlowRaw 没读回: 重启后会回到默认值。",
            emptyList<String>(),
            (written - read).sorted(),
        )
    }
}
