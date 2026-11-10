package me.rerere.myagent.ui.components.webview

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse

/**
 * 应用内 WebView 使用的虚拟域名，用于给 loadDataWithBaseURL 提供一个稳定的 origin。
 */
const val WEB_VIEW_BASE_URL = "https://myagent.local"

/**
 * 虚拟域名下的静态资源前缀，会被映射到 app 的 assets 目录。
 *
 * 例如 `https://myagent.local/assets/html/mermaid.min.js` 对应 `assets/html/mermaid.min.js`，
 * 这样 mermaid/katex 之类的库可以本地加载，而不用从 CDN 下载（首次加载可能需要几十秒）。
 */
const val WEB_VIEW_ASSET_URL = "$WEB_VIEW_BASE_URL/assets"

/** 预览文档的虚拟主机名。页面内的相对链接会解析到这个 origin，不该当成外部链接。 */
const val WEB_VIEW_HOST = "myagent.local"

/** 插件面板资源的虚拟前缀：`https://myagent.local/plugin/<id>/<path>`。 */
const val WEB_VIEW_PLUGIN_PREFIX = "/plugin/"

private const val ASSET_HOST = WEB_VIEW_HOST
private const val ASSET_PATH_PREFIX = "/assets/"

internal object WebViewLocalAssets {
    /**
     * 插件目录解析器，由 DI 在启动时注册（`(pluginId, relativePath) -> File?`）。
     *
     * 用注册式而不是直接依赖 PluginStore：这里是 WebView 层的 object，注入 Koin 会让
     * 每个渲染路径都反查容器。未注册时插件资源请求只是 404，不影响其他页面。
     */
    @Volatile
    var pluginFileResolver: ((String, String) -> java.io.File?)? = null

    fun intercept(context: Context, uri: Uri): WebResourceResponse? {
        if (!uri.host.equals(ASSET_HOST, ignoreCase = true)) return null
        val path = uri.path ?: return null
        if (path.startsWith(ASSET_PATH_PREFIX)) return assetResponse(context, path)
        if (path.startsWith(WEB_VIEW_PLUGIN_PREFIX)) return pluginResponse(path)
        return null
    }

    private fun assetResponse(context: Context, path: String): WebResourceResponse? {
        val assetPath = path.removePrefix(ASSET_PATH_PREFIX)
        if (assetPath.isEmpty() || assetPath.contains("..")) return null

        val mimeType = mimeTypeOf(assetPath)
        return runCatching {
            WebResourceResponse(
                mimeType,
                if (mimeType.startsWith("text/") || mimeType.endsWith("json") || mimeType.endsWith("xml")) "UTF-8" else null,
                context.assets.open(assetPath)
            )
        }.getOrNull()
    }

    /**
     * `https://myagent.local/plugin/<id>/<rel>` → 插件目录里的文件。
     *
     * 面板可以像普通网页一样 `<link rel=stylesheet href="style.css">`、`<script src=app.js>`，
     * 不需要把资源内联成 data URL。路径穿越由 PluginStore.resolveFile 兜住。
     */
    private fun pluginResponse(path: String): WebResourceResponse? {
        val resolver = pluginFileResolver ?: return null
        val rest = path.removePrefix(WEB_VIEW_PLUGIN_PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val id = rest.substring(0, slash)
        val rel = rest.substring(slash + 1).ifEmpty { return null }
        val file = runCatching { resolver(id, rel) }.getOrNull() ?: return null
        if (!file.isFile) return null
        val mimeType = mimeTypeOf(rel)
        return runCatching {
            WebResourceResponse(
                mimeType,
                if (mimeType.startsWith("text/") || mimeType.endsWith("json") || mimeType.endsWith("xml")) "UTF-8" else null,
                file.inputStream(),
            )
        }.getOrNull()
    }

    private fun mimeTypeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }
}
