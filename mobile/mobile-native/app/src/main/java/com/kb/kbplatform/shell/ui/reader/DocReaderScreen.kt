/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.reader

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kb.kbplatform.shell.AppConfig
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.Bookmarks
import com.kb.kbplatform.shell.data.HistoryStore
import com.kb.kbplatform.shell.data.OfflineCache
import com.kb.kbplatform.shell.ui.common.CenterHint
import com.kb.kbplatform.shell.ui.common.TopBar
import org.json.JSONObject

/**
 * 原生文档阅读器（正文级离线核心）：
 * 在线打开 → GET 文档 JSON → 正文缓存到本地（上限 100 篇，超量丢最旧）→ WebView 渲染；
 * 断网打开 → 直接读缓存，秒开可读。图片等外链资源离线时不可见（正文文字完整）。
 * 「编辑」进入 WebView 协同编辑页（Tiptap 能力不受影响）。
 */
@Composable
fun DocReaderScreen(
    shareId: Long,
    docId: Long,
    titleHint: String,
    onBack: () -> Unit,
    onEdit: () -> Unit = {}
) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(titleHint) }
    var html by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(shareId, docId) {
        loading = true
        error = ""
        try {
            val d: JSONObject = KbApi.getShareDoc(shareId, docId)
            val t = d.optString("title").ifBlank { titleHint }
            val c = d.optString("content")
            title = t
            html = c
            offline = false
            OfflineCache.saveDocContent(ctx, shareId, docId, t, c)
            HistoryStore.record(ctx, shareId, docId, t)   // 浏览历史（对标套壳 history store）
        } catch (e: Exception) {
            // 网络失败 → 落离线缓存
            val cached = OfflineCache.loadDocContent(ctx, shareId, docId)
            if (cached != null) {
                title = cached.title.ifBlank { titleHint }
                html = cached.content
                offline = true
                error = ""
            } else {
                error = e.message ?: "加载失败，且无离线缓存"
            }
        } finally {
            loading = false
        }
    }

    val bookmarked = remember(title) { Bookmarks.isBookmarked(ctx, shareId, docId) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = title, onBack = onBack, actions = {
            TextButton(onClick = {
                Bookmarks.toggle(ctx, shareId, docId, title)
            }) { Text(if (bookmarked) "★ 已藏" else "☆ 收藏", fontSize = 13.sp) }
            TextButton(onClick = {
                onEdit()
            }) { Text("编辑", fontWeight = FontWeight.Medium, fontSize = 14.sp) }
        })

        if (offline) {
            Text(
                "📴 离线阅读（连网后打开可刷新并重新缓存）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        when {
            loading -> CenterHint("加载中…", loading = true)
            error.isNotEmpty() -> CenterHint(error)
            html != null -> Box(Modifier.fillMaxSize()) {
                ReaderWebView(html!!)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReaderWebView(contentHtml: String) {
    // 正文是后端产出的 HTML 片段；外链资源（图片/上传文件）用后端绝对地址解析
    val base = AppConfig.effectiveBaseUrl
    val doc = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body { font-family: sans-serif; font-size: 16px; line-height: 1.75;
                 color: #1f2937; padding: 14px 16px 40px; margin: 0; }
          img, video { max-width: 100%; height: auto; border-radius: 6px; }
          table { border-collapse: collapse; max-width: 100%; }
          td, th { border: 1px solid #d1d5db; padding: 4px 8px; }
          a { color: #409eff; }
          blockquote { margin: 8px 0; padding: 4px 12px; border-left: 3px solid #c7d2fe; color: #4b5563; }
          pre { background: #f3f4f6; padding: 10px; border-radius: 8px; overflow-x: auto; }
        </style></head>
        <body>$contentHtml</body></html>
    """.trimIndent()

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> WebView(ctx).apply { settings.javaScriptEnabled = false } }
    ) { view ->
        view.loadDataWithBaseURL(base + "/", doc, "text/html", "utf-8", null)
    }
}
