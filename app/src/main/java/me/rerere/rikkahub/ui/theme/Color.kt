package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingToolbarColors
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import hct.Hct
import utils.MathUtils
import kotlin.math.min

data class ExtendColors(
    val red1: Color,
    val red2: Color,
    val red3: Color,
    val red4: Color,
    val red5: Color,
    val red6: Color,
    val red7: Color,
    val red8: Color,
    val red9: Color,
    val red10: Color,
    val orange1: Color,
    val orange2: Color,
    val orange3: Color,
    val orange4: Color,
    val orange5: Color,
    val orange6: Color,
    val orange7: Color,
    val orange8: Color,
    val orange9: Color,
    val orange10: Color,
    val green1: Color,
    val green2: Color,
    val green3: Color,
    val green4: Color,
    val green5: Color,
    val green6: Color,
    val green7: Color,
    val green8: Color,
    val green9: Color,
    val green10: Color,
    val blue1: Color,
    val blue2: Color,
    val blue3: Color,
    val blue4: Color,
    val blue5: Color,
    val blue6: Color,
    val blue7: Color,
    val blue8: Color,
    val blue9: Color,
    val blue10: Color,
    val gray1: Color,
    val gray2: Color,
    val gray3: Color,
    val gray4: Color,
    val gray5: Color,
    val gray6: Color,
    val gray7: Color,
    val gray8: Color,
    val gray9: Color,
    val gray10: Color,
)

fun lightExtendColors(): ExtendColors = ExtendColors(
    red1 = Color(255, 236, 232),
    red2 = Color(253, 205, 197),
    red3 = Color(251, 172, 163),
    red4 = Color(249, 137, 129),
    red5 = Color(247, 101, 96),
    red6 = Color(245, 63, 63),
    red7 = Color(203, 39, 45),
    red8 = Color(161, 21, 30),
    red9 = Color(119, 8, 19),
    red10 = Color(77, 0, 10),
    orange1 = Color(255, 247, 232),
    orange2 = Color(255, 228, 186),
    orange3 = Color(255, 207, 139),
    orange4 = Color(255, 182, 93),
    orange5 = Color(255, 154, 46),
    orange6 = Color(255, 125, 0),
    orange7 = Color(210, 95, 0),
    orange8 = Color(166, 69, 0),
    orange9 = Color(121, 46, 0),
    orange10 = Color(77, 27, 0),
    green1 = Color(232, 255, 234),
    green2 = Color(175, 240, 181),
    green3 = Color(123, 225, 136),
    green4 = Color(76, 210, 99),
    green5 = Color(35, 195, 67),
    green6 = Color(0, 180, 42),
    green7 = Color(0, 154, 41),
    green8 = Color(0, 128, 38),
    green9 = Color(0, 102, 34),
    green10 = Color(0, 77, 28),
    blue1 = Color(232, 247, 255),
    blue2 = Color(195, 231, 254),
    blue3 = Color(159, 212, 253),
    blue4 = Color(123, 192, 252),
    blue5 = Color(87, 169, 251),
    blue6 = Color(52, 145, 250),
    blue7 = Color(32, 108, 207),
    blue8 = Color(17, 75, 163),
    blue9 = Color(6, 48, 120),
    blue10 = Color(0, 26, 77),
    gray1 = Color(247, 248, 250),
    gray2 = Color(242, 243, 245),
    gray3 = Color(229, 230, 235),
    gray4 = Color(201, 205, 212),
    gray5 = Color(169, 174, 184),
    gray6 = Color(134, 144, 156),
    gray7 = Color(107, 119, 133),
    gray8 = Color(78, 89, 105),
    gray9 = Color(39, 46, 59),
    gray10 = Color(29, 33, 41),
)

fun darkExtendColors(): ExtendColors = ExtendColors(
    red1 = Color(77, 0, 10),
    red2 = Color(119, 6, 17),
    red3 = Color(161, 22, 31),
    red4 = Color(203, 46, 52),
    red5 = Color(245, 78, 78),
    red6 = Color(247, 105, 101),
    red7 = Color(249, 141, 134),
    red8 = Color(251, 176, 167),
    red9 = Color(253, 209, 202),
    red10 = Color(255, 240, 236),
    orange1 = Color(77, 27, 0),
    orange2 = Color(121, 48, 4),
    orange3 = Color(166, 75, 10),
    orange4 = Color(210, 105, 19),
    orange5 = Color(255, 141, 31),
    orange6 = Color(255, 150, 38),
    orange7 = Color(255, 179, 87),
    orange8 = Color(255, 205, 135),
    orange9 = Color(255, 227, 184),
    orange10 = Color(255, 247, 232),
    green1 = Color(0, 77, 28),
    green2 = Color(4, 102, 37),
    green3 = Color(10, 128, 45),
    green4 = Color(18, 154, 55),
    green5 = Color(29, 180, 64),
    green6 = Color(39, 195, 70),
    green7 = Color(80, 210, 102),
    green8 = Color(126, 225, 139),
    green9 = Color(178, 240, 183),
    green10 = Color(235, 255, 236),
    blue1 = Color(0, 26, 77),
    blue2 = Color(5, 47, 120),
    blue3 = Color(19, 76, 163),
    blue4 = Color(41, 113, 207),
    blue5 = Color(70, 154, 250),
    blue6 = Color(90, 170, 251),
    blue7 = Color(125, 193, 252),
    blue8 = Color(161, 213, 253),
    blue9 = Color(198, 232, 254),
    blue10 = Color(234, 248, 255),
    gray1 = Color(23, 23, 26),
    gray2 = Color(46, 46, 48),
    gray3 = Color(72, 72, 73),
    gray4 = Color(95, 95, 96),
    gray5 = Color(120, 120, 122),
    gray6 = Color(146, 146, 147),
    gray7 = Color(171, 171, 172),
    gray8 = Color(197, 197, 197),
    gray9 = Color(223, 223, 223),
    gray10 = Color(246, 246, 246),
)

/**
 * mono 主题的扩展色板：**画布无彩，语义有彩**。
 *
 * 版式、表面、正文、主色依旧是灰阶（见 [me.rerere.rikkahub.ui.theme.presets.MonoTheme]），
 * 但红/橙/绿/蓝四族是真正的信号色 —— 成功、失败、警告、信息这四件事本来就是
 * 「该有颜色」的地方，把它们压成灰等于让用户靠猜。
 *
 * 纪律：
 *  - 只染信息，不染气氛。渐变背景、玻璃色散、加载动画仍然走灰阶分支，不受这里影响。
 *  - 亮色主题的语义档（6/7/8）取各自色相的满色域：灰底上一味压低饱和度只会让红变成土、
 *    绿变成灰，用户读不出「这是绿的」。填充档（1–5）保持低彩度，才不会把整屏拽回彩色主题。
 *    暗色主题沿用原版阶梯——深底上高饱和会发光刺眼，亮度已经承担了区分。
 *  - 语义序号不变：1 最浅填充 … 6 实心标记/状态点 … 8 深色文字。调用点零改动。
 *  - 每一档的对子都验过对比度（亮色/白底）：Tag 8/2 ≥ 6.1:1，正文 7/画布 ≥ 4.97:1，
 *    状态点 6/画布 ≥ 3.5:1（红 4.24）。暗色：8/画布 ≥ 9.9:1，7/画布 ≥ 7.3:1。
 */
fun lightMonoExtendColors(): ExtendColors = monoExtendColors(dark = false)

fun darkMonoExtendColors(): ExtendColors = monoExtendColors(dark = true)

private val MonoLightRed = listOf(
    0xFFFFF2F1, 0xFFFFE6E4, 0xFFFFD4CF, 0xFFFFBCB6, 0xFFFF9B93,
    0xFFF4102C, 0xFFC80B22, 0xFF9F131D, 0xFF79171A, 0xFF451C1A,
)
private val MonoLightOrange = listOf(
    0xFFFFF3E7, 0xFFFFE9D0, 0xFFFFD8AC, 0xFFFCC382, 0xFFF7A73B,
    0xFFB4740B, 0xFF945E07, 0xFF764B0D, 0xFF593A11, 0xFF352717,
)
private val MonoLightGreen = listOf(
    0xFFECFAEF, 0xFFD8F6DE, 0xFFBBEFC8, 0xFF9AE4AE, 0xFF65D68A,
    0xFF0E9D51, 0xFF098141, 0xFF106635, 0xFF144E2A, 0xFF1A3020,
)
private val MonoLightBlue = listOf(
    0xFFEFF6FF, 0xFFE1EEFF, 0xFFCBE2FF, 0xFFB0D2FF, 0xFF8BBEFF,
    0xFF0B81F6, 0xFF0769CA, 0xFF0D54A0, 0xFF124179, 0xFF182B43,
)
private val MonoDarkRed = listOf(
    0xFF432421, 0xFF60302D, 0xFF7D3D39, 0xFF9A4B46, 0xFFB65C56,
    0xFFD3726B, 0xFFEC8C85, 0xFFFFA8A0, 0xFFFFCAC5, 0xFFFFEDEB,
)
private val MonoDarkOrange = listOf(
    0xFF3D2911, 0xFF57380F, 0xFF72480B, 0xFF8C5A0C, 0xFFA66C17,
    0xFFC2832F, 0xFFDA9D51, 0xFFEEB776, 0xFFFED09B, 0xFFFFEFDD,
)
private val MonoDarkGreen = listOf(
    0xFF1A3522, 0xFF1F4A2D, 0xFF256039, 0xFF2D7747, 0xFF3A8E56,
    0xFF4FA86B, 0xFF6DC085, 0xFF8ED6A1, 0xFFAFEABD, 0xFFDBFCE2,
)
private val MonoDarkBlue = listOf(
    0xFF1D2F46, 0xFF254164, 0xFF2E5483, 0xFF3967A1, 0xFF477CBF,
    0xFF5B94DD, 0xFF76AEF4, 0xFF99C6FF, 0xFFC0DCFF, 0xFFE9F3FF,
)

/** 灰色族继续走纯灰阶：它承载的是「中性 / 无状态」，这里不该有色相。 */
private val MonoLightGray = listOf(
    0xFFF2F2F2, 0xFFE9E9E9, 0xFFDEDEDE, 0xFFD1D1D1, 0xFFC3C3C3,
    0xFFB2B2B2, 0xFF9E9E9E, 0xFF858585, 0xFF686868, 0xFF1A1A1A,
)
private val MonoDarkGray = listOf(
    0xFF1E1E1E, 0xFF2E2E2E, 0xFF3E3E3E, 0xFF4E4E4E, 0xFF606060,
    0xFF747474, 0xFF8A8A8A, 0xFFA4A4A4, 0xFFC4C4C4, 0xFFF5F5F5,
)

private fun monoExtendColors(dark: Boolean): ExtendColors = assembleExtendColors(
    red = (if (dark) MonoDarkRed else MonoLightRed).map { Color(it) },
    orange = (if (dark) MonoDarkOrange else MonoLightOrange).map { Color(it) },
    green = (if (dark) MonoDarkGreen else MonoLightGreen).map { Color(it) },
    blue = (if (dark) MonoDarkBlue else MonoLightBlue).map { Color(it) },
    gray = (if (dark) MonoDarkGray else MonoLightGray).map { Color(it) },
)

/** 四族语义色 + 灰族装配成 [ExtendColors]。序号不在这里解释，见各族常量。 */
private fun assembleExtendColors(
    red: List<Color>,
    orange: List<Color>,
    green: List<Color>,
    blue: List<Color>,
    gray: List<Color>,
): ExtendColors = ExtendColors(
    red1 = red[0], red2 = red[1], red3 = red[2], red4 = red[3], red5 = red[4],
    red6 = red[5], red7 = red[6], red8 = red[7], red9 = red[8], red10 = red[9],
    orange1 = orange[0], orange2 = orange[1], orange3 = orange[2], orange4 = orange[3],
    orange5 = orange[4], orange6 = orange[5], orange7 = orange[6], orange8 = orange[7],
    orange9 = orange[8], orange10 = orange[9],
    green1 = green[0], green2 = green[1], green3 = green[2], green4 = green[3],
    green5 = green[4], green6 = green[5], green7 = green[6], green8 = green[7],
    green9 = green[8], green10 = green[9],
    blue1 = blue[0], blue2 = blue[1], blue3 = blue[2], blue4 = blue[3],
    blue5 = blue[4], blue6 = blue[5], blue7 = blue[6], blue8 = blue[7],
    blue9 = blue[8], blue10 = blue[9],
    gray1 = gray[0], gray2 = gray[1], gray3 = gray[2], gray4 = gray[3],
    gray5 = gray[4], gray6 = gray[5], gray7 = gray[6], gray8 = gray[7],
    gray9 = gray[8], gray10 = gray[9],
)

/** 一族 10 档，索引 0 对应序号 1。 */
private fun ExtendColors.redFamily(): List<Color> =
    listOf(red1, red2, red3, red4, red5, red6, red7, red8, red9, red10)

private fun ExtendColors.orangeFamily(): List<Color> =
    listOf(orange1, orange2, orange3, orange4, orange5, orange6, orange7, orange8, orange9, orange10)

private fun ExtendColors.greenFamily(): List<Color> =
    listOf(green1, green2, green3, green4, green5, green6, green7, green8, green9, green10)

private fun ExtendColors.blueFamily(): List<Color> =
    listOf(blue1, blue2, blue3, blue4, blue5, blue6, blue7, blue8, blue9, blue10)

private fun ExtendColors.grayFamily(): List<Color> =
    listOf(gray1, gray2, gray3, gray4, gray5, gray6, gray7, gray8, gray9, gray10)

/**
 * 语义色与当前主色对齐（动态取色 / 自定义主题专用）。
 *
 * 问题：壁纸取色把主色染成什么色相都有可能，而语义色板是写死色相的。粉紫壁纸配上一个
 * 标准蓝的「信息」色，两者互不相干，界面看起来像两套配色拼在一起。
 *
 * 做法借用 M3 自己的 harmonize 口径：把每族色相朝主色色相旋转，**最多 15°、且只走一半**
 * 的差角。这样：
 *  - 色相只是「微微偏向」主色，绿仍然是绿、橙仍然是橙，语义不会被调色到认不出；
 *    15° 的上限是这里的关键：红 25° 最多偏到 40°（仍读作红/朱），不会串到琥珀；
 *  - 只动色相，HCT 的 chroma 与 tone 保持原值，所以对比度契约（Tag 8/2、正文 7/画布）
 *    全部原样成立，不需要重算；
 *  - 灰族不动：中性色带色相就不再中性了。
 *
 * 四族一起转（含错误红）：动态取色下 `colorScheme.error` 本来就由壁纸推导，语义红跟着走
 * 才不会出现「错误容器是主色系、状态点却是固定正红」的错位。
 */
fun harmonizedExtendColors(base: ExtendColors, sourceArgb: Int): ExtendColors {
    val anchorHue = Hct.fromInt(sourceArgb).hue
    fun List<Color>.towardsAnchor(): List<Color> {
        // 用 8 号档（正文级）定色相：它是四族里唯一每屏都当文字用的档位。
        val from = Hct.fromInt(this[7].toArgb()).hue
        val degrees = harmonizeDegrees(from = from, to = anchorHue)
        return if (degrees == 0.0) this else map { it.rotateHue(degrees) }
    }
    return assembleExtendColors(
        red = base.redFamily().towardsAnchor(),
        orange = base.orangeFamily().towardsAnchor(),
        green = base.greenFamily().towardsAnchor(),
        blue = base.blueFamily().towardsAnchor(),
        gray = base.grayFamily(),
    )
}

/** 朝 [to] 旋转的角度：差角的一半，封顶 15°，方向取最短弧。 */
internal fun harmonizeDegrees(from: Double, to: Double): Double {
    val difference = MathUtils.differenceDegrees(from, to)
    val direction = if (MathUtils.sanitizeDegreesDouble(to - from) <= 180.0) 1.0 else -1.0
    val magnitude = min(difference * 0.5, HARMONIZE_MAX_DEGREES)
    // 差角为 0 或 360 的整数倍时 direction 无意义，直接不转。
    return if (difference == 0.0) 0.0 else direction * magnitude
}

/**
 * 只换色相，tone 原样保留——亮度不变，对比度契约也就不变。
 *
 * 一个坑：[Hct.from] 会为目标色相 + 原 chroma 求解 sRGB 值；当那个色相装不下这么多彩度时
 * 会削减 chroma，而削减过程本身把**实际色相**带偏——实测要求 15° 会落到 16.7°。
 * 所以这里逐级降 chroma 试算，取第一个误差 ≤ 0.5° 的结果：
 * chroma 越大越鲜艳，误差优先，两者在这里不冲突（最坏只掉 10% 彩度）。
 */
private fun Color.rotateHue(degrees: Double): Color {
    val source = Hct.fromInt(toArgb())
    val desired = degrees.coerceIn(-HARMONIZE_MAX_DEGREES, HARMONIZE_MAX_DEGREES)
    val target = MathUtils.sanitizeDegreesDouble(source.hue + desired)

    var chroma = source.chroma
    var best = Hct.from(target, chroma, source.tone)
    var bestError = kotlin.math.abs(harmonizeDeltaDegrees(source.hue, best.hue) - desired)
    while (bestError > HUE_TOLERANCE_DEGREES && chroma > source.chroma * 0.5) {
        chroma *= 0.9
        val candidate = Hct.from(target, chroma, source.tone)
        val error = kotlin.math.abs(harmonizeDeltaDegrees(source.hue, candidate.hue) - desired)
        if (error < bestError) {
            bestError = error
            best = candidate
        }
    }
    return Color(best.toInt())
}

/** 收敛判据：0.5° 远低于肉眼可辨的色相差，又高于 8 bit 量化抖动。 */
private const val HUE_TOLERANCE_DEGREES = 0.5

/** 从 [from] 转到 [to] 的实际角度，带方向、范围 (-180, 180]。 */
private fun harmonizeDeltaDegrees(from: Double, to: Double): Double {
    var delta = MathUtils.sanitizeDegreesDouble(to - from)
    if (delta > 180.0) delta -= 360.0
    return delta
}

private const val HARMONIZE_MAX_DEGREES = 15.0

object CustomColors {
    var black = false

    val topBarColors: TopAppBarColors
        @Composable get() {
            return if (!LocalDarkMode.current) TopAppBarDefaults.topAppBarColors(
                containerColor = colorScheme.surfaceContainer,
                scrolledContainerColor = colorScheme.surfaceContainer
            ) else TopAppBarDefaults.topAppBarColors()
        }

    val cardColors: CardColors
        @Composable get() = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainer)

    val cardColorsOnSurfaceContainer: CardColors
        @Composable get() = CardDefaults.cardColors(containerColor = colorScheme.surfaceBright)

    val listItemColors: ListItemColors
        @Composable get() = ListItemDefaults.colors(containerColor = colorScheme.surfaceBright)

    // 浅色模式下页面背景和悬浮工具栏默认的底色相同，用强调色的版本才分得开（同上游 3.1.1）。
    val floatingToolbarColors: FloatingToolbarColors
        @Composable get() = FloatingToolbarDefaults.vibrantFloatingToolbarColors()
}
