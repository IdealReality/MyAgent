package me.rerere.myagent.ui.pages.extensions.plugins

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.myagent.plugin.PluginRegistry
import me.rerere.myagent.ui.components.nav.BackButton
import me.rerere.myagent.ui.components.richtext.WebPreview
import me.rerere.myagent.ui.components.richtext.WebPreviewTheme
import me.rerere.myagent.ui.components.richtext.isPreviewOrigin
import me.rerere.myagent.ui.components.richtext.openExternally
import me.rerere.myagent.ui.components.richtext.rememberWebPreviewTheme
import me.rerere.myagent.ui.components.webview.WEB_VIEW_BASE_URL
import me.rerere.myagent.ui.components.webview.WebContent
import me.rerere.myagent.ui.components.webview.WebView
import me.rerere.myagent.ui.components.webview.WebViewState
import me.rerere.myagent.ui.context.LocalNavController
import org.koin.compose.koinInject

/** 面板 JS 桥的名字（页面里用 `MyAgentHost.*`）。 */
private const val PANEL_BRIDGE = "MyAgentHost"

/**
 * 插件声明的 `ui.panel` 页面。
 *
 * 这是「模型写的插件」变成「应用里真实界面」的那一步：插件目录里的 HTML/CSS/JS 直接在
 * 应用内渲染，能引用同目录资源（`https://myagent.local/plugin/<id>/…`），能调用宿主
 * 桥拿到主题、弹提示、关闭页面。不需要编译、不需要重启 —— 模型写完插件，点开就是一张
 * 新界面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginPanelPage(pluginId: String) {
    val registry: PluginRegistry = koinInject()
    val context = LocalContext.current
    val navigator = LocalNavController.current
    val theme = rememberWebPreviewTheme()

    var html by remember(pluginId) { mutableStateOf<String?>(null) }
    var loading by remember(pluginId) { mutableStateOf(true) }
    val title = remember(pluginId) { registry.panelTitleOf(pluginId) }

    LaunchedEffect(pluginId) {
        html = withContext(Dispatchers.IO) { registry.panelHtml(pluginId) }
        loading = false
    }

    // 复用预览的文档内核：主题变量、charset 归一、CSP 清理、链接拦截都一样，
    // 面板因此天然跟随亮暗主题，也不会把 WebView 导航走。
    val document = remember(html, theme) {
        html?.let { WebPreview.fullPage(it, "html", theme) }
    }

    val bridge = remember(pluginId, theme) {
        PanelBridge(
            context = context,
            pluginId = pluginId,
            themeJson = theme.toBridgeJson(),
            onClose = { navigator.popBackStack() },
        )
    }
    val state = remember(pluginId) {
        WebViewState(
            initialContent = WebContent.NavigatorOnly,
            interfaces = mapOf(PANEL_BRIDGE to bridge),
            settings = {
                javaScriptEnabled = true
                domStorageEnabled = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                textZoom = 100
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            },
        )
    }

    // 面板里的相对资源要落在插件自己的目录前缀下，否则 style.css 会解析到站点根。
    val baseUrl = "$WEB_VIEW_BASE_URL/plugin/$pluginId/"

    LaunchedEffect(document) {
        document?.let { state.loadData(it, baseUrl, "utf-8", "text/html") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                document == null -> Text(
                    text = "This plugin declares no ui.panel (or the panel file is unreadable).",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> WebView(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    overrideNavigation = { url ->
                        // 面板自己目录下的跳转放行（多页面板）；外部链接交给系统浏览器。
                        if (!isPreviewOrigin(url)) {
                            openExternally(context, url)
                            true
                        } else {
                            false
                        }
                    },
                )
            }
        }
    }
}

/**
 * 面板 → 原生。只有这几个方法：面板不该凭空获得改应用状态的能力，
 * 需要更深的控制就走插件工具那条有权限声明、有校验的通道。
 */
private class PanelBridge(
    private val context: Context,
    private val pluginId: String,
    private val themeJson: String,
    private val onClose: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun close() {
        main.post(onClose)
    }

    @JavascriptInterface
    fun toast(message: String) {
        main.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun theme(): String = themeJson

    @JavascriptInterface
    fun plugin(): String = pluginId
}

/** 面板拿到的主题：与注入的 CSS 变量同源，插件写一次两处都跟随亮暗。 */
private fun WebPreviewTheme.toBridgeJson(): String =
    """{"dark":$dark,"foreground":"$foreground","muted":"$muted","surface":"$surface",""" +
        """"border":"$border","primary":"$primary","background":"$background"}"""
