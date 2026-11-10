package me.rerere.myagent.ui.components.richtext

/**
 * 「互动图 / 网页」代码块的内联渲染内核。
 *
 * 模型把 HTML（或裸 SVG）写进 ```html / ```interactive 代码块后，本对象把它变成一份
 * 自包含的预览文档：
 * - 注入移动端 viewport，片段在手机宽度下排版而不是按桌面宽度溢出；
 * - 注入主题 CSS 变量（`--rk-fg` / `--rk-muted` / `--rk-surface` / `--rk-border` /
 *   `--rk-primary`），页面不写颜色也能跟随 App 的亮暗主题；
 * - 注入高度上报脚本（ResizeObserver → `MyAgentPreview.setContentHeight`），
 *   消息流里按内容真实高度铺开，而不是固定一个 200dp 的小窗口；
 * - 拦截普通链接点击（预览窗口没有地址栏也没有返回键），交给系统浏览器打开；
 * - 把作者写的 `<meta charset>` 归一成 utf-8（代码块本身就是 UTF-8，声明成 gbk 只会乱码）；
 * - 结构判定只认真正的标签：注释、`<script>`/`<style>` 内容里的 `<html>` 不算骨架
 *   （否则整份注入会落进注释，预览丢掉 viewport 与主题变量）。
 *
 * 纯 Kotlin、无 Android 依赖，方便单测（见 `WebPreviewTest`）。
 */
internal object WebPreview {

    /** 内联自动预览的代码上限：超过它只在工具栏保留「预览」入口，不把巨型文档塞进消息流。 */
    const val MAX_INLINE_CHARS = 400_000

    /** JS 桥名字。页面脚本可以直接调用 `MyAgentPreview.*`。 */
    const val BRIDGE_NAME = "MyAgentPreview"

    private val HTML_LANGUAGES = setOf("html", "htm", "webpage", "web", "interactive")
    private val SVG_LANGUAGES = setOf("svg")

    /** 走 WebView 预览的语言集合（HighlightCodeBlock 的工具栏/默认预览判定用）。 */
    val previewableLanguages: Set<String> = HTML_LANGUAGES + SVG_LANGUAGES

    private val FULL_DOCUMENT = Regex("""(?is)<\s*(!doctype|html)\b""")
    private val HEAD_OPEN = Regex("""(?is)<head\b[^>]*>""")
    private val HTML_OPEN = Regex("""(?is)<html\b[^>]*>""")
    private val BODY_CLOSE = Regex("""(?is)</body\s*>""")
    private val HTML_CLOSE = Regex("""(?is)</html\s*>""")

    /** 命中已有的 viewport 声明（含属性顺序不同的写法），用于改写而不是重复插入。 */
    private val VIEWPORT_META_TAG_REGEX =
        Regex("""(?is)<meta\b[^>]*name\s*=\s*["']?viewport["']?[^>]*>""")

    /**
     * 命中文档自带的编码声明。
     *
     * 代码块永远是 Kotlin 的 UTF-8 字符串，作者写的 `gbk` / `iso-8859-1` 只会让中文变成乱码
     * （实测 `<meta charset="gbk">` 会让 WebView 按 GBK 解码这份 UTF-8 文档）。
     */
    private val CHARSET_META_REGEX = Regex("""(?is)<meta\b[^>]*charset\s*=[^>]*>""")

    /**
     * 命中作者写的 CSP 声明。
     *
     * 预览依赖自己注入的 inline `<style>` 与 `<script>`（主题变量、高度上报）；
     * 一份 `default-src 'self'` 会把两者都拦掉，页面高度就永远停在占位值。
     */
    private val CSP_META_REGEX =
        Regex("""(?is)<meta\b[^>]*http-equiv\s*=\s*["']?content-security-policy["']?[^>]*>""")

    private const val UTF8_META_TAG = """<meta charset="utf-8">"""

    private const val VIEWPORT_META_TAG =
        """<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">"""

    /**
     * 内容会被浏览器当纯文本吞掉、但字面上仍可能出现 `<html>` 之类字样的元素。
     * 结构判定必须跳过它们的内容，否则 `<!-- <html> -->` 会把整份注入送进注释里。
     */
    private val RAW_TEXT_ELEMENTS = setOf("script", "style", "title", "textarea")

    fun kindOf(language: String): WebPreviewKind? = when (language.trim().lowercase()) {
        in HTML_LANGUAGES -> WebPreviewKind.HTML
        in SVG_LANGUAGES -> WebPreviewKind.SVG
        else -> null
    }

    fun supports(language: String): Boolean = kindOf(language) != null

    /** 是否值得内联渲染（超大文档交给全屏预览，避免消息流里塞一个几十万字符的页面）。 */
    fun fitsInline(code: String): Boolean = code.length <= MAX_INLINE_CHARS

    /**
     * 生成内联预览文档。
     *
     * 完整文档（自带 `<!doctype` / `<html`）只做注入，保留作者的 head/body；
     * 片段则包一层最小骨架。
     */
    fun document(code: String, kind: WebPreviewKind, theme: WebPreviewTheme): String =
        build(code, kind, theme, background = null)

    /**
     * 全屏预览（[me.rerere.myagent.Screen.WebView]）用的文档。
     *
     * 与内联不同：全屏的 WebView 没有气泡背景可透，页面若不自带背景就会露出宿主默认的
     * 白底 —— 深色主题下浅色正文直接不可读。这里把 html 的背景钉成主题背景色
     * （作者写在 body 上的背景仍然优先），并保留主题变量。
     */
    fun fullPage(code: String, language: String, theme: WebPreviewTheme): String =
        when (kindOf(language)) {
            WebPreviewKind.SVG, WebPreviewKind.HTML ->
                build(code, kindOf(language)!!, theme, background = theme.background)
            null -> code
        }

    private fun build(
        code: String,
        kind: WebPreviewKind,
        theme: WebPreviewTheme,
        background: String?,
    ): String {
        if (kind == WebPreviewKind.SVG) {
            return wrap("""<div class="rk-svg-root">$code</div>""", theme, background)
        }
        // 掩码一次用到底：400KB 的文档做两遍线性扫描纯属浪费。
        val masked = maskNoise(code)
        return if (FULL_DOCUMENT.containsMatchIn(masked)) {
            injectIntoDocument(code, masked, theme, background)
        } else {
            wrap(code, theme, background, masked)
        }
    }

    /**
     * 判断这是「自带骨架的完整文档」还是「一段片段」。
     *
     * 必须基于 [maskNoise]：模型经常在注释里举例 `<html>`，或把标签写进 JS 字符串；
     * 直接对原文做正则会让片段被当成完整文档，注入落进注释里（实测后果：页面丢掉
     * viewport 与全部主题变量，变成桌面宽度、无样式）。
     */
    fun isFullDocument(code: String): Boolean = FULL_DOCUMENT.containsMatchIn(maskNoise(code))

    private fun wrap(
        body: String,
        theme: WebPreviewTheme,
        background: String? = null,
        masked: String = maskNoise(body),
    ): String {
        val edits = ArrayList<Edit>(cspEdits(masked))
        VIEWPORT_META_TAG_REGEX.findAll(masked).forEach {
            edits += Edit(it.range.first, it.range.last + 1, VIEWPORT_META_TAG)
        }
        val withViewport = applyEdits(body, edits)
        return buildString {
            append("<!DOCTYPE html>\n<html>\n<head>\n")
            append(UTF8_META_TAG).append('\n')
            append(VIEWPORT_META_TAG).append('\n')
            append("<style>\n").append(baseCss(theme, background)).append("</style>\n</head>\n<body>\n")
            append(withViewport)
            append("\n</body>\n")
            append(HEIGHT_SCRIPT)
            append("\n</html>")
        }
    }

    private fun injectIntoDocument(
        code: String,
        masked: String,
        theme: WebPreviewTheme,
        background: String? = null,
    ): String {
        // 所有定位都基于等长的掩码文本：注释/脚本文本里的标签不算结构。
        val edits = ArrayList<Edit>(cspEdits(masked))

        // 统一 viewport：内联预览只有手机宽度，且 CSS px 必须等于 dp（高度上报按 dp 用）。
        val viewportMatches = VIEWPORT_META_TAG_REGEX.findAll(masked).toList()
        viewportMatches.forEach {
            edits += Edit(it.range.first, it.range.last + 1, VIEWPORT_META_TAG)
        }
        val hasViewport = viewportMatches.isNotEmpty()
        val hasCharset = CHARSET_META_REGEX.containsMatchIn(masked)

        val injection = buildString {
            append("<style>\n").append(baseCss(theme, background)).append("</style>\n")
            if (!hasViewport) append(VIEWPORT_META_TAG).append('\n')
            if (!hasCharset) append(UTF8_META_TAG).append('\n')
        }
        val head = HEAD_OPEN.find(masked)
        val html = HTML_OPEN.find(masked)
        when {
            head != null -> edits += Edit(head.range.last + 1, head.range.last + 1, "\n" + injection)
            html != null -> edits += Edit(
                html.range.last + 1,
                html.range.last + 1,
                "\n<head>\n" + injection + "</head>",
            )
            // 只有 doctype 没有 html/head：直接顶在最前面，浏览器会补全骨架。
            else -> edits += Edit(0, 0, injection)
        }

        // 上报脚本放在 </body> 之前。追加到 </html> 之后虽然浏览器容忍，但解析器会把它
        // 塞回 body 末尾，而「只有 doctype」的片段连 </html> 都没有。
        val bodyClose = BODY_CLOSE.find(masked)
        val htmlClose = HTML_CLOSE.find(masked)
        when {
            bodyClose != null -> edits +=
                Edit(bodyClose.range.first, bodyClose.range.first, HEIGHT_SCRIPT + "\n")
            htmlClose != null -> edits +=
                Edit(htmlClose.range.first, htmlClose.range.first, HEIGHT_SCRIPT + "\n")
            else -> edits += Edit(code.length, code.length, "\n" + HEIGHT_SCRIPT)
        }

        // charset 归一化最后做：改写 viewport 会改变长度，先做会让上面算好的位置失效。
        return normalizeCharset(applyEdits(code, edits))
    }

    /** 把作者写的编码声明统一改成 utf-8（代码块本身就是 UTF-8，声明成 gbk 只会乱码）。 */
    private fun normalizeCharset(html: String): String =
        applyEdits(
            html,
            CHARSET_META_REGEX.findAll(maskNoise(html)).map {
                Edit(it.range.first, it.range.last + 1, UTF8_META_TAG)
            }.toList(),
        )

    private data class Edit(val start: Int, val end: Int, val text: String)

    /** 删掉作者的 CSP 声明（注释里的不算，所以基于掩码文本匹配）。 */
    private fun cspEdits(masked: String): List<Edit> =
        CSP_META_REGEX.findAll(masked).map {
            Edit(it.range.first, it.range.last + 1, "")
        }.toList()

    /** 按位置从后往前应用编辑，这样先算好的下标不会因为前面的改写而失效。 */
    private fun applyEdits(source: String, edits: List<Edit>): String {
        if (edits.isEmpty()) return source
        val ordered = edits.sortedWith(
            compareByDescending<Edit> { it.start }.thenByDescending { it.end },
        )
        val builder = StringBuilder(source)
        for (edit in ordered) {
            builder.replace(edit.start, edit.end, edit.text)
        }
        return builder.toString()
    }

    /**
     * 把「浏览器会当纯文本」的区域抹成空格（长度不变）：注释、script/style/title/textarea 的内容。
     *
     * 只用于结构判定；掩码与原文逐字符对齐，所以掩码上找到的下标可以直接在原文上改写。
     * 保留换行，避免把 `-->` 之类的行内位置信息也糊掉。
     */
    fun maskNoise(code: String): String {
        val length = code.length
        val out = code.toCharArray()
        var i = 0

        fun blank(from: Int, to: Int) {
            var k = from
            val stop = minOf(to, length)
            while (k < stop) {
                if (out[k] != '\n') out[k] = ' '
                k++
            }
        }

        while (i < length) {
            if (code[i] != '<') {
                i++
                continue
            }
            if (code.startsWith("<!--", i)) {
                val end = code.indexOf("-->", i + 4)
                val stop = if (end < 0) length else end + 3
                blank(i, stop)
                i = stop
                continue
            }
            val tag = rawTextOpenTagAt(code, i)
            if (tag != null) {
                val close = indexOfRawTextClose(code, tag.first, tag.second)
                val stop = if (close < 0) {
                    length
                } else {
                    val gt = code.indexOf('>', close)
                    if (gt < 0) length else gt + 1
                }
                blank(i, stop)
                i = stop
                continue
            }
            i++
        }
        return String(out)
    }

    /** `<script ...` 这类元素：返回 (标签名, 内容起点)。闭合标签不算。 */
    private fun rawTextOpenTagAt(code: String, at: Int): Pair<String, Int>? {
        var i = at + 1
        val length = code.length
        if (i >= length || code[i] == '/') return null
        val nameStart = i
        while (i < length && code[i].isLetterOrDigit()) i++
        if (i == nameStart) return null
        val name = code.substring(nameStart, i).lowercase()
        if (name !in RAW_TEXT_ELEMENTS) return null
        if (i < length && code[i] != '>' && code[i] != '/' && !code[i].isWhitespace()) return null
        val gt = code.indexOf('>', i)
        if (gt < 0) return null
        return name to (gt + 1)
    }

    /** 找 `</name` 且后面跟 `>` / `/` / 空白（浏览器的原始文本元素结束规则）。 */
    private fun indexOfRawTextClose(code: String, name: String, from: Int): Int {
        var i = from
        while (true) {
            val index = code.indexOf("</$name", i, ignoreCase = true)
            if (index < 0) return -1
            val after = index + 2 + name.length
            if (after >= code.length) return index
            val ch = code[after]
            if (ch == '>' || ch == '/' || ch.isWhitespace()) return index
            i = index + 1
        }
    }

    private fun baseCss(theme: WebPreviewTheme, background: String? = null): String = """
        :root {
          color-scheme: ${if (theme.dark) "dark" else "light"};
          --rk-fg: ${theme.foreground};
          --rk-muted: ${theme.muted};
          --rk-surface: ${theme.surface};
          --rk-border: ${theme.border};
          --rk-primary: ${theme.primary};
        }
        html { -webkit-text-size-adjust: 100%; background: ${background ?: "transparent"}; }
        body {
          margin: 0;
          padding: 0;
          background: transparent;
          color: var(--rk-fg);
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, "Noto Sans SC", sans-serif;
          font-size: 14px;
          line-height: 1.5;
          overflow-wrap: break-word;
        }
        *, *::before, *::after { box-sizing: border-box; }
        img, svg, video, canvas { max-width: 100%; }
        a { color: var(--rk-primary); }
        table { border-collapse: collapse; max-width: 100%; }
        pre, code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
        .rk-svg-root { display: flex; align-items: center; justify-content: center; min-height: 120px; padding: 8px; }
        .rk-svg-root svg { max-width: 100%; height: auto; }
    """.trimIndent()

    /**
     * 高度上报 + 链接拦截。
     *
     * 高度取 documentElement / body 的 scrollHeight 与视口高度的最大值：页面用了
     * `height: 100vh` 时视口高度就是当前 WebView 高度，取 max 保证不会来回收缩。
     */
    private val HEIGHT_SCRIPT: String = """
        <script>
        (function () {
          if (window.__rikkaHubPreview) return;
          window.__rikkaHubPreview = true;
          var last = -1;
          var timer = null;
          function measure() {
            // 只量 body 的盒高。html 元素的 scrollHeight/offsetHeight 永远等于视口高度，
            // 用它会让「短内容 = 占位高度」并且内容收缩后也降不下来（实测：21px 的卡片
            // 占满 260dp，折叠 400px→40px 高度不变）。页面用 100% / 100vh 布局时，
            // body 自己的盒高本来就是视口高度，不需要再和 window.innerHeight 取 max。
            var b = document.body;
            if (!b) return 0;
            var h = Math.max(b.scrollHeight, b.offsetHeight);
            if (b.getBoundingClientRect) {
              h = Math.max(h, Math.ceil(b.getBoundingClientRect().height));
            }
            return Math.ceil(h);
          }
          function send(force) {
            var h = measure();
            if (!force && Math.abs(h - last) <= 2) return;
            last = h;
            try {
              if (window.MyAgentPreview) window.MyAgentPreview.setContentHeight(h);
            } catch (e) {}
          }
          function schedule() {
            if (timer) clearTimeout(timer);
            timer = setTimeout(function () { timer = null; send(false); }, 80);
          }
          function observe(target) {
            try { new ResizeObserver(schedule).observe(target); } catch (e) {}
          }
          if (document.documentElement) observe(document.documentElement);
          if (document.body) observe(document.body);
          window.addEventListener('load', function () {
            send(true);
            setTimeout(function () { send(true); }, 150);
            setTimeout(function () { send(true); }, 600);
          });
          document.addEventListener('DOMContentLoaded', function () { send(true); });
          window.addEventListener('animationend', schedule, true);
          window.addEventListener('transitionend', schedule, true);
          schedule();
          send(true);

          // 预览窗口没有地址栏和返回键：普通链接交给系统浏览器，页面内锚点放行。
          document.addEventListener('click', function (e) {
            var node = e.target;
            // 不用 'a[href]'：SVG 的 <a> 常用 xlink:href，匹配不到就漏拦。
            var anchor = node && node.closest ? node.closest('a') : null;
            if (!anchor) return;
            var href = anchor.getAttribute('href') || anchor.getAttribute('xlink:href') || '';
            if (!href || href.charAt(0) === '#') return;
            e.preventDefault();
            if (/^(javascript|data|blob):/i.test(href)) return;
            // SVG 的 anchor.href 是 SVGAnimatedString，直接传过去只会得到 "[object ...]"；
            // 统一用原始属性 + baseURI 自己算绝对地址。
            var abs = href;
            try { abs = new URL(href, document.baseURI).href; } catch (err) {}
            // 预览文档自己的相对链接（https://myagent.local/...）没有可去的地方。
            if (/^https?:\/\/myagent\.local(?::\d+)?\//i.test(abs)) return;
            try {
              if (window.MyAgentPreview) window.MyAgentPreview.openLink(String(abs));
            } catch (err) {}
          }, true);
        })();
        </script>
    """.trimIndent()
}

internal enum class WebPreviewKind { HTML, SVG }

/**
 * 预览文档的主题色（已是 CSS 可用的字符串）。
 * [dark] 同时用于 `color-scheme`，让表单控件、滚动条跟随 App 主题。
 */
internal data class WebPreviewTheme(
    val dark: Boolean,
    /** App 的页面底色：全屏预览时钉在 html 上，避免深色主题下露出宿主白底。 */
    val background: String,
    val foreground: String,
    val muted: String,
    val surface: String,
    val border: String,
    val primary: String,
)
