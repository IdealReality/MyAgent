package me.rerere.myagent.ui.components.richtext

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.myagent.ui.components.webview.WEB_VIEW_BASE_URL
import me.rerere.myagent.ui.components.webview.WEB_VIEW_HOST
import me.rerere.myagent.ui.components.webview.WebContent
import me.rerere.myagent.ui.components.webview.WebView as ComposeWebView
import me.rerere.myagent.ui.components.webview.WebViewState
import me.rerere.myagent.ui.theme.LocalDarkMode
import java.util.Locale
import kotlin.math.roundToInt

private const val SETTLE_DELAY_MS = 220L

/** 还没拿到页面实测高度时的占位高度。 */
private const val DEFAULT_HEIGHT_DP = 260
private const val MIN_HEIGHT_DP = 120

/** 预览高度上限占屏幕高度的比例：再高就交给 WebView 内部滚动，否则一条消息能占满整屏。 */
private const val MAX_HEIGHT_RATIO = 0.68f

/** 页面上报高度的上限，防止异常布局撑爆消息列表。 */
private const val MAX_REPORTED_HEIGHT = 20_000

private val LINK_SCHEMES = setOf("http", "https", "mailto", "tel")

/**
 * 消息流里的可交互网页预览。
 *
 * 与 [Mermaid] 的区别：Mermaid 是固定 200dp 的静态图；这里承载的是模型生成的**页面**，
 * 需要真实高度、内部滚动、手势协调（WebView 滚不动时把滚动还给消息列表）、可点击交互，
 * 并且流式输出期间不能每个 token 重建一次 WebView。
 */
@Composable
internal fun InteractiveHtmlPreview(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val theme = rememberWebPreviewTheme()
    val kind = remember(language) { WebPreview.kindOf(language) ?: WebPreviewKind.HTML }

    // 流式输出时正文每个 token 都会重渲染代码块（MarkdownNew 在围栏未闭合时也会出块），
    // 立刻重建 WebView 会反复重载页面并把用户的操作状态清空。先渲染首帧，之后等内容
    // 安静 SETTLE_DELAY_MS 再换文档。
    var settled by remember { mutableStateOf(code) }
    LaunchedEffect(code) {
        if (settled == code) return@LaunchedEffect
        delay(SETTLE_DELAY_MS)
        settled = code
    }
    val html = remember(settled, kind, theme) { WebPreview.document(settled, kind, theme) }

    // JS 上报的是 CSS px；viewport 已强制为 width=device-width 且不缩放，所以 1 CSS px ≈ 1 dp。
    var contentHeightDp by remember { mutableIntStateOf(0) }
    val bridge = remember(context) {
        WebPreviewBridge(context) { height -> contentHeightDp = height }
    }
    val state = remember {
        WebViewState(
            initialContent = WebContent.Data(html, WEB_VIEW_BASE_URL, "utf-8", "text/html", null),
            interfaces = mapOf(WebPreview.BRIDGE_NAME to bridge),
            settings = {
                javaScriptEnabled = true
                domStorageEnabled = true
                // 双指缩放用得上（密集图表），但不显示缩放控件。
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                textZoom = 100
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                // 互动页面不应在用户没点的情况下出声。
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            },
        )
    }

    // 页面里的任何导航（location.href、表单提交、meta refresh）都在预览内拦下：
    // 这块 WebView 嵌在消息流里，既没有地址栏也没有返回键，跟过去就再也回不来。
    val overrideNavigation = remember(context) {
        { url: String ->
            // 相对链接会解析成 https://myagent.local/… —— 那是预览自己的 origin，
            // 没有可去的地方，直接丢掉（与页面内 click 拦截的判定保持一致）。
            if (!isPreviewOrigin(url)) openExternally(context, url)
            true
        }
    }

    LaunchedEffect(html) {
        // 内容相同时 WebView 的 update 会比较 lastLoadedData，不会重复加载。
        state.loadData(html, WEB_VIEW_BASE_URL, "utf-8", "text/html")
    }

    val maxHeightDp = (LocalConfiguration.current.screenHeightDp * MAX_HEIGHT_RATIO)
        .coerceAtLeast(MIN_HEIGHT_DP.toFloat())
    val heightDp = if (contentHeightDp > 0) {
        contentHeightDp.coerceIn(MIN_HEIGHT_DP, maxHeightDp.roundToInt())
    } else {
        maxHeightDp.roundToInt().coerceAtMost(DEFAULT_HEIGHT_DP)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colorScheme.surfaceVariant.copy(alpha = 0.18f)),
    ) {
        ComposeWebView(
            state = state,
            modifier = Modifier.fillMaxSize(),
            onCreated = { webView ->
                webView.setBackgroundColor(AndroidColor.TRANSPARENT)
                webView.isVerticalScrollBarEnabled = false
                webView.isHorizontalScrollBarEnabled = false
                // 滚到边界时不要画 Android 的边缘辉光：那是「已经到底」的原生提示，
                // 而这里到底意味着「该把滑动还给消息列表」，两者视觉上会打架。
                webView.overScrollMode = View.OVER_SCROLL_NEVER
                attachScrollPassthrough(webView)
            },
            overrideNavigation = overrideNavigation,
        )
    }
}

/**
 * WebView 在可滚动方向上独占手势，滚到边界后把滑动还给消息列表。
 *
 * 不做这件事时有两种坏结果：要么 Compose 的列表抢走所有竖滑（长页面根本滚不动），
 * 要么 WebView 永远吃掉手势（预览下方的消息翻不过去）。
 */
@SuppressLint("ClickableViewAccessibility")
private fun attachScrollPassthrough(webView: WebView) {
    var downY = 0f
    var disallowed = false
    webView.setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                disallowed = false
            }

            MotionEvent.ACTION_MOVE -> {
                // 双指是缩放手势：任何一侧都不该在这时候抢或还滚动。
                if (event.pointerCount > 1) {
                    if (disallowed) {
                        disallowed = false
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    return@setOnTouchListener false
                }
                val dy = event.y - downY
                val want = when {
                    dy < 0 -> view.canScrollVertically(1) // 手指上滑 = 内容向下翻
                    dy > 0 -> view.canScrollVertically(-1)
                    else -> false
                }
                if (want != disallowed) {
                    disallowed = want
                    view.parent?.requestDisallowInterceptTouchEvent(want)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (disallowed) {
                    disallowed = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        false
    }
}

/**
 * 页面 → 原生：高度上报与链接外开。
 * JS 调用发生在 JavaBridge 线程，所有 Compose 状态更新都 post 回主线程。
 */
private class WebPreviewBridge(
    private val context: Context,
    private val onContentHeight: (Int) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun setContentHeight(height: Int) {
        if (height <= 0) return
        val clamped = height.coerceAtMost(MAX_REPORTED_HEIGHT)
        main.post { onContentHeight(clamped) }
    }

    @JavascriptInterface
    fun openLink(url: String) {
        main.post { openExternally(context, url) }
    }
}

/**
 * 把链接交给系统浏览器。
 *
 * 只放行有外部处理者的 scheme：`data:` / `blob:` / `javascript:` / `intent:` / `file:`
 * 一律丢弃 —— 预览是模型生成的页面，不能让它借这条通道拉起任意组件或本地文件。
 */
internal fun openExternally(context: Context, url: String): Boolean {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme !in LINK_SCHEMES) return false
    val intent = Intent(Intent.ACTION_VIEW, uri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}

/** 是否是预览文档自己的虚拟 origin（`https://myagent.local/…`）。 */
internal fun isPreviewOrigin(url: String): Boolean {
    val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return false
    return host.equals(WEB_VIEW_HOST, ignoreCase = true)
}

private fun Color.toCssColor(): String {
    val r = (red * 255).roundToInt().coerceIn(0, 255)
    val g = (green * 255).roundToInt().coerceIn(0, 255)
    val b = (blue * 255).roundToInt().coerceIn(0, 255)
    val a = alpha.coerceIn(0f, 1f)
    return String.format(Locale.ROOT, "rgba(%d, %d, %d, %.3f)", r, g, b, a)
}

/** 内联预览与全屏预览共用的主题映射（颜色只在 [WebPreview] 里拼进 CSS）。 */
@Composable
internal fun rememberWebPreviewTheme(): WebPreviewTheme {
    val colorScheme = MaterialTheme.colorScheme
    val darkMode = LocalDarkMode.current
    return remember(colorScheme, darkMode) {
        WebPreviewTheme(
            dark = darkMode,
            background = colorScheme.background.toCssColor(),
            foreground = colorScheme.onSurface.toCssColor(),
            muted = colorScheme.onSurfaceVariant.toCssColor(),
            surface = colorScheme.surfaceContainerHigh.toCssColor(),
            border = colorScheme.outlineVariant.toCssColor(),
            primary = colorScheme.primary.toCssColor(),
        )
    }
}
