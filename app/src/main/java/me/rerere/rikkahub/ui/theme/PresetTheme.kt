package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import me.rerere.rikkahub.ui.theme.presets.AutumnThemePreset
import me.rerere.rikkahub.ui.theme.presets.BlackThemePreset
import me.rerere.rikkahub.ui.theme.presets.ClaudeThemePreset
import me.rerere.rikkahub.ui.theme.presets.MinimalThemePreset
import me.rerere.rikkahub.ui.theme.presets.MonoThemePreset
import me.rerere.rikkahub.ui.theme.presets.OceanThemePreset
import me.rerere.rikkahub.ui.theme.presets.SakuraThemePreset
import me.rerere.rikkahub.ui.theme.presets.SpringThemePreset

data class PresetTheme(
    val id: String,
    val name: @Composable () -> Unit,
    val standardLight: ColorScheme,
    val standardDark: ColorScheme,
) {
    fun getColorScheme(dark: Boolean): ColorScheme {
        return if (dark) standardDark else standardLight
    }
}

val PresetThemes by lazy {
    listOf(
        // mono 是产品现在的默认主题（见 Settings.themeId 与 PreferencesStore 的读取路径）。
        MonoThemePreset,
        SakuraThemePreset,
        OceanThemePreset,
        SpringThemePreset,
        AutumnThemePreset,
        BlackThemePreset,
        MinimalThemePreset,
        ClaudeThemePreset,
    )
}

fun findPresetTheme(id: String): PresetTheme {
    // 缺失/未知 id 回落到首位（mono），与 Settings.themeId 的默认值保持一致。
    return PresetThemes.find { it.id == id } ?: PresetThemes.first()
}

fun findThemeById(id: String, customThemes: List<CustomTheme>): PresetTheme? {
    PresetThemes.find { it.id == id }?.let { return it }
    val custom = customThemes.find { it.id == id } ?: return null
    return PresetTheme(
        id = custom.id,
        name = { androidx.compose.material3.Text(custom.name) },
        standardLight = custom.generateColorScheme(dark = false),
        standardDark = custom.generateColorScheme(dark = true),
    )
}
