package me.rerere.myagent.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.myagent.R
import me.rerere.myagent.ui.theme.PresetTheme

/**
 * 黑白单色：版式、表面、正文、主色没有色相，只有灰阶 —— 层级全靠明度差与 1px 描边表达。
 *
 * 例外是**语义色**：错误、成功、警告、信息不是装饰。把「失败」也画成灰，用户就得读文案
 * 才知道出了错，而错误态恰恰是最不该要求仔细阅读的地方。所以这里保留一支真正的红
 * （色相 25°，与扩展色板红族同源），扩展色板四族同理，
 * 见 [me.rerere.myagent.ui.theme.lightMonoExtendColors]。
 *
 * 该主题同时是默认主题，[me.rerere.myagent.ui.theme.LocalMonoTheme] 为真，
 * 装饰面（渐变背景、彩色光斑、玻璃色散、加载动画）都会被同一个开关压成灰阶。
 */
val MonoThemePreset by lazy {
    PresetTheme(
        id = "mono",
        name = {
            Text(stringResource(id = R.string.theme_name_mono))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

private val primaryLight = Color(0xFF111111)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFEFEFEF)
private val onPrimaryContainerLight = Color(0xFF111111)
private val secondaryLight = Color(0xFF555555)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFF1F1F1)
private val onSecondaryContainerLight = Color(0xFF1C1C1C)
private val tertiaryLight = Color(0xFF333333)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFEAEAEA)
private val onTertiaryContainerLight = Color(0xFF1C1C1C)
// error 是全主题唯一保留色相的角色：语义色不是装饰，见文件头注释。
// 取值与扩展色板红族同一条色阶（红 8 的 0xFF9F131D 再深一档），保证「错误」在两个入口
// ——ColorScheme.error 与 extendColors.red8——看起来是同一个红，而不是一砖一朱。
private val errorLight = Color(0xFFB3080F)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFFFE4E1)
private val onErrorContainerLight = Color(0xFF7A0A15)
private val backgroundLight = Color(0xFFFFFFFF)
private val onBackgroundLight = Color(0xFF111111)
private val surfaceLight = Color(0xFFFFFFFF)
private val onSurfaceLight = Color(0xFF111111)
private val surfaceVariantLight = Color(0xFFF2F2F2)
private val onSurfaceVariantLight = Color(0xFF666666)
private val outlineLight = Color(0xFFC4C4C4)
private val outlineVariantLight = Color(0xFFEAEAEA)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF1C1C1C)
private val inverseOnSurfaceLight = Color(0xFFF2F2F2)
private val inversePrimaryLight = Color(0xFFD8D8D8)
private val surfaceDimLight = Color(0xFFEDEDED)
private val surfaceBrightLight = Color(0xFFFFFFFF)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFFAFAFA)
private val surfaceContainerLight = Color(0xFFF6F6F6)
private val surfaceContainerHighLight = Color(0xFFF0F0F0)
private val surfaceContainerHighestLight = Color(0xFFE9E9E9)

private val primaryDark = Color(0xFFF2F2F2)
private val onPrimaryDark = Color(0xFF161616)
private val primaryContainerDark = Color(0xFF2A2A2A)
private val onPrimaryContainerDark = Color(0xFFE6E6E6)
private val secondaryDark = Color(0xFFC6C6C6)
private val onSecondaryDark = Color(0xFF1A1A1A)
private val secondaryContainerDark = Color(0xFF303030)
private val onSecondaryContainerDark = Color(0xFFEDEDED)
private val tertiaryDark = Color(0xFFDDDDDD)
private val onTertiaryDark = Color(0xFF1A1A1A)
private val tertiaryContainerDark = Color(0xFF2E2E2E)
private val onTertiaryContainerDark = Color(0xFFE0E0E0)
private val errorDark = Color(0xFFFFA8A0)
private val onErrorDark = Color(0xFF4A1B18)
private val errorContainerDark = Color(0xFF612E2B)
private val onErrorContainerDark = Color(0xFFF6CECA)
private val backgroundDark = Color(0xFF0C0C0C)
private val onBackgroundDark = Color(0xFFEDEDED)
private val surfaceDark = Color(0xFF0C0C0C)
private val onSurfaceDark = Color(0xFFEDEDED)
private val surfaceVariantDark = Color(0xFF2A2A2A)
private val onSurfaceVariantDark = Color(0xFF9E9E9E)
private val outlineDark = Color(0xFF565656)
private val outlineVariantDark = Color(0xFF2C2C2C)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFEDEDED)
private val inverseOnSurfaceDark = Color(0xFF1A1A1A)
private val inversePrimaryDark = Color(0xFF3A3A3A)
private val surfaceDimDark = Color(0xFF0C0C0C)
private val surfaceBrightDark = Color(0xFF333333)
private val surfaceContainerLowestDark = Color(0xFF080808)
private val surfaceContainerLowDark = Color(0xFF141414)
private val surfaceContainerDark = Color(0xFF171717)
private val surfaceContainerHighDark = Color(0xFF212121)
private val surfaceContainerHighestDark = Color(0xFF2A2A2A)

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
