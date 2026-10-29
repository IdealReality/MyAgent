package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

/**
 * My Agent：乳白 / 黑灰两套基底，红→紫→蓝作为唯一身份色。
 *
 * 三色分工（不是装饰，各司其职）：
 *  - primary   紫  —— 主操作、选中态、焦点
 *  - secondary 蓝  —— 次级信息、链接
 *  - tertiary  红  —— 需要注意的强调（避开 error 语义）
 *
 * 基底刻意接近中性：光态 #F6F4F1、暗态 #121214，让三色跳出来而非被底色染色。
 */
val MyAgentThemePreset by lazy {
    PresetTheme(
        id = "myagent",
        name = {
            Text(stringResource(id = R.string.theme_name_myagent))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

// ---- light: 乳白基底 --------------------------------------------------------
private val primaryLight = Color(0xFFC0346B)          // 紫
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFFBE0EA)
private val onPrimaryContainerLight = Color(0xFF7A1038)
private val secondaryLight = Color(0xFF6B3FC8)        // 蓝
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFE9E1FA)
private val onSecondaryContainerLight = Color(0xFF3D1F82)
private val tertiaryLight = Color(0xFF2B6FD6)         // 红
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFDDE9FB)
private val onTertiaryContainerLight = Color(0xFF15407F)
private val errorLight = Color(0xFFB3362B)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFF7E5E2)
private val onErrorContainerLight = Color(0xFF7C231A)

private val backgroundLight = Color(0xFFF8F4F6)       // 乳白（略偏红，接住底布渐变起点）
private val onBackgroundLight = Color(0xFF16161A)
private val surfaceLight = Color(0xFFF8F4F6)
private val onSurfaceLight = Color(0xFF16161A)
private val surfaceVariantLight = Color(0xFFEFEAF1)
private val onSurfaceVariantLight = Color(0xFF5E5E68)
private val outlineLight = Color(0xFFB9B6B1)
private val outlineVariantLight = Color(0xFFDCD8D3)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF2A2A30)
private val inverseOnSurfaceLight = Color(0xFFF2F1EE)
private val inversePrimaryLight = Color(0xFFFF9CB8)
private val surfaceDimLight = Color(0xFFF7EDEE)
private val surfaceBrightLight = Color(0xFFFDFBF9)
// 表面阶梯按 红 → 紫 → 蓝 分配极淡色相：卡片堆得越「高」，色相越往后走。
// 这是「整体渐变」在 ColorScheme 侧的一半——另一半是 LocalAmbientBrush / LocalAccentBrush。
private val surfaceContainerLowestLight = Color(0xFFFDF9FA)   // 偏红
private val surfaceContainerLowLight = Color(0xFFFAF5F7)      // 红紫之间
private val surfaceContainerLight = Color(0xFFF7F3FA)         // 偏紫
private val surfaceContainerHighLight = Color(0xFFF3F3FC)     // 紫蓝之间
private val surfaceContainerHighestLight = Color(0xFFEFF2FC)  // 偏蓝

// ---- dark: 黑灰基底 --------------------------------------------------------
private val primaryDark = Color(0xFFFF7A9E)           // 紫（提亮以过对比度）
private val onPrimaryDark = Color(0xFF4A0A22)
private val primaryContainerDark = Color(0xFF7A2148)
private val onPrimaryContainerDark = Color(0xFFFFD9E4)
private val secondaryDark = Color(0xFFB98CFF)         // 蓝
private val onSecondaryDark = Color(0xFF2A1060)
private val secondaryContainerDark = Color(0xFF43217A)
private val onSecondaryContainerDark = Color(0xFFECDDFF)
private val tertiaryDark = Color(0xFF7FB2FF)          // 红
private val onTertiaryDark = Color(0xFF0A2454)
private val tertiaryContainerDark = Color(0xFF1E3C74)
private val onTertiaryContainerDark = Color(0xFFD6E4FF)
private val errorDark = Color(0xFFFF8A80)
private val onErrorDark = Color(0xFF5C120C)
private val errorContainerDark = Color(0xFF8C1F16)
private val onErrorContainerDark = Color(0xFFFFDAD5)

private val backgroundDark = Color(0xFF0B0A0F)        // 黑灰（略偏红）
private val onBackgroundDark = Color(0xFFF2F1EE)
private val surfaceDark = Color(0xFF0B0A0F)
private val onSurfaceDark = Color(0xFFF2F1EE)
private val surfaceVariantDark = Color(0xFF2A2634)
private val onSurfaceVariantDark = Color(0xFFA8A7B0)
private val outlineDark = Color(0xFF4C4B52)
private val outlineVariantDark = Color(0xFF2E2D32)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFF2F1EE)
private val inverseOnSurfaceDark = Color(0xFF2A2A30)
private val inversePrimaryDark = Color(0xFFC0346B)
private val surfaceDimDark = Color(0xFF141218)
private val surfaceBrightDark = Color(0xFF3A3547)
// 暗色同样按 红 → 紫 → 蓝 走；暗底上色相只能给一点点，否则整屏显脏。
private val surfaceContainerLowestDark = Color(0xFF08070C)                       // 偏红
private val surfaceContainerLowDark = Color(0xFF121017)                          // 红紫之间
private val surfaceContainerDark = Color(0xFF191622)                             // 偏紫
private val surfaceContainerHighDark = Color(0xFF231F2F)                         // 紫蓝之间
private val surfaceContainerHighestDark = Color(0xFF2C2839)                      // 偏蓝

private val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

private val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)
