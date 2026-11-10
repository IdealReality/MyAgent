package me.rerere.myagent.ui.theme

import androidx.compose.ui.graphics.Color
import me.rerere.highlight.HighlightTextColorPalette

// Atom One Dark Theme Colors
val AtomOneDarkPalette = HighlightTextColorPalette(
    keyword = Color(0xFFc678dd),    // 紫色
    string = Color(0xFF98c379),     // 绿色
    number = Color(0xFFd19a66),     // 橙色
    comment = Color(0xFF5c6370),    // 灰色
    function = Color(0xFF61afef),   // 蓝色
    operator = Color(0xFF56b6c2),   // 青色
    punctuation = Color(0xFFabb2bf), // 浅灰色
    className = Color(0xFFe5c07b),  // 黄色
    property = Color(0xFFe06c75),   // 红色
    boolean = Color(0xFFd19a66),    // 橙色
    variable = Color(0xFFe06c75),   // 红色
    tag = Color(0xFFe06c75),        // 红色
    attrName = Color(0xFFd19a66),   // 橙色
    attrValue = Color(0xFF98c379),  // 绿色
    fallback = Color(0xFFabb2bf)    // 浅灰色
)

// Atom One Light Theme Colors
val AtomOneLightPalette = HighlightTextColorPalette(
    keyword = Color(0xFFa626a4),    // 紫色
    string = Color(0xFF50a14f),     // 绿色
    number = Color(0xFFc18401),     // 橙色
    comment = Color(0xFF9ca0a4),    // 灰色
    function = Color(0xFF4078f2),   // 蓝色
    operator = Color(0xFF0184bc),   // 青色
    punctuation = Color(0xFF383a42), // 深灰色
    className = Color(0xFFc18401),  // 黄色
    property = Color(0xFFe45649),   // 红色
    boolean = Color(0xFFc18401),    // 橙色
    variable = Color(0xFFe45649),   // 红色
    tag = Color(0xFFe45649),        // 红色
    attrName = Color(0xFFc18401),   // 橙色
    attrValue = Color(0xFF50a14f),  // 绿色
    fallback = Color(0xFF383a42)    // 深灰色
)

/*
 * mono 主题的代码高亮：保留语法色，并且**亮色也敢上色**。
 *
 * 曾经的做法是把高亮压成一条灰阶明度轴——可读，但每种 token 的区分度全靠深浅，
 * 长代码块里关键字和变量会糊在一起。后来改成低饱和五色，可读性上来了、色相却几乎看不出来
 * （keyword 只有 0.10 的彩度）。这里把亮色档提到各自色相的 80%…95% 色域：
 * 紫/绿/橙/蓝/青/琥珀/红七种记号，一眼能分清类型，仍不碰正文与标点。
 *  - 正文（punctuation/fallback）与注释保持中性，颜色只出现在真正的语法记号上；
 *  - 每个 token 与代码块底色（surfaceContainer #F6F6F6）的对比度 ≥ 4.6:1，深浅两种主题都成立；
 *  - 暗色档不动：深底上高饱和的信号会发光刺眼，亮度已经够区分
 *    （见 [MonoDarkPalette]，每 token 对 #171717 ≥ 7.3:1）。
 */
val MonoLightPalette = HighlightTextColorPalette(
    keyword = Color(0xFF7F20D9),          // 紫
    string = Color(0xFF0F783E),           // 绿
    number = Color(0xFF915C06),           // 橙
    comment = Color(0xFF6E6E6E),          // 灰
    function = Color(0xFF0F63BC),         // 蓝
    operator = Color(0xFF127277),         // 青
    punctuation = Color(0xFF484848),      // 中性深灰
    className = Color(0xFF816006),        // 琥珀
    property = Color(0xFFBF1624),         // 红
    boolean = Color(0xFF915C06),          // 橙（同 number）
    variable = Color(0xFFBF1624),         // 红
    tag = Color(0xFFBF1624),              // 红
    attrName = Color(0xFF915C06),         // 橙
    attrValue = Color(0xFF0F783E),        // 绿（同 string）
    fallback = Color(0xFF353535),
)

val MonoDarkPalette = HighlightTextColorPalette(
    keyword = Color(0xFFCDB3FD),
    string = Color(0xFF94CF9F),
    number = Color(0xFFEBAE78),
    comment = Color(0xFF8C8C8C),
    function = Color(0xFF99C6FF),
    operator = Color(0xFF83C8D0),
    punctuation = Color(0xFFBEBEBE),
    className = Color(0xFFE8BE7E),
    property = Color(0xFFF99E9B),
    boolean = Color(0xFFEBAE78),
    variable = Color(0xFFF19E9B),
    tag = Color(0xFFFB9F9C),
    attrName = Color(0xFFEEB380),
    attrValue = Color(0xFF94CF9F),
    fallback = Color(0xFFCCCCCC),
)
