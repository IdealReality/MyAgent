package me.rerere.myagent.ui.components.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.myagent.Screen
import me.rerere.myagent.plugin.PluginRegistry
import me.rerere.myagent.plugin.PluginSlot
import me.rerere.myagent.plugin.PluginSlotAction
import me.rerere.myagent.plugin.PluginValidationRules
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
import me.rerere.myagent.ui.context.Navigator
import org.koin.compose.koinInject

/** 槽位网页里拿宿主能力的桥名（与插件面板一致：`MyAgentHost`）。 */
private const val SLOT_BRIDGE = "MyAgentHost"

/**
 * 聊天内槽位的渲染点。
 *
 * 两种形态共用一套语义：`chips` 是原生按钮（便宜、跟随主题），`html` 是插件目录里的网页
 * （任意布局，但一个有高度的 WebView）。内容全部来自磁盘，所以「让对话把界面改成什么样」
 * 在改完文件的那一刻就成立，不需要重装。
 *
 * [argsProvider] 而不是 `args: String`：上下文里有「当前草稿/当前消息正文」，流式生成时每
 * 个 token 都在变。传字符串会让整块槽位（含 WebView）跟着每个 token 重组，而槽位真正需要
 * 它的时刻只有「点了按钮」或「网页调 args()」。传 lambda 后组合期一次都不读，也就不订阅。
 */
@Composable
fun PluginSlots(
    slots: List<PluginSlot>,
    argsProvider: () -> String,
    onInsert: (String) -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (slots.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        slots.forEach { slot ->
            key("${slot.pluginId}#${slot.index}") {
                when (slot.spec.type) {
                    PluginValidationRules.SLOT_TYPE_HTML -> PluginHtmlSlot(
                        slot = slot,
                        argsProvider = argsProvider,
                        onInsert = onInsert,
                        onSend = onSend,
                    )

                    else -> PluginChipsSlot(
                        slot = slot,
                        argsProvider = argsProvider,
                        onInsert = onInsert,
                        onSend = onSend,
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginChipsSlot(
    slot: PluginSlot,
    argsProvider: () -> String,
    onInsert: (String) -> Unit,
    onSend: (String) -> Unit,
) {
    val registry: PluginRegistry = koinInject()
    val appContext = LocalContext.current.applicationContext
    val navigator = LocalNavController.current
    val scope = rememberCoroutineScope()
    // 动作最长可以跑 5 秒：连点会让同一个动作并发起多个 QuickJS 上下文，还会重复插入文本。
    // 一个槽位一行，按钮共用这个闸，等结果回来再放行。
    var running by remember(slot.pluginId, slot.index) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (slot.spec.title.isNotBlank()) {
            Text(
                text = slot.spec.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        slot.spec.items.forEach { item ->
            AssistChip(
                enabled = !running,
                onClick = {
                    val panel = item.panel
                    if (panel != null) {
                        navigator.navigate(Screen.PluginPanel(panel.ifBlank { slot.pluginId }))
                        return@AssistChip
                    }
                    if (running) return@AssistChip
                    running = true
                    scope.launch {
                        try {
                            val result = registry.runSlotAction(
                                pluginId = slot.pluginId,
                                action = item.action,
                                argsJson = argsProvider(),
                            )
                            applySlotResult(
                                result = result,
                                context = appContext,
                                navigator = navigator,
                                pluginId = slot.pluginId,
                                onInsert = onInsert,
                                onSend = onSend,
                            )
                        } finally {
                            running = false
                        }
                    }
                },
                label = {
                    Text(
                        text = if (item.icon.isBlank()) item.label else "${item.icon} ${item.label}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun PluginHtmlSlot(
    slot: PluginSlot,
    argsProvider: () -> String,
    onInsert: (String) -> Unit,
    onSend: (String) -> Unit,
) {
    val activityContext = LocalContext.current
    val appContext = activityContext.applicationContext
    val navigator = LocalNavController.current
    val registry: PluginRegistry = koinInject()
    val theme = rememberWebPreviewTheme()
    val html = slot.html

    if (html == null) {
        Text(
            text = "plugin '${slot.pluginId}' slot file is unreadable",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        return
    }

    val document = remember(html, theme) { WebPreview.fullPage(html, "html", theme) }

    // 桥要活过整个槽位生命周期：WebViewState 在创建时就带上了 interfaces，之后重组换新桥
    // 也不会被重新注册。所以这里只建一次，参数与回调全部走 rememberUpdatedState 读最新值
    // （否则 args() 会永远返回第一次组合时的会话/草稿）。
    val latestArgs = rememberUpdatedState(argsProvider)
    val latestInsert = rememberUpdatedState(onInsert)
    val latestSend = rememberUpdatedState(onSend)
    val latestOpenPanel = rememberUpdatedState<(String) -> Unit> { id ->
        navigator.navigate(Screen.PluginPanel(id.ifBlank { slot.pluginId }))
    }
    val bridge = remember(slot.pluginId, slot.index, theme) {
        SlotBridge(
            context = appContext,
            pluginId = slot.pluginId,
            theme = theme,
            registry = registry,
            argsProvider = { latestArgs.value() },
            onInsert = { text -> latestInsert.value(text) },
            onSend = { text -> latestSend.value(text) },
            onOpenPanel = { id -> latestOpenPanel.value(id) },
        )
    }

    val state = remember(slot.pluginId, slot.index) {
        WebViewState(
            initialContent = WebContent.NavigatorOnly,
            interfaces = mapOf(SLOT_BRIDGE to bridge),
            settings = {
                javaScriptEnabled = true
                domStorageEnabled = true
                setSupportZoom(false)
                builtInZoomControls = false
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
    val baseUrl = "$WEB_VIEW_BASE_URL/plugin/${slot.pluginId}/"
    LaunchedEffect(document) {
        state.loadData(document, baseUrl, "utf-8", "text/html")
    }

    val heightDp = slot.spec.height.coerceIn(
        PluginValidationRules.MIN_SLOT_HEIGHT,
        PluginValidationRules.MAX_SLOT_HEIGHT,
    ).dp

    WebView(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp),
        overrideNavigation = { url ->
            // 槽位里的跳转不该把聊天页面顶掉：站内相对资源放行，外部链接交给系统浏览器。
            if (isPreviewOrigin(url) || url.startsWith(WEB_VIEW_BASE_URL)) {
                false
            } else {
                openExternally(appContext, url)
                true
            }
        },
    )
}

/** 把动作返回值落到界面上：提示 → 填字 → 发送 → 开面板（顺序固定，便于预测）。 */
private fun applySlotResult(
    result: PluginSlotAction,
    context: Context,
    navigator: Navigator,
    pluginId: String,
    onInsert: (String) -> Unit,
    onSend: (String) -> Unit,
) {
    if (!result.ok) {
        Toast.makeText(context, result.error ?: "plugin action failed", Toast.LENGTH_SHORT).show()
        return
    }
    result.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    result.insert?.let(onInsert)
    result.send?.let(onSend)
    result.panel?.let { navigator.navigate(Screen.PluginPanel(it.ifBlank { pluginId })) }
}

/**
 * 槽位网页 → 宿主。
 *
 * 和插件面板的桥的区别只有一处：多了 `call(action, args)` 与 `args()`。前者让网页里的按钮
 * 复用 manifest 里声明的动作（于是权限/声明校验只有一套），后者把当前会话与消息交给网页。
 *
 * 所有会变的东西都以 lambda 形式传入：桥只建一次，值在调用时刻取。
 */
private class SlotBridge(
    private val context: Context,
    private val pluginId: String,
    theme: WebPreviewTheme,
    private val registry: PluginRegistry,
    private val argsProvider: () -> String,
    private val onInsert: (String) -> Unit,
    private val onSend: (String) -> Unit,
    private val onOpenPanel: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val themeJson = theme.toBridgeJson()

    /**
     * 同步返回，因为 `@JavascriptInterface` 不能挂起。调用发生在 WebView 的 JS 桥线程上
     * （不是主线程），所以 runBlocking 只挡住这一个网页脚本，不冻结界面。
     */
    @SuppressLint("JavascriptInterface")
    @JavascriptInterface
    fun call(action: String, actionArgsJson: String?): String {
        val payload = actionArgsJson?.takeIf { it.isNotBlank() } ?: argsProvider()
        val result = runBlocking { registry.runSlotAction(pluginId, action, payload) }
        return buildJsonObject {
            put("ok", result.ok)
            result.insert?.let { put("insert", it) }
            result.send?.let { put("send", it) }
            result.toast?.let { put("toast", it) }
            result.panel?.let { put("panel", it) }
            result.text?.let { put("text", it) }
            result.error?.let { put("error", it) }
        }.toString()
    }

    @JavascriptInterface
    fun insert(text: String) {
        main.post { onInsert(text) }
    }

    @JavascriptInterface
    fun send(text: String) {
        main.post { onSend(text) }
    }

    @JavascriptInterface
    fun openPanel(id: String) {
        main.post { onOpenPanel(id) }
    }

    @JavascriptInterface
    fun toast(message: String) {
        main.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun theme(): String = themeJson

    @JavascriptInterface
    fun plugin(): String = pluginId

    @JavascriptInterface
    fun args(): String = argsProvider()
}

private fun WebPreviewTheme.toBridgeJson(): String =
    """{"dark":$dark,"foreground":"$foreground","muted":"$muted","surface":"$surface",""" +
        """"border":"$border","primary":"$primary","background":"$background"}"""
