package me.rerere.rikkahub.data.datastore.migration

import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Drops the display-settings values that this fork used to write as *its own defaults*, so the
 * rikkahub-style defaults take over on upgraded installs.
 *
 * Why a migration instead of just changing the `DisplaySetting` defaults: `DisplaySetting` is
 * persisted as one JSON blob, so an upgrade reads back whatever the old build stored — including
 * values the user never touched. Changing a Kotlin default therefore only affects fresh installs.
 *
 * The test for "never touched" is exact equality with the old default. A user who really wanted
 * the liquid-glass composer or the expressive menu picked a value that differs from it, and that
 * pick is kept.
 */
class PreferenceStoreV6Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 6
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val raw = prefs[SettingsStore.DISPLAY_SETTING]
        val cleaned = raw?.let(::stripLegacyDefaults)
        if (cleaned != null && cleaned != raw) {
            prefs[SettingsStore.DISPLAY_SETTING] = cleaned
        }
        prefs[SettingsStore.VERSION] = 6
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}

    private fun stripLegacyDefaults(json: String): String? = runCatching {
        val root = JsonInstant.parseToJsonElement(json).jsonObject.toMutableMap()
        var dropped = false
        LEGACY_DEFAULTS.forEach { (key, legacy) ->
            val current = root[key] ?: return@forEach
            if (current is JsonPrimitive && current == legacy) {
                root.remove(key)
                dropped = true
            }
        }
        if (dropped) JsonInstant.encodeToString(JsonObject(root)) else json
    }.getOrElse {
        Log.w(TAG, "Failed to strip legacy display defaults", it)
        null
    }

    private companion object {
        const val TAG = "PreferenceStoreV6"

        /** Field name in `DisplaySetting` → the value this fork used to default it to. */
        val LEGACY_DEFAULTS: Map<String, JsonPrimitive> = mapOf(
            "cleanLayoutGlass" to JsonPrimitive(true),
            "drawerCapsules" to JsonPrimitive(true),
            "useAppIconStyleLoadingIndicator" to JsonPrimitive(true),
            "popupMenuStyle" to JsonPrimitive("expressive"),
        )
    }
}
