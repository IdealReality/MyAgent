package me.rerere.myagent.data.ai.prompts

/**
 * 「互动图 / 网页」渲染能力的系统提示段。
 *
 * 由 [me.rerere.myagent.data.ai.SystemPromptBuilder] 放进 stable 段（同一设置下逐字节
 * 稳定，不破坏 prompt 缓存）。只在用户开启「互动网页预览」时注入：否则模型辛苦写出的
 * HTML 会被当成普通代码块显示，反而是噪声。
 *
 * ★ 提示里承诺的 CSS 变量必须和 [me.rerere.myagent.ui.components.richtext.WebPreview]
 * 的注入保持一致（`--rk-fg` / `--rk-muted` / `--rk-surface` / `--rk-border` /
 * `--rk-primary`）；改一处就要一起改。
 */
object InteractivePreviewPrompt {

    fun render(): String = """
        [Inline rich preview]
        In this app a fenced code block tagged html (or interactive, or svg) is NOT displayed as
        source code: it is rendered as a live, tappable web page directly inside your reply, at
        mobile width, with the height fitted to its content. Use it when an interactive visual
        says more than prose — charts, diagrams with clickable parts, sliders, small simulators,
        mini tools, one-page explainers.
        - Make it self-contained and offline-capable: inline SVG / CSS / canvas / vanilla JS.
          Do not rely on CDN libraries such as Chart.js or D3 — the device may be offline.
        - Keep the page background transparent (do not set an opaque background on html/body) and
          colour it with the theme variables the app injects, so it follows light and dark theme:
          var(--rk-fg) text, var(--rk-muted) secondary text, var(--rk-surface) card fill,
          var(--rk-border) lines, var(--rk-primary) accent.
        - The viewport is already mobile width; size with percentages/flex, not fixed widths.
        - Do not give html/body a fixed or 100% height, and do not put the whole page in a
          fixed-size box: the reply grows and shrinks with the page's real content height.
        - Keep it compact: the reply shows it inline, and the user can open it full screen.
        - Do not wrap ordinary explanations or the final answer in html; use it only when the
          interactive visual itself is the answer or a genuinely useful addition.
    """.trimIndent()
}
