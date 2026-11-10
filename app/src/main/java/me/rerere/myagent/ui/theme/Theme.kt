package me.rerere.myagent.ui.theme

import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.serialization.Serializable
import me.rerere.myagent.utils.getActivity
import me.rerere.myagent.data.datastore.ChatFontFamily
import me.rerere.myagent.ui.hooks.rememberAmoledDarkMode
import me.rerere.myagent.ui.hooks.rememberCurrentColorMode
import me.rerere.myagent.ui.hooks.rememberUserSettingsState

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
private val MonoExtendLightColors = lightMonoExtendColors()
private val MonoExtendDarkColors = darkMonoExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }

/**
 * 当前主题是否是黑白单色主题。装饰面（渐变背景、彩色光斑、代码高亮、加载动画、状态点）
 * 都读它，决定自己要不要降饱和——它们写死了色相，光靠 ColorScheme 变成灰阶管不到。
 */
val LocalMonoTheme = compositionLocalOf { true }

/** 主题 id 为 mono 即单色主题；预设、自定义主题、动态取色都走这一条判据。 */
fun isMonoTheme(themeId: String): Boolean = themeId == "mono"

private val AMOLED_DARK_BACKGROUND = Color(0xFF000000)

@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun RikkahubTheme(
    colorMode: ColorMode = rememberCurrentColorMode(),
    content: @Composable () -> Unit
) {
    val settings by rememberUserSettingsState()

    val darkTheme = when (colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val amoledDarkMode by rememberAmoledDarkMode()

    // mono 优先级高于动态取色：用户选了黑白主题，就不该再被壁纸配色盖回去。
    val monoTheme = isMonoTheme(settings.themeId)

    // 主色来源不是作者精心调过的预设，而是壁纸（动态取色）或用户自选颜色。
    // 此时色相不可预知，语义色板需要跟着主色微微旋转，见 [harmonizedExtendColors]。
    val dynamicColorActive = !monoTheme && settings.dynamicColor &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorSourceIsUserChosen = dynamicColorActive ||
        (!monoTheme && settings.customThemes.any { it.id == settings.themeId })

    val colorScheme = when {
        dynamicColorActive -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            val theme = findThemeById(settings.themeId, settings.customThemes)
                ?: findPresetTheme(settings.themeId)
            theme.getColorScheme(dark = darkTheme)
        }
    }
    val colorSchemeConverted = remember(darkTheme, amoledDarkMode, colorScheme) {
        if (darkTheme && amoledDarkMode) {
            colorScheme.copy(
                background = AMOLED_DARK_BACKGROUND,
                surface = AMOLED_DARK_BACKGROUND,
            )
        } else {
            colorScheme
        }
    }
    // 动态取色与自定义主题的主色由壁纸 / 用户选择决定，色相不可预知；此时语义色板要跟着
    // 主色微微旋转（见 [harmonizedExtendColors]），否则会出现「壁纸是粉紫、信息色是标准蓝」
    // 这种两套配色拼在一起的感觉。预设主题的配色是作者调过的，保持原样。
    val baseExtendColors = when {
        monoTheme && darkTheme -> MonoExtendDarkColors
        monoTheme -> MonoExtendLightColors
        darkTheme -> ExtendDarkColors
        else -> ExtendLightColors
    }
    val extendColors = remember(baseExtendColors, colorSourceIsUserChosen, colorScheme.primary) {
        if (colorSourceIsUserChosen) {
            harmonizedExtendColors(baseExtendColors, colorScheme.primary.toArgb())
        } else {
            baseExtendColors
        }
    }

    // 更新状态栏图标颜色
    val view = LocalView.current
    // 浮窗可能使用 Application Context，没有可更新系统栏的 Activity（同上游 3.1.1）。
    val activity = view.context.getActivity()
    if (!view.isInEditMode && activity != null) {
        DisposableEffect(view, activity, darkTheme) {
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, view)
            val previousLightStatusBars = controller.isAppearanceLightStatusBars
            val previousLightNavigationBars = controller.isAppearanceLightNavigationBars
            controller.apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            onDispose {
                // 嵌套主题（如终端的深色主题）退出时恢复原有系统栏图标颜色。
                controller.isAppearanceLightStatusBars = previousLightStatusBars
                controller.isAppearanceLightNavigationBars = previousLightNavigationBars
            }
        }
    }

    // 画风对齐 myagent 3.1.1：默认就是 M3 的 [Typography] 与系统密度——不像以前那样无条件
    // 换字族、无条件改 fontScale。只有用户真的改过（选了 UI 字体，或把 UI 字号调离 100%）
    // 才挂上对应的覆盖，所以新装/未改动状态下观感与原版逐一对应。
    val uiFontCustomized = settings.displaySetting.uiFontFamily != ChatFontFamily.DEFAULT ||
        settings.displaySetting.uiFontScale != FontScale.DEFAULT
    val typography = if (uiFontCustomized) {
        rememberAppTypography(settings.displaySetting)
    } else {
        Typography
    }
    val (appDensity, baseFontScale) = if (uiFontCustomized) {
        rememberAppDensity(settings.displaySetting)
    } else {
        LocalDensity.current to (LocalBaseFontScale.current ?: LocalDensity.current.fontScale)
    }

    CompositionLocalProvider(
        LocalDarkMode provides darkTheme,
        LocalMonoTheme provides monoTheme,
        LocalExtendColors provides extendColors,
        LocalOverscrollFactory provides null,
        LocalDensity provides appDensity,
        LocalBaseFontScale provides baseFontScale,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorSchemeConverted,
            typography = typography,
            content = content,
            motionScheme = MotionScheme.expressive()
        )
    }
}

val MaterialTheme.extendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current
