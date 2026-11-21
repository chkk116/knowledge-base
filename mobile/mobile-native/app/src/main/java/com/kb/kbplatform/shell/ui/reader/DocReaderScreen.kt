/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.reader

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kb.kbplatform.shell.AppConfig
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.Bookmarks
import com.kb.kbplatform.shell.data.HistoryStore
import com.kb.kbplatform.shell.data.OfflineCache
import com.kb.kbplatform.shell.ui.common.CenterHint
import com.kb.kbplatform.shell.ui.common.KbChip
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.formatDateTime
import kotlinx.coroutines.launch
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
    var updatedAt by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    // 外部分享链接（生成/更新/撤销，免登录只读 /s/{token}）
    var showShare by remember { mutableStateOf(false) }
    // 文档级权限（单篇覆盖空间权限）
    var showPerm by remember { mutableStateOf(false) }

    LaunchedEffect(shareId, docId, refreshKey) {
        if (refreshKey > 0) refreshing = true else loading = true
        error = ""
        try {
            val d: JSONObject = KbApi.getShareDoc(shareId, docId)
            val t = d.optString("title").ifBlank { titleHint }
            val c = d.optString("content")
            title = t
            html = c
            updatedAt = d.optString("updatedAt")
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
            refreshing = false
        }
    }

    val bookmarked = remember(title) { Bookmarks.isBookmarked(ctx, shareId, docId) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = title, onBack = onBack, actions = {
            TextButton(onClick = {
                Bookmarks.toggle(ctx, shareId, docId, title)
            }) { Text(if (bookmarked) "★ 已藏" else "☆ 收藏", fontSize = 13.sp) }
            IconButton(onClick = { showShare = true }) {
                Icon(Icons.Filled.Share, contentDescription = "分享", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { showPerm = true }) {
                Icon(Icons.Filled.Lock, contentDescription = "权限", modifier = Modifier.size(18.dp))
            }
            TextButton(
                onClick = { if (!refreshing) refreshKey++ },
                enabled = !refreshing
            ) { Text(if (refreshing) "刷新中…" else "刷新", fontSize = 13.sp) }
            TextButton(onClick = {
                onEdit()
            }) { Text("编辑", fontWeight = FontWeight.Medium, fontSize = 14.sp) }
        })

        // 最后更新时间（离线时给出缓存提示，连网后点顶部「刷新」即可重新缓存）
        val stamp = formatDateTime(updatedAt)
        if (stamp.isNotBlank() && !offline) {
            Text(
                "更新于 $stamp",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

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
                // 纯文本类正文（段落/标题/列表/粗体/链接）**原生渲染**，滚动/选中/深色/字体缩放都跟系统走；
                // 只有含图片/表格/视频/iframe 的文档才交给 WebView（原生文本渲染画不出这些）
                val pieces = androidx.compose.runtime.remember(html) {
                    com.kb.kbplatform.shell.ui.diagram.DiagramParser.split(html!!)
                }
                val hasDiagram = pieces.any { it is com.kb.kbplatform.shell.ui.diagram.DocPiece.Diagram }
                when {
                    // 有流程图/思维导图：原生混排渲染（文本段用 fromHtml，图块用 NativeDiagram 画）
                    hasDiagram -> NativeDocContent(pieces)
                    needsRichWebView(html!!) -> ReaderWebView(html!!)
                    else -> NativeHtmlText(html!!)
                }
            }
        }
    }

    if (showShare) {
        ShareLinkDialog(
            shareId = shareId,
            docId = docId,
            docTitle = title,
            onClose = { showShare = false }
        )
    }

    if (showPerm) {
        DocPermDialog(
            shareId = shareId,
            docId = docId,
            docTitle = title,
            onClose = { showPerm = false }
        )
    }
}

/**
 * 原生混排正文：普通段落走 AnnotatedString.fromHtml，图块走 NativeDiagram（Compose 绘制）。
 * 这样流程图/思维导图在原生壳里是「真·原生渲染」，不再依赖 WebView。
 */
@Composable
private fun NativeDocContent(pieces: List<com.kb.kbplatform.shell.ui.diagram.DocPiece>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        pieces.forEach { piece ->
            when (piece) {
                is com.kb.kbplatform.shell.ui.diagram.DocPiece.Html -> Text(
                    text = AnnotatedString.fromHtml(piece.html),
                    fontSize = 16.sp,
                    lineHeight = 28.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                is com.kb.kbplatform.shell.ui.diagram.DocPiece.Diagram -> {
                    Text(
                        text = piece.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    com.kb.kbplatform.shell.ui.diagram.NativeDiagram(piece.data)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReaderWebView(contentHtml: String) {
    // 正文是后端产出的 HTML 片段；外链资源（图片/上传文件）用后端绝对地址解析
    val base = AppConfig.effectiveBaseUrl
    // 图块（流程图/思维导图/表格）的数据在 data 属性里，靠 Vue Flow 在网页端绘制；
    // 这里是关闭 JS 的静态模板，原样塞进去只会是一个空 div（用户看到「空白」）。
    // 统一替换成可读的占位块，至少让人知道这里有什么、去哪里看。
    val safeHtml = contentHtml
        .replace(Regex("<div[^>]*data-type=\"[^\"]*\"[^>]*>.*?</div>", RegexOption.DOT_MATCHES_ALL)) { m ->
            val kind = Regex("data-type=\"([^\"]*)\"").find(m.value)?.groupValues?.get(1).orEmpty()
            val label = when {
                kind.contains("flowchart") -> "流程图"
                kind.contains("mindmap") -> "思维导图"
                kind.contains("sheet") -> "表格"
                else -> "图形块"
            }
            "<div style=\"margin:10px 0;padding:14px 16px;border:1px dashed #c7d2fe;border-radius:10px;" +
                "background:#f5f7ff;color:#4338ca;font-size:14px;line-height:1.7\">" +
                "📊 <b>$label</b><br><span style=\"color:#6b7280;font-size:13px\">" +
                "该图形需要在 App 编辑页或网页端查看（静态阅读页不加载绘图组件）</span></div>"
        }
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
        <body>$safeHtml</body></html>
    """.trimIndent()

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> WebView(ctx).apply { settings.javaScriptEnabled = false } }
    ) { view ->
        view.loadDataWithBaseURL(base + "/", doc, "text/html", "utf-8", null)
    }
}

/**
 * 外部分享链接（文档级，能管理该文档的人才能生成/撤销）。
 *
 * 后端：`GET|POST|DELETE /doc-shares/{sid}/docs/{docId}/public-link`，POST body `{password?, expireDays?, noCopy}`；
 * 返回 `enabled/token/hasPassword/expireAt/noCopy/views`。访问地址是 Web 端 `/s/{token}`（免登录只读，
 * 有密码要输密码），撤销后历史链接立即 404。有效期 0 = 永久。
 */
@Composable
private fun ShareLinkDialog(shareId: Long, docId: Long, docTitle: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = androidx.compose.runtime.rememberCoroutineScope()
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var pwd by remember { mutableStateOf("") }
    var days by remember { mutableIntStateOf(0) }
    var noCopy by remember { mutableStateOf(false) }

    LaunchedEffect(tick) {
        loading = true
        err = ""
        try {
            val v = KbApi.shareLinkInfo(shareId, docId)
            info = v
            noCopy = v.optBoolean("noCopy", false)
        } catch (e: Exception) {
            err = e.message ?: "分享状态加载失败"
        } finally {
            loading = false
        }
    }

    val tok = info?.optString("token").orEmpty()
    val enabled = info?.optBoolean("enabled", false) == true && tok.isNotBlank()
    val url = if (enabled) AppConfig.effectiveBaseUrl.trimEnd('/') + "/s/" + tok else ""

    fun copyLink() {
        runCatching {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("share-link", url))
            note = "链接已复制"
        }.onFailure { note = "复制失败：${it.message}" }
    }

    fun openLink() {
        runCatching {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure { note = "打开失败：${it.message}" }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("外部分享链接", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "「" + docTitle.ifBlank { "当前文档" } + "」· 拿到链接的人无需登录即可只读打开",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (note.isNotEmpty()) {
                    Text(
                        note,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                when {
                    loading -> CenterHint("加载中…", loading = true)
                    err.isNotEmpty() -> CenterHint(err)
                    else -> {
                        if (url.isNotBlank()) {
                            Text(
                                "访问地址",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Text(url, fontSize = 11.5.sp, maxLines = 2)
                            Row(Modifier.padding(top = 2.dp)) {
                                TextButton(onClick = { copyLink() }) { Text("复制", fontSize = 12.sp) }
                                TextButton(onClick = { openLink() }) { Text("打开", fontSize = 12.sp) }
                            }
                            Text(
                                buildString {
                                    append(if (info?.optBoolean("hasPassword", false) == true) "需要密码" else "无密码")
                                    append(" · ")
                                    append(
                                        if (info?.optString("expireAt").orEmpty().isNotBlank())
                                            "有效期至 " + formatDateTime(info?.optString("expireAt"))
                                        else "永久有效"
                                    )
                                    if (info?.optBoolean("noCopy", false) == true) append(" · 禁止复制")
                                    append(" · 已浏览 " + (info?.optLong("views", 0) ?: 0) + " 次")
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedTextField(
                            value = pwd,
                            onValueChange = { pwd = it },
                            label = { Text("访问密码（留空 = 无密码）", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        )
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("有效期：", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            for (d in listOf(0, 7, 30, 90)) {
                                KbChip(if (d == 0) "永久" else d.toString() + " 天", selected = days == d, onClick = { days = d })
                                Spacer(Modifier.size(4.dp))
                            }
                        }
                        Row(Modifier.padding(top = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("复制限制：", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            KbChip("允许", selected = !noCopy, onClick = { noCopy = false })
                            Spacer(Modifier.size(4.dp))
                            KbChip("禁止复制", selected = noCopy, onClick = { noCopy = true })
                        }
                        Row(Modifier.padding(top = 6.dp)) {
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    cs.launch {
                                        try {
                                            info = KbApi.saveShareLink(shareId, docId, pwd.ifBlank { null }, days, noCopy)
                                            note = if (url.isBlank()) "已开启外部分享" else "已更新设置（链接不变）"
                                            pwd = ""
                                            tick++
                                        } catch (e: Exception) {
                                            note = e.message ?: "生成失败"
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            ) { Text(if (busy) "提交中…" else if (url.isBlank()) "生成链接" else "更新设置", fontSize = 12.sp) }
                            if (url.isNotBlank()) {
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        busy = true
                                        cs.launch {
                                            try {
                                                KbApi.revokeShareLink(shareId, docId)
                                                info = null
                                                note = "已撤销，历史链接立即失效"
                                                tick++
                                            } catch (e: Exception) {
                                                note = e.message ?: "撤销失败"
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }
                                ) {
                                    Text("撤销链接", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("关闭") }
        }
    )
}
