package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/**
 * 输入栏正上方那条窄渐隐带：消息滚到底部时淡出，而不是被输入卡一刀切断。
 *
 * 它必须画在**输入栏外部上方**，不能铺在卡片背后——底部整体是透明的，若在卡片下方垫一层
 * 底色，半透明卡片透出来的就是这层色板而不是壁纸。它按底栏的布局槽位定位（负 offset 移出），
 * 因此键盘弹出时跟着输入栏一起上浮，不会留在屏幕中部。
 */
internal val BottomDissolveHeight = 48.dp

@Composable
internal fun BottomDissolve(
    setting: Settings,
    modifier: Modifier = Modifier,
) {
    val assistant = setting.getCurrentAssistant()
    val darkTheme = LocalDarkMode.current
    val photoShown = chatPhotoShown(
        effectiveBackground(
            darkTheme = darkTheme,
            background = assistant.background,
            darkBackground = assistant.darkBackground,
            lightBackground = assistant.lightBackground,
            mode = assistant.backgroundMode,
        ),
        assistant.useGradientBackground,
    )
    val fade = bottomBarFade(darkTheme = darkTheme, photoShown = photoShown)
    val fadeColor = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(BottomDissolveHeight)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.6f to fadeColor.copy(alpha = fade.midAlpha),
                    1f to fadeColor.copy(alpha = fade.bottomAlpha),
                )
            )
    )
}
