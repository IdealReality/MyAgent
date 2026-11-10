package me.rerere.myagent.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPreviewTest {

    private val dark = WebPreviewTheme(
        dark = true,
        background = "rgba(18, 18, 18, 1.000)",
        foreground = "rgba(255, 255, 255, 1.000)",
        muted = "rgba(200, 200, 200, 1.000)",
        surface = "rgba(30, 30, 30, 1.000)",
        border = "rgba(80, 80, 80, 1.000)",
        primary = "rgba(120, 160, 255, 1.000)",
    )
    private val light = dark.copy(dark = false)

    @Test
    fun `html family languages are recognised`() {
        listOf("html", "htm", "HTML", " webpage ", "web", "interactive", "Interactive")
            .forEach { assertEquals(it, WebPreviewKind.HTML, WebPreview.kindOf(it)) }
    }

    @Test
    fun `svg is its own kind`() {
        assertEquals(WebPreviewKind.SVG, WebPreview.kindOf("svg"))
        assertEquals(WebPreviewKind.SVG, WebPreview.kindOf(" SVG "))
    }

    @Test
    fun `unrelated languages are not previewable`() {
        listOf("", "kotlin", "json", "plaintext", "jsx").forEach {
            assertNull(it, WebPreview.kindOf(it))
            assertFalse(it, WebPreview.supports(it))
        }
    }

    @Test
    fun `previewable set matches the recognised languages`() {
        assertEquals(
            setOf("html", "htm", "webpage", "web", "interactive", "svg"),
            WebPreview.previewableLanguages,
        )
    }

    @Test
    fun `fragment is wrapped into a full document`() {
        val html = WebPreview.document("<div id=x>hi</div>", WebPreviewKind.HTML, dark)
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("""<div id=x>hi</div>"""))
        assertTrue(html.contains("""name="viewport"""))
        assertTrue(html.contains("--rk-fg: ${dark.foreground}"))
        assertTrue(html.contains("MyAgentPreview.setContentHeight"))
        assertTrue(html.contains("</body>"))
    }

    @Test
    fun `color scheme follows the theme`() {
        assertTrue(WebPreview.document("<p>x</p>", WebPreviewKind.HTML, dark).contains("color-scheme: dark"))
        assertTrue(WebPreview.document("<p>x</p>", WebPreviewKind.HTML, light).contains("color-scheme: light"))
    }

    @Test
    fun `full document keeps the author head and gets injected once`() {
        val code = """
            <!DOCTYPE html>
            <html>
            <head>
              <title>t</title>
              <style>body{background:#fff}</style>
            </head>
            <body><canvas id=c></canvas></body>
            </html>
        """.trimIndent()
        val html = WebPreview.document(code, WebPreviewKind.HTML, dark)

        assertTrue(html.contains("<title>t</title>"))
        assertTrue(html.contains("<style>body{background:#fff}</style>"))
        assertTrue(html.contains("<canvas id=c></canvas>"))
        // 只注入一份 viewport 与一份主题样式，不能覆盖作者的 head 内容。
        assertEquals(1, Regex("""name="viewport"""").findAll(html).count())
        assertEquals(1, Regex("""--rk-fg:""").findAll(html).count())
        // 脚本插在 </body> 之前，文档结构保持作者原样（不再追加到 </html> 之后）。
        assertTrue(html.trimEnd().endsWith("</html>"))
        assertTrue(html.indexOf("MyAgentPreview.setContentHeight") < html.lastIndexOf("</body>"))
    }

    @Test
    fun `existing viewport is rewritten to mobile width instead of duplicated`() {
        val code = """<html><head><meta name="viewport" content="width=1024"></head><body>x</body></html>"""
        val html = WebPreview.document(code, WebPreviewKind.HTML, dark)
        assertEquals(1, Regex("""name="viewport"""").findAll(html).count())
        assertFalse(html.contains("width=1024"))
        assertTrue(html.contains("width=device-width"))
    }

    @Test
    fun `document without head gets one`() {
        val code = """<!DOCTYPE html><html><body><p>a</p></body></html>"""
        val html = WebPreview.document(code, WebPreviewKind.HTML, dark)
        assertTrue(html.contains("<head>"))
        assertTrue(html.contains("--rk-fg"))
        assertTrue(html.contains("<p>a</p>"))
    }

    @Test
    fun `svg is embedded in a centred root`() {
        val svg = """<svg viewBox="0 0 10 10"><circle r="5"/></svg>"""
        val html = WebPreview.document(svg, WebPreviewKind.SVG, dark)
        assertTrue(html.contains("""class="rk-svg-root""""))
        assertTrue(html.contains(svg))
        assertTrue(html.contains("MyAgentPreview.setContentHeight"))
    }

    @Test
    fun `inline size guard`() {
        assertTrue(WebPreview.fitsInline(""))
        assertTrue(WebPreview.fitsInline("x".repeat(WebPreview.MAX_INLINE_CHARS)))
        assertFalse(WebPreview.fitsInline("x".repeat(WebPreview.MAX_INLINE_CHARS + 1)))
    }

    @Test
    fun `full page keeps the author document and pins the theme background`() {
        val html = "<!DOCTYPE html><html><head></head><body>x</body></html>"
        val out = WebPreview.fullPage(html, "html", dark)
        assertTrue(out.contains("<body>x"))
        assertTrue(out.contains("</body></html>"))
        assertTrue(out.contains("background: ${dark.background}"))

        val wrapped = WebPreview.fullPage("<svg></svg>", "svg", dark)
        assertTrue(wrapped.startsWith("<!DOCTYPE html>"))
        assertTrue(wrapped.contains("<svg></svg>"))
        assertTrue(wrapped.contains("background: ${dark.background}"))
    }

    @Test
    fun `inline document stays transparent so the bubble shows through`() {
        assertTrue(WebPreview.document("<p>x</p>", WebPreviewKind.HTML, dark).contains("background: transparent"))
    }

    @Test
    fun `doctype-only document still gets the injected style`() {
        val html = WebPreview.document("<!DOCTYPE html><p>bare</p>", WebPreviewKind.HTML, dark)
        assertTrue(html.contains("--rk-fg:"))
        assertTrue(html.contains("<p>bare</p>"))
    }

    @Test
    fun `tags mentioned inside comments are not structure`() {
        val code = "<!-- <html><head></head> --><p>注释里提到 html</p>"
        val html = WebPreview.document(code, WebPreviewKind.HTML, dark)
        // 片段路径：注入在文档骨架里，不会被送进注释。
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("--rk-fg:"))
        assertTrue(html.contains("<!-- <html><head></head> -->"))
    }

    @Test
    fun `tags inside script strings are not structure`() {
        val code = """<script>var s = "<html><head>";</script><p>x</p>"""
        val html = WebPreview.document(code, WebPreviewKind.HTML, dark)
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("""var s = "<html><head>";"""))
        assertEquals(1, Regex("""--rk-fg:""").findAll(html).count())
    }

    @Test
    fun `extracted sample fragment survives masking`() {
        // 真实形态：模型在脚本里拼 HTML 字符串，并且注释里带标签示例。
        val code = """<div id="app"></div><script>document.getElementById('app').innerHTML = "<!-- <html> --><p>hi</p>";</script>"""
        assertFalse(WebPreview.isFullDocument(code))
        val html = WebPreview.document(code, WebPreviewKind.HTML, dark)
        assertTrue(html.contains("""<div id="app"></div>"""))
        assertTrue(html.contains("--rk-primary:"))
    }

    @Test
    fun `declared charset is normalised to utf-8`() {
        val code =
            """<!DOCTYPE html><html><head><meta charset="gbk"><title>t</title></head><body>中文</body></html>"""
        val html = WebPreview.document(code, WebPreviewKind.HTML, dark)
        assertFalse(html.contains("gbk"))
        assertTrue(html.contains("""<meta charset="utf-8">"""))
    }

    @Test
    fun `height script goes before the body close tag`() {
        val code = "<!DOCTYPE html><html><body><p>a</p></body></html>"
        val html = WebPreview.document(code, WebPreviewKind.HTML, dark)
        val script = html.indexOf("MyAgentPreview.setContentHeight")
        val bodyClose = html.lastIndexOf("</body>")
        assertTrue("script=$script bodyClose=$bodyClose", script >= 0 && script < bodyClose)
    }

    @Test
    fun `body close tag inside a comment does not anchor the script`() {
        val code = "<!DOCTYPE html><html><body><!-- </body> --><p>a</p></body></html>"
        val html = WebPreview.document(code, WebPreviewKind.HTML, dark)
        val script = html.indexOf("MyAgentPreview.setContentHeight")
        val realClose = html.lastIndexOf("</body>")
        assertTrue("script=$script close=$realClose", script >= 0 && script < realClose)
    }

    @Test
    fun `mask keeps offsets aligned with the source`() {
        val code = "<!-- <html> --><p>a</p><script>\"<body>\"</script>"
        val masked = WebPreview.maskNoise(code)
        assertEquals(code.length, masked.length)
        assertFalse(masked.contains("<html"))
        assertFalse(masked.contains("<body"))
        assertTrue(masked.contains("<p>a</p>"))
        assertFalse(WebPreview.maskNoise("<!-- 未闭合注释 <html>").contains("<html"))
    }

    @Test
    fun `viewport inside a comment is left alone`() {
        val code =
            """<!DOCTYPE html><html><head><!-- <meta name="viewport" content="width=1024"> --></head><body>x</body></html>"""
        val html = WebPreview.document(code, WebPreviewKind.HTML, dark)
        assertTrue(html.contains("width=1024"))
        assertTrue(html.contains("width=device-width"))
    }

    @Test
    fun `svg full page still pins the theme background`() {
        val out = WebPreview.fullPage("<svg></svg>", "svg", dark)
        assertTrue(out.contains("rk-svg-root"))
        assertTrue(out.contains("background: ${dark.background}"))
    }

    @Test
    fun `author csp is removed so the injected script can run`() {
        val code =
            """<!DOCTYPE html><html><head><meta http-equiv="Content-Security-Policy" content="default-src 'none'"></head><body>x</body></html>"""
        val html = WebPreview.document(code, WebPreviewKind.HTML, dark)
        assertFalse(html.contains("Content-Security-Policy", ignoreCase = true))
        assertTrue(html.contains("MyAgentPreview.setContentHeight"))
    }

    @Test
    fun `csp mentioned inside a comment is left alone`() {
        val code =
            """<p>x</p><!-- <meta http-equiv="Content-Security-Policy" content="default-src 'none'"> -->"""
        val html = WebPreview.document(code, WebPreviewKind.HTML, dark)
        assertTrue(html.contains("default-src 'none'"))
    }
}
