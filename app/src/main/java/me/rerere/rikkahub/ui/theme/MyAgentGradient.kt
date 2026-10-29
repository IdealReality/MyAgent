package me.rerere.rikkahub.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
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
    val redLight = Color(0xFFC0346B)
    val purpleLight = Color(0xFF6B3FC8)
    val blueLight = Color(0xFF2B6FD6)

    // ---- 暗色：提亮，压在近黑底上 ---------------------------------------
    val redDark = Color(0xFFFF7A9E)
    val purpleDark = Color(0xFFB98CFF)
    val blueDark = Color(0xFF7FB2FF)

    /** 页面底布：三色各占一段，纵向走完。亮暗各一套，用作不透明背景。 */
    fun ambientStops(dark: Boolean): List<Color> = if (dark) {
        listOf(
            Color(0xFF33172A), // 顶部：深玫红，饱和度抬高但明度压住
            Color(0xFF1E1428), // 上中：紫
            Color(0xFF141122), // 中：靛
            Color(0xFF0E1220), // 下中：深蓝
            Color(0xFF0A0A12), // 底部：收黑
        )
    } else {
        listOf(
            Color(0xFFFFF1F3), // 顶部：暖粉
            Color(0xFFFAF0FA), // 上中：淡紫
            Color(0xFFF4F2FD), // 中：淡靛
            Color(0xFFEFF4FE), // 下中：淡蓝
            Color(0xFFFBFBFD), // 底部：收白
        )
    }

    /** 主操作面：横向从左到右走完红→紫→蓝。 */
    fun accentStops(dark: Boolean): List<Color> =
        if (dark) listOf(redDark, purpleDark, blueDark)
        else listOf(redLight, purpleLight, blueLight)

    /**
     * 顶部光晕：从屏幕上方 1/5 处向下扩散的径向渐变，给纯纵向渐变增加「光从上面来」的
     * 空间感。叠在 [ambient] 之上、透明度很低，只负责让背景不死板。
     */
    fun aura(dark: Boolean): Brush = Brush.radialGradient(
        colors = if (dark) {
            listOf(
                Color(0xFF8A3C68).copy(alpha = 0.62f),
                Color(0xFF3A2150).copy(alpha = 0.18f),
                Color.Transparent,
            )
        } else {
            listOf(
                Color(0xFFF7C9D8).copy(alpha = 0.55f),
                Color(0xFFE3D7F7).copy(alpha = 0.22f),
                Color.Transparent,
            )
        },
        center = Offset(0.5f, 0.12f),
        radius = 1400f,
    )

    /** 底部补光：让页面下缘不至于完全沉黑，和 [aura] 呼应。 */
    fun underglow(dark: Boolean): Brush = Brush.radialGradient(
        colors = if (dark) {
            listOf(
                Color(0xFF16305C).copy(alpha = 0.38f),
                Color.Transparent,
            )
        } else {
            listOf(
                Color(0xFFD3E2FB).copy(alpha = 0.45f),
                Color.Transparent,
            )
        },
        center = Offset(0.5f, 0.98f),
        radius = 1200f,
    )
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

/** 顶部光晕。由 [RikkahubTheme] 注入，随日夜切换重建。 */
val LocalAuraBrush = compositionLocalOf { MyAgentGradient.aura(dark = false) }

/** 底部补光。由 [RikkahubTheme] 注入，随日夜切换重建。 */
val LocalUnderglowBrush = compositionLocalOf { MyAgentGradient.underglow(dark = false) }
