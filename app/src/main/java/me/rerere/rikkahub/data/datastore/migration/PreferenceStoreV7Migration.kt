package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.rikkahub.data.datastore.SettingsStore

/** 本 fork 历史上的默认主题；升级时只有它会被换成 [MONO_THEME]。 */
private const val LEGACY_DEFAULT_THEME = "sakura"

/** 新的默认主题：黑白单色。 */
private const val MONO_THEME = "mono"

/**
 * 决定升级后的主题 id：返回 null 表示保持原值。
 *
 * 只迁移 `sakura` 与「从未写过 theme_id」两种情况。用户主动选过的其他主题（含自定义主题）
 * 一律保留，避免把「我特意选的」覆盖掉。
 */
internal fun migratedThemeId(current: String?): String? =
    if (current == null || current == LEGACY_DEFAULT_THEME) MONO_THEME else null

/**
 * 把仍在旧默认主题（sakura）上的安装切到黑白 mono 主题。
 *
 * 为什么不只改 Kotlin 默认值：[Settings.themeId] 的默认只在首次安装生效，升级后的安装会
 * 原样读回存储里的 `theme_id`。若不迁移，老用户（包括本机）升级后看到的还是原来那套粉色。
 */
class PreferenceStoreV7Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 7
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        migratedThemeId(prefs[SettingsStore.THEME_ID])?.let { prefs[SettingsStore.THEME_ID] = it }
        prefs[SettingsStore.VERSION] = 7
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
