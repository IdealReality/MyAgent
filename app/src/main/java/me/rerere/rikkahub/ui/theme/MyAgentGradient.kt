package me.rerere.rikkahub.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * My Agent 的三色身份：红 → 紫 → 蓝。
 *
 * 为什么需要它：主题的 `primary` 只能是一个纯色（Material3 的 ColorScheme 全是 Color，
 * 没有 Brush 字段），所以「整体是三色渐变」不能靠改一个字段实现。这里给出两样东西：
 *
 *  1. [ambient] —— 页面底布的纵向三色渐变，替代平铺的 `colorScheme.background`。
 *  2. [accent]  —— 主操作面上的三色渐变（发送键、主 CTA、徽标、进度条等）。
 *
 * 表面阶梯（`surfaceContainerLow` → `Highest`）在 [me.rerere.rikkahub.ui.theme.presets.MyAgentTheme]
 * 里按红→紫→蓝分配了极淡的色相，所以即便某个页面完全不使用 Brush，卡片堆叠起来也读得出
 * 这条色带：底布偏红、中层偏紫、高层偏蓝。三处叠起来才是「整体渐变」。
 */
object MyAgentGradient {

    // ---- 亮色：低饱和、高明度，压在乳白底上 --------------------------------
    val redLight = Color(0xFFE2516B)
    val purpleLight = Color(0xFF7A3BC8)
    val blueLight = Color(0xFF2B6FD6)

    // ---- 暗色：提亮，压在近黑底上 ---------------------------------------
    val redDark = Color(0xFFFF7385)
    val purpleDark = Color(0xFFC07BFF)
    val blueDark = Color(0xFF6B9BFF)

    /** 页面底布：三色各占一段，纵向走完。亮暗各一套，用作不透明背景。 */
    fun ambientStops(dark: Boolean): List<Color> = if (dark) {
        listOf(
            Color(0xFF1A1218), // 顶部偏红
            Color(0xFF17131D), // 中部偏紫
            Color(0xFF11151F), // 底部偏蓝
        )
    } else {
        listOf(
            Color(0xFFFBF4F5), // 顶部偏红
            Color(0xFFF8F4FA), // 中部偏紫
            Color(0xFFF3F5FB), // 底部偏蓝
        )
    }

    /** 主操作面：横向从左到右走完红→紫→蓝。 */
    fun accentStops(dark: Boolean): List<Color> =
        if (dark) listOf(redDark, purpleDark, blueDark)
        else listOf(redLight, purpleLight, blueLight)

    fun ambient(dark: Boolean): Brush =
        Brush.verticalGradient(colors = ambientStops(dark))

    fun accent(dark: Boolean): Brush =
        Brush.horizontalGradient(colors = accentStops(dark))

    /** 半透明版本，给需要让下层透出来的叠层（如顶栏溶解、卡片描边）用。 */
    fun accentVeil(dark: Boolean, alpha: Float = 0.14f): Brush =
        Brush.horizontalGradient(colors = accentStops(dark).map { it.copy(alpha = alpha) })
}

/** 当前主题下的主操作渐变。由 [RikkahubTheme] 提供。 */
val LocalAccentBrush = compositionLocalOf { MyAgentGradient.accent(dark = false) }

/** 当前主题下的页面底布渐变。由 [RikkahubTheme] 提供。 */
val LocalAmbientBrush = compositionLocalOf { MyAgentGradient.ambient(dark = false) }
