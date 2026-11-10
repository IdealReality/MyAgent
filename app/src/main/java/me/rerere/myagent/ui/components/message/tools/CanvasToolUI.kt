package me.rerere.myagent.ui.components.message.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.myagent.data.ai.tools.local.canvasStoreDir
import me.rerere.myagent.ui.components.richtext.WebPreview
import me.rerere.myagent.ui.components.richtext.WebPreviewKind
import me.rerere.myagent.ui.components.richtext.WebPreviewTheme
import me.rerere.myagent.ui.components.richtext.rememberWebPreviewTheme
import me.rerere.myagent.ui.components.webview.WEB_VIEW_BASE_URL
import me.rerere.myagent.ui.components.webview.WebContent
import me.rerere.myagent.ui.components.webview.WebView
import me.rerere.myagent.ui.components.webview.WebViewState
import androidx.compose.ui.graphics.Color as ComposeColor
import android.graphics.Color as AndroidColor
import java.io.File

private const val CANVAS_ASSET = "html/canvas.html"
private const val CANVAS_B64_SLOT = "__CANVAS_B64__"
private const val CANVAS_MIN_HEIGHT_DP = 180
private const val CANVAS_MAX_HEIGHT_RATIO = 0.78f

private fun JsonObject?.canvasStr(key: String): String? =
    runCatching { this?.get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()

/**
 * 把画布数据塞进 `assets/html/canvas.html`，再走和 ```html 代码块完全相同的一条渲染路径
 * （[WebPreview.document] 注入 viewport/主题变量/高度上报，[WebView] 承载页面）。
 *
 * 这样画布和聊天里那些互动页面是同一套东西：同一份主题、同样的高度自适应、
 * 同一个 JS 桥。区别只在于页面的脚本会把编辑结果写回磁盘，供 `canvas_read` 再读出来。
 */
private fun buildCanvasDocument(
    context: Context,
    payloadJson: String,
    theme: WebPreviewTheme,
): String = runCatching {
    val raw = context.assets.open(CANVAS_ASSET).bufferedReader().use { it.readText() }
    val encoded = Base64.encodeToString(payloadJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    WebPreview.document(raw.replace(CANVAS_B64_SLOT, encoded), WebPreviewKind.HTML, theme)
}.getOrElse { "" }

/**
 * 画布页面的 JS 桥。
 *
 * 名字复用 [WebPreview.BRIDGE_NAME]，因此 [WebPreview] 注入的 ResizeObserver 高度上报
 * 直接落到 [setContentHeight]，不需要页面自己算高度。
 */
private class CanvasBridge(
    private val context: Context,
    private val canvasId: String?,
    private val onHeight: (Int) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun setContentHeight(px: Int) {
        val safe = px.coerceIn(0, 40_000)
        main.post { onHeight(safe) }
    }

    @JavascriptInterface
    fun save(json: String) {
        val id = canvasId ?: return
        runCatching { File(canvasStoreDir(context), "$id.json").writeText(json) }
    }

    @JavascriptInterface
    fun copy(text: String) {
        main.post {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            manager?.setPrimaryClip(ClipData.newPlainText("canvas", text))
            Toast.makeText(context, "画布 JSON 已复制", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun CanvasBoardContent(
    content: JsonElement?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val theme = rememberWebPreviewTheme()
    val root = content as? JsonObject
    val canvasId = remember(root) { root.canvasStr("canvas_id") }
    val payloadJson = remember(root) { root?.toString().orEmpty() }

    if (root == null || payloadJson.isEmpty()) {
        Box(modifier.padding(12.dp)) {
            Text(
                text = if (content == null) "画布生成中…" else "画布数据为空",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        return
    }

    var heightPx by remember(canvasId) { mutableIntStateOf(0) }
    val html = remember(payloadJson, theme) { buildCanvasDocument(context, payloadJson, theme) }
    val bridge = remember(canvasId) { CanvasBridge(context, canvasId) { heightPx = it } }

    val state = remember(canvasId) {
        WebViewState(
            initialContent = WebContent.Data(html, WEB_VIEW_BASE_URL, "utf-8", "text/html", null),
            interfaces = mapOf(WebPreview.BRIDGE_NAME to bridge),
            settings = {
                javaScriptEnabled = true
                domStorageEnabled = true
                setSupportZoom(false)
                builtInZoomControls = false
                useWideViewPort = true
                textZoom = 100
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            },
        )
    }

    LaunchedEffect(html) {
        if (html.isNotEmpty()) {
            state.loadData(html, WEB_VIEW_BASE_URL, "utf-8", "text/html")
        }
    }

    // JS 上报的是 CSS px；viewport 已固定 width=device-width 且不缩放，1 CSS px ≈ 1 dp。
    val maxHeightDp = (LocalConfiguration.current.screenHeightDp * CANVAS_MAX_HEIGHT_RATIO)
        .toInt()
        .coerceAtLeast(CANVAS_MIN_HEIGHT_DP)
    val heightDp = if (heightPx > 0) {
        heightPx.coerceIn(CANVAS_MIN_HEIGHT_DP, maxHeightDp)
    } else {
        CANVAS_MIN_HEIGHT_DP
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f))
    ) {
        WebView(
            state = state,
            modifier = Modifier.fillMaxSize(),
            onCreated = { webView ->
                webView.setBackgroundColor(AndroidColor.TRANSPARENT)
                webView.isVerticalScrollBarEnabled = false
                webView.isHorizontalScrollBarEnabled = false
            },
        )
    }
}

object CanvasToolUI : ToolUIRenderer {
    override val toolName: String = "canvas_render"

    @Composable
    override fun title(context: ToolUIContext): String {
        val name = (context.content as? JsonObject).canvasStr("title")
        return if (name.isNullOrBlank()) "画布" else "画布 · $name"
    }

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        CanvasBoardContent(
            content = context.content,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        Box(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            CanvasBoardContent(content = context.content, modifier = Modifier.fillMaxWidth())
        }
    }
}