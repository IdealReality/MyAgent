package me.rerere.myagent.data.datastore.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * V7 迁移的唯一决策点：哪些 theme_id 该被换成 mono。
 *
 * 这条边界必须钉死——多迁一种就会覆盖用户主动选的主题，少迁一种则老存档升上来还是粉色。
 */
class ThemeMigrationTest {

    @Test
    fun `legacy default theme migrates to mono`() {
        assertEquals("mono", migratedThemeId("sakura"))
    }

    @Test
    fun `missing theme id migrates to mono`() {
        assertEquals("mono", migratedThemeId(null))
    }

    @Test
    fun `explicitly chosen preset theme is preserved`() {
        assertNull(migratedThemeId("ocean"))
        assertNull(migratedThemeId("black"))
        assertNull(migratedThemeId("claude"))
    }

    @Test
    fun `custom theme id is preserved`() {
        assertNull(migratedThemeId("custom-8f3a1c"))
    }

    @Test
    fun `already mono stays untouched`() {
        assertNull(migratedThemeId("mono"))
    }
}
