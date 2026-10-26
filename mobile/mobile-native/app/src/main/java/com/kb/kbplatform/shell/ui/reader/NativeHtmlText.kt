/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 文档正文的**原生渲染**（2026-10-26）。
 *
 * 背景：阅读页原来是「Compose 外壳 + WebView 塞 HTML」，因为正文是富文本。
 * 但绝大多数文档其实只有段落/标题/列表/粗体/链接——这部分用 `AnnotatedString.fromHtml`
 * 就能原生渲染，滚动、选中、深浅色、字体缩放全部跟着系统走，也没有 WebView 的白屏和闪烁。
 *
 * 只有含 `img` / `table` / `video` 的文档仍交给 WebView（原生文本渲染画不出这些）。
 * 判定见 [needsRichWebView]。
 */
@Composable
fun NativeHtmlText(html: String, modifier: Modifier = Modifier) {
    val styled = remember(html) {
        // fromHtml 支持 b/strong/i/em/u/h1-h6/p/br/ul/ol/li/a/font 等基础标签
        AnnotatedString.fromHtml(html)
    }
    Text(
        text = styled,
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 40.dp),
        fontSize = 16.sp,
        // 行高 1.75 对齐套壳 .m-prose
        lineHeight = 28.sp,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * 正文里是否有原生文本渲染画不出来的元素。
 * 有则退回 WebView（图片、表格、视频、内嵌 iframe）。
 */
fun needsRichWebView(html: String): Boolean {
    val h = html.lowercase()
    return h.contains("<img") || h.contains("<table") || h.contains("<video") || h.contains("<iframe")
}
