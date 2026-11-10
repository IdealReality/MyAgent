package me.rerere.myagent.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态取色下的语义色对齐：色相偏一点，亮度不能动。
 *
 * 真正要守的是**对比度契约**——Tag 文字(8)/底(2)、正文(7)/画布都靠 HCT tone 定死。
 * 所以这里断言 tone 几乎不漂（argb 8 bit 往返 + 色域裁剪的余量），并对真实色板在极端主色
 * 下再验一次 AA。chroma 不设上下界：目标色相在 sRGB 里可能装不下这么多彩度，HCT 会自动
 * 削减到色域内（实测最坏 0.76×），这是正确行为，不是缺陷。
 */
class HarmonizedExtendColorsTest {

    private val base = lightMonoExtendColors()

    private fun ExtendColors.levels(): List<Color> = listOf(
        red1, red2, red3, red4, red5, red6, red7, red8, red9, red10,
        orange1, orange2, orange3, orange4, orange5, orange6, orange7, orange8, orange9, orange10,
        green1, green2, green3, green4, green5, green6, green7, green8, green9, green10,
        blue1, blue2, blue3, blue4, blue5, blue6, blue7, blue8, blue9, blue10,
    )

    /** 主色的四个极端色相：正红、正黄绿、正青、正紫——覆盖色环上差别最大的几段。 */
    private val anchors = listOf(
        Color(0xFFFF0000),
        Color(0xFF00FF00),
        Color(0xFF00FFFF),
        Color(0xFF8000FF),
    )

    @Test
    fun `tone survives the hue rotation`() {
        for (anchor in anchors) {
            val rotated = harmonizedExtendColors(base, anchor.toArgb())
            base.levels().zip(rotated.levels()).forEach { (before, after) ->
                val a = Hct.fromInt(before.toArgb())
                val b = Hct.fromInt(after.toArgb())
                assertEquals("tone drifted for anchor $anchor", a.tone, b.tone, 1.0)
            }
        }
    }

    @Test
    fun `hue shift stays within the harmonize cap`() {
        // 上限 15°，加上 8 bit 量化的一点余量。
        for (anchor in anchors) {
            val rotated = harmonizedExtendColors(base, anchor.toArgb())
            base.levels().zip(rotated.levels()).forEach { (before, after) ->
                val a = Hct.fromInt(before.toArgb()).hue
                val b = Hct.fromInt(after.toArgb()).hue
                assertTrue(
                    "hue moved too far for anchor $anchor: $a -> $b",
                    utils.MathUtils.differenceDegrees(a, b) <= 16.0,
                )
            }
        }
    }

    @Test
    fun `gray family is never tinted`() {
        val rotated = harmonizedExtendColors(base, Color(0xFF00FFFF).toArgb())
        assertEquals(base.gray1, rotated.gray1)
        assertEquals(base.gray8, rotated.gray8)
        assertEquals(base.gray10, rotated.gray10)
    }

    @Test
    fun `tag text still meets AA after harmonizing`() {
        for (anchor in anchors) {
            val c = harmonizedExtendColors(base, anchor.toArgb())
            listOf(
                c.red8 to c.red2,
                c.orange8 to c.orange2,
                c.green8 to c.green2,
                c.blue8 to c.blue2,
            ).forEach { (text, background) ->
                val ratio = contrastRatio(text, background)
                assertTrue("Tag contrast $ratio under anchor $anchor", ratio >= 4.5)
            }
        }
    }
}

/** WCAG 相对亮度对比度，就地实现以免测试依赖 UI 工具链。 */
private fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

private fun relativeLuminance(color: Color): Double {
    fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(color.red) +
        0.7152 * channel(color.green) +
        0.0722 * channel(color.blue)
}
