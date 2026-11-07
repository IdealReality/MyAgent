package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.model.AssistantBackgroundMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the light/dark split for a chat photo. The dark theme's veil is the
 * historical 0.20 → 0.50 (a dark photo is unchanged by it). The light theme must
 * not paint that same ramp in white.
 */
class WallpaperContrastTest {

    @Test fun `a photo is shown only when a background is set and the mesh gradient is off`() {
        assertTrue(chatPhotoShown("file://a", useGradientBackground = false))
        assertFalse(chatPhotoShown(null, useGradientBackground = false))
        assertFalse(chatPhotoShown("file://a", useGradientBackground = true))
    }

    @Test fun `light theme does not veil a photo`() {
        val scrim = wallpaperScrim(darkTheme = false)
        assertEquals(0f, scrim.topAlpha, 0f)
        assertEquals(0f, scrim.bottomAlpha, 0f)
    }

    @Test fun `dark theme keeps the historical veil`() {
        val scrim = wallpaperScrim(darkTheme = true)
        assertEquals(0.20f, scrim.topAlpha, 0f)
        assertEquals(0.50f, scrim.bottomAlpha, 0f)
    }

    @Test fun `title fade stays translucent so the top bar is transparent like the bottom`() {
        // 2026-11-04: the top dissolve was a near-solid slab (1f -> 0.75f), which killed the
        // transparency the bottom bar now has. It is now in the same magnitude as the bottom
        // fade: top holds a light cover for status icons, mid is a dissolve hint, bottom clears.
        val noPhoto = titleBarFade(darkTheme = false, photoShown = false)
        val darkPhoto = titleBarFade(darkTheme = true, photoShown = true)
        assertEquals(0f, noPhoto.bottomAlpha, 0f)
        assertEquals(0f, darkPhoto.bottomAlpha, 0f)
        assertTrue("top must stay translucent, not a solid slab", noPhoto.topAlpha <= 0.5f)
        assertTrue("top must stay translucent in the dark theme too", darkPhoto.topAlpha <= 0.5f)
        assertTrue(noPhoto.midAlpha < noPhoto.topAlpha)
    }

    @Test fun `light photo fade keeps a light cover for status icons and clears at the bottom`() {
        val fade = titleBarFade(darkTheme = false, photoShown = true)
        assertEquals(0f, fade.bottomAlpha, 0f)
        assertTrue("status icons still legible at the very top", fade.topAlpha <= 0.5f)
        assertTrue(fade.midAlpha < fade.topAlpha)
    }

    @Test fun `bottom fade starts transparent and stays translucent at the screen edge`() {
        val noPhoto = bottomBarFade(darkTheme = false, photoShown = false)
        val darkPhoto = bottomBarFade(darkTheme = true, photoShown = true)
        assertEquals(0f, noPhoto.topAlpha, 0f)
        assertEquals(0f, darkPhoto.topAlpha, 0f)
        assertEquals(noPhoto, darkPhoto)
        // The bottom of the page is transparent: the dissolve hints at content sliding under the
        // bar, it does not paint a solid surface behind it.
        assertTrue(noPhoto.midAlpha < 0.4f)
        assertTrue(noPhoto.bottomAlpha <= 0.5f)
    }

    @Test fun `light photo bottom fade keeps a softer veil`() {
        val lightPhoto = bottomBarFade(darkTheme = false, photoShown = true)
        val noPhoto = bottomBarFade(darkTheme = false, photoShown = false)
        assertEquals(0f, lightPhoto.topAlpha, 0f)
        assertTrue(lightPhoto.midAlpha < noPhoto.midAlpha)
        assertTrue(lightPhoto.bottomAlpha < noPhoto.bottomAlpha)
    }

    @Test fun `bottom elements are translucent but still readable`() {
        // 0.72: strong enough for body text over a wallpaper, weak enough that the page behind
        // the input bar still shows through.
        assertTrue(CHAT_BOTTOM_SURFACE_ALPHA in 0.6f..0.8f)
        assertTrue(CHAT_BOTTOM_SURFACE_ALPHA < 1f)
    }

    @Test fun `bare assistant text gets a plate only on a light-theme photo`() {
        assertTrue(assistantTextNeedsPhotoPlate(darkTheme = false, photoShown = true, showAssistantBubble = false))
        assertFalse(assistantTextNeedsPhotoPlate(darkTheme = true, photoShown = true, showAssistantBubble = false))
        assertFalse(assistantTextNeedsPhotoPlate(darkTheme = false, photoShown = false, showAssistantBubble = false))
        assertFalse(assistantTextNeedsPhotoPlate(darkTheme = false, photoShown = true, showAssistantBubble = true))
    }

    @Test fun `loose text gets a plate on a light-theme photo even when bubbles are on`() {
        assertTrue(looseTextNeedsPhotoPlate(darkTheme = false, photoShown = true))
        assertFalse(looseTextNeedsPhotoPlate(darkTheme = true, photoShown = true))
        assertFalse(looseTextNeedsPhotoPlate(darkTheme = false, photoShown = false))
    }

    // --- effectiveBackground: per-theme resolution ---

    @Test fun `dark theme prefers darkBackground then falls back to background`() {
        assertEquals("dark.jpg", effectiveBackground(true, "common.jpg", "dark.jpg", "light.jpg"))
        assertEquals("common.jpg", effectiveBackground(true, "common.jpg", null, "light.jpg"))
        assertEquals("common.jpg", effectiveBackground(true, "common.jpg", null, null))
    }

    @Test fun `light theme prefers lightBackground then falls back to background`() {
        assertEquals("light.jpg", effectiveBackground(false, "common.jpg", "dark.jpg", "light.jpg"))
        assertEquals("common.jpg", effectiveBackground(false, "common.jpg", "dark.jpg", null))
        assertEquals("common.jpg", effectiveBackground(false, "common.jpg", null, null))
    }

    @Test fun `effective background is null when nothing is set`() {
        assertEquals(null, effectiveBackground(true, null, null, null))
        assertEquals(null, effectiveBackground(false, null, null, null))
    }

    @Test fun `per-theme wallpaper overrides common even when common is null`() {
        assertEquals("dark.jpg", effectiveBackground(true, null, "dark.jpg", null))
        assertEquals("light.jpg", effectiveBackground(false, null, null, "light.jpg"))
    }

    @Test fun `auto mode is the default and keeps the per-theme override`() {
        assertEquals("dark.jpg", effectiveBackground(true, "common.jpg", "dark.jpg", "light.jpg"))
        assertEquals(
            "light.jpg",
            effectiveBackground(false, "common.jpg", "dark.jpg", "light.jpg", AssistantBackgroundMode.AUTO),
        )
        assertEquals(
            "common.jpg",
            effectiveBackground(false, "common.jpg", "dark.jpg", null, AssistantBackgroundMode.AUTO),
        )
    }

    @Test fun `general mode always uses the common wallpaper`() {
        val mode = AssistantBackgroundMode.GENERAL
        assertEquals("common.jpg", effectiveBackground(true, "common.jpg", "dark.jpg", "light.jpg", mode))
        assertEquals("common.jpg", effectiveBackground(false, "common.jpg", "dark.jpg", "light.jpg", mode))
        assertNull(effectiveBackground(true, null, "dark.jpg", "light.jpg", mode))
    }
}
