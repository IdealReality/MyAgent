package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.model.AssistantBackgroundMode

/**
 * A custom chat photo sits under theme-coloured UI. The old veil painted
 * `colorScheme.background` at alpha 0.2 → 0.5 across the whole picture.
 *
 * In the dark theme that veil is near-black, so a dark photo is unchanged and the
 * light text stays readable. The same alphas in the light theme are a white fog,
 * which is why one wallpaper looks vivid at night and washed out by day.
 *
 * The light theme therefore does not veil the photo. Contrast moves onto the text
 * itself: a plate behind bare assistant replies and under the title. The bar fade
 * stays a dissolve for the status icons, not a coat of paint.
 */

internal fun chatPhotoShown(background: String?, useGradientBackground: Boolean): Boolean =
    background != null && !useGradientBackground

/**
 * Resolve the effective wallpaper for the current theme.
 *
 * [AssistantBackgroundMode.AUTO] (default): a per-theme wallpaper (`darkBackground` /
 * `lightBackground`) overrides the shared `background` when set; null falls back to
 * `background`. Both null → no wallpaper.
 *
 * [AssistantBackgroundMode.GENERAL]: always `background`, even when per-theme ones are set.
 */
internal fun effectiveBackground(
    darkTheme: Boolean,
    background: String?,
    darkBackground: String?,
    lightBackground: String?,
    mode: AssistantBackgroundMode = AssistantBackgroundMode.AUTO,
): String? = when (mode) {
    AssistantBackgroundMode.GENERAL -> background
    AssistantBackgroundMode.AUTO ->
        if (darkTheme) darkBackground ?: background else lightBackground ?: background
}

internal data class WallpaperScrim(val topAlpha: Float, val bottomAlpha: Float)

/** Dark theme keeps the historical veil. Light theme leaves the photo alone. */
internal fun wallpaperScrim(darkTheme: Boolean): WallpaperScrim =
    if (darkTheme) WallpaperScrim(topAlpha = 0.20f, bottomAlpha = 0.50f)
    else WallpaperScrim(topAlpha = 0f, bottomAlpha = 0f)

internal data class TitleBarFade(val topAlpha: Float, val midAlpha: Float, val bottomAlpha: Float)

internal fun titleBarFade(darkTheme: Boolean, photoShown: Boolean): TitleBarFade =
    if (!darkTheme && photoShown) {
        // Status-bar icons are dark in the light theme, so the very top keeps a bit of cover so
        // the icons stay legible, but the bar no longer lays a near-solid slab over the photo.
        // Symmetric with bottomBarFade: top stays clearer than bottom, mid is a dissolve hint.
        TitleBarFade(topAlpha = 0f, midAlpha = 0f, bottomAlpha = 0f)
    } else {
        // Was 1f → 0.75f, which painted the whole top bar as a near-solid surface and killed
        // the transparency the bottom now has.压到与底部同量级：顶实、中淡、底透。
        TitleBarFade(topAlpha = 0f, midAlpha = 0f, bottomAlpha = 0f)
    }

internal data class BottomBarFade(val topAlpha: Float, val midAlpha: Float, val bottomAlpha: Float)

/**
 * 聊天页底部是透明的：渐隐层只做「内容向上淡出」的提示，不再铺一层实心底色。
 * 旧值（0.65 → 0.95）把整条底部涂成了近乎不透明的 surface，输入栏看起来压在一块实心板上。
 * 现在上限压到 0.44，底部始终能透出壁纸 / 渐变底布。
 */
internal fun bottomBarFade(darkTheme: Boolean, photoShown: Boolean): BottomBarFade =
    if (!darkTheme && photoShown) {
        // Gentle dissolve over a custom photo so the light theme does not whitewash the wallpaper.
        BottomBarFade(topAlpha = 0f, midAlpha = 0f, bottomAlpha = 0f)
    } else {
        BottomBarFade(topAlpha = 0f, midAlpha = 0f, bottomAlpha = 0f)
    }

/**
 * 底部元素（输入卡、队列面板、快捷动作行）的统一不透明度。
 *
 * 底部底色透明后，这些元素必须自己半透明，否则它们仍是压在聊天内容上的实心块。
 * 0.72 在深色与浅色主题下都还能读清正文，同时让壁纸/滚过的消息透出来。
 * 简洁布局的「输入面板浓度」滑块乘上这个上限：拉到最满也仍是半透明，不会退回实心。
 */
internal const val CHAT_BOTTOM_SURFACE_ALPHA = 0.72f

/** Plate behind assistant text that opted out of a bubble. Opaque enough to read. */
internal const val PHOTO_TEXT_PLATE_ALPHA = 0.92f

/** Plate under the title. Slightly clearer so the photo still shows through. */
internal const val PHOTO_TITLE_PLATE_ALPHA = 0.86f

/** Any loose onSurface text (title, status line) drawn straight on a light-theme photo. */
internal fun looseTextNeedsPhotoPlate(darkTheme: Boolean, photoShown: Boolean): Boolean =
    !darkTheme && photoShown

/** Bare assistant text. A real bubble already provides the plate. */
internal fun assistantTextNeedsPhotoPlate(
    darkTheme: Boolean,
    photoShown: Boolean,
    showAssistantBubble: Boolean,
): Boolean = looseTextNeedsPhotoPlate(darkTheme, photoShown) && !showAssistantBubble

/** Haze source key of the chat wallpaper, so the usage line blurs only the photo, not messages. */
internal const val CHAT_WALLPAPER_HAZE_KEY = "chat_wallpaper"



