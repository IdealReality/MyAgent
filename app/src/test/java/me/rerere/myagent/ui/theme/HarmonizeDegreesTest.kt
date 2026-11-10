package me.rerere.myagent.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * harmonize 的角度规则：朝主色走一半差角、封顶 15°、跨 0° 时走最短弧。
 *
 * 这三条是「语义色跟随壁纸但不被同化」的全部约束——写错任何一条，绿会被染成主色
 * （语义消失），或者干脆不转（两套配色并排）。
 */
class HarmonizeDegreesTest {

    @Test
    fun `identical hues do not rotate`() {
        assertEquals(0.0, harmonizeDegrees(from = 25.0, to = 25.0), 1e-9)
    }

    @Test
    fun `rotation is half the hue difference`() {
        assertEquals(8.0, harmonizeDegrees(from = 30.0, to = 46.0), 1e-9)
        assertEquals(-8.0, harmonizeDegrees(from = 46.0, to = 30.0), 1e-9)
    }

    @Test
    fun `rotation is capped at fifteen degrees`() {
        // 差 80° 的一半是 40°，必须被压到 15°：再大绿就真的变成主色了。
        assertEquals(15.0, harmonizeDegrees(from = 0.0, to = 80.0), 1e-9)
        assertEquals(-15.0, harmonizeDegrees(from = 80.0, to = 0.0), 1e-9)
    }

    @Test
    fun `rotation takes the short way across zero`() {
        // 352° → 4° 实际只差 12°（跨 0），不该按 348° 反向转。
        assertEquals(6.0, harmonizeDegrees(from = 352.0, to = 4.0), 1e-9)
        assertEquals(-6.0, harmonizeDegrees(from = 4.0, to = 352.0), 1e-9)
    }

    @Test
    fun `diametrically opposite hues pick one direction and stay capped`() {
        val degrees = harmonizeDegrees(from = 0.0, to = 180.0)
        assertEquals(15.0, kotlin.math.abs(degrees), 1e-9)
    }
}
