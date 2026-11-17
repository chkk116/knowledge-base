package com.kb.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Desktop
import java.net.URI

// 共享文档空间（2026-11-16 新增）：左＝我的空间，中＝空间内文档，右＝只读阅读
//   ⚠️ 正文取的是后端 content 字段（HTML 快照）；协同实时正文在 kb_shared_document.yjs_state，
//   REST 不下发（@JsonIgnore），桌面端只读快照属于既定口径，编辑仍在 Web 端。

private val ShareRailBg = Color(0xFFFAFBFC)
private val ShareErrRed = Color(0xFFDC2626)

@Composable
fun SharesPanel(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    var spaces by remember { mutableStateOf<List<AgentSpace>>(emptyList()) }
    var spaceId by remember { mutableStateOf<Long?>(null) }
    var docs by remember { mutableStateOf<List<SharedDocItem>>(emptyList()) }
    var docId by remember { mutableStateOf<Long?>(null) }
    var detail by remember { mutableStateOf<SharedDocItem?>(null) }
    // 协同正文（本地解析 yjs_state）：拿到就用，拿不到/解析失败回退 content 快照
    var yjsHtml by remember { mutableStateOf<String?>(null) }
    var yjsLive by remember { mutableStateOf(false) }
    // 拿不到协同正文时的原因（404 未部署 / empty 无状态 / parse 解析失败 / tooLarge 超限），
    // 只用于空正文时把「不可解析」换成能照着做的提示
    var yjsNote by remember { mutableStateOf("") }
    // 历史版本：既用于手动回滚，也作为「拿不到 /yjs 又没快照」时的兜底正文来源
    var versions by remember { mutableStateOf<List<Api.ShareVersionItem>>(emptyList()) }
    var versionsLoading by remember { mutableStateOf(false) }
    var versionsErr by remember { mutableStateOf<String?>(null) }
    var showVersions by remember { mutableStateOf(false) }
    var versionNote by remember { mutableStateOf("") }

    var spacesLoading by remember { mutableStateOf(true) }
    var docsLoading by remember { mutableStateOf(false) }
    var detailLoading by remember { mutableStateOf(false) }
    var spacesErr by remember { mutableStateOf<String?>(null) }
    var docsErr by remember { mutableStateOf<String?>(null) }
    var detailErr by remember { mutableStateOf<String?>(null) }
    var spacesTick by remember { mutableStateOf(0) }
    var docsTick by remember { mutableStateOf(0) }
    var detailTick by remember { mutableStateOf(0) }
    // 空间内检索与排序（纯本地：标题/标签关键字 + 更新时间/标题）
    var q by remember { mutableStateOf("") }
    var order by remember { mutableStateOf("time") }

    LaunchedEffect(spacesTick) {
        spacesLoading = true
        spacesErr = null
        try {
            spaces = api.myShares(session.token)
            // 首屏默认落到第一个空间；空间被删/换账号时回落到第一个
            if (spaceId == null || spaces.none { it.id == spaceId }) spaceId = spaces.firstOrNull()?.id
        } catch (e: Exception) {
            spacesErr = e.message ?: "加载失败"
        } finally {
            spacesLoading = false
        }
    }

    LaunchedEffect(spaceId, docsTick) {
        val sid = spaceId ?: return@LaunchedEffect
        docsLoading = true
        docsErr = null
        docs = emptyList()
        docId = null
        detail = null
        try {
            docs = api.shareDocs(session.token, sid)
            docId = docs.firstOrNull()?.id
        } catch (e: Exception) {
            docsErr = e.message ?: "加载失败"
        } finally {
            docsLoading = false
        }
    }

    LaunchedEffect(spaceId, docId, detailTick) {
        val sid = spaceId ?: return@LaunchedEffect
        val did = docId ?: return@LaunchedEffect
        detailLoading = true
        detailErr = null
        detail = null
        try {
            detail = api.shareDocDetail(session.token, sid, did)
        } catch (e: Exception) {
            detailErr = e.message ?: "加载失败"
        } finally {
            detailLoading = false
        }
        // 协同正文：relay 每 5s 落库一次，读到的就是准实时内容；解析失败静默回退快照
        yjsHtml = null
        yjsLive = false
        yjsNote = ""
        try {
            val y = api.shareDocYjs(session.token, sid, did)
            val b64 = y?.state
            if (b64.isNullOrBlank()) {
                yjsNote = if (y?.tooLarge == true) "tooLarge" else "empty"
            } else {
                val bytes = java.util.Base64.getDecoder().decode(b64)
                val html = YjsHtml.toHtml(bytes)
                if (!html.isNullOrBlank()) {
                    yjsHtml = html
                    yjsLive = true
                } else {
                    yjsNote = "parse"
                }
            }
        } catch (e: Exception) {
            // 回退快照，不打断阅读；记下原因，供空正文时给出准确提示
            yjsNote = e.message ?: "error"
        }
    }

    val space = spaces.firstOrNull { it.id == spaceId }

    val shownDocs = remember(docs, q, order) {
        val kw = q.trim()
        val filtered = if (kw.isEmpty()) docs else docs.filter {
            it.title.contains(kw, ignoreCase = true) || (it.tags?.contains(kw, ignoreCase = true) == true)
        }
        if (order == "title") filtered.sortedBy { it.title.lowercase() }
        else filtered.sortedByDescending { it.updatedAt }
    }

    Row(Modifier.fillMaxSize()) {
        // ---- 左：我的协作空间 ----
        Column(Modifier.width(150.dp).fillMaxHeight().background(ShareRailBg)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("协作空间", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { spacesTick++ }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("刷新", fontSize = 11.sp)
                }
            }
            HorizontalDivider(color = KbBorder)
            Box(Modifier.weight(1f)) {
                when {
                    spacesLoading -> ShareHint("加载中…")
                    spacesErr != null -> ShareError(spacesErr ?: "", onRetry = { spacesTick++ })
                    spaces.isEmpty() -> ShareHint("还没有协作空间\n可在 Web 端创建或接受邀请")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(spaces, key = { it.id }) { s ->
                            SpaceRow(s, sel = s.id == spaceId) { spaceId = s.id }
                        }
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(KbBorder))

        // ---- 中：空间内文档 ----
        Column(Modifier.width(204.dp).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    space?.name?.ifBlank { "文档" } ?: "文档",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = KbTextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (shownDocs.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text("${shownDocs.size} 篇", fontSize = 10.5.sp, color = KbTextSub)
                }
                Spacer(Modifier.weight(1f))
                if (spaceId != null) {
                    TextButton(onClick = { docsTick++ }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("刷新", fontSize = 11.sp)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchBox(q, { q = it }, Modifier.weight(1f))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SortChip("最近更新", order == "time") { order = "time" }
                Spacer(Modifier.width(6.dp))
                SortChip("标题", order == "title") { order = "title" }
            }
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = KbBorder)
            Box(Modifier.weight(1f)) {
                when {
                    spaceId == null -> ShareHint("先在左侧选择一个空间")
                    docsLoading -> ShareHint("加载中…")
                    docsErr != null -> ShareError(docsErr ?: "", onRetry = { docsTick++ })
                    docs.isEmpty() -> ShareHint("这个空间还没有文档\n可在 Web 端新建或导入")
                    shownDocs.isEmpty() -> ShareHint("没有匹配的文档")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(shownDocs, key = { it.id }) { d ->
                            ShareDocRow(d, sel = d.id == docId) { docId = d.id }
                        }
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(KbBorder))

        // ---- 右：只读阅读 ----
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val d = detail
            when {
                spaceId == null -> ShareHint("选择空间与文档后在这里阅读")
                docId == null -> ShareHint("在中间列表选择一篇文档")
                detailLoading -> ShareHint("加载中…")
                detailErr != null -> ShareError(detailErr ?: "", onRetry = { detailTick++ })
                d == null -> ShareHint("加载中…")
                else -> ShareReader(
                    doc = d,
                    spaceName = space?.name.orEmpty(),
                    server = session.server,
                    html = yjsHtml ?: d.content.orEmpty(),
                    live = yjsLive,
                    yjsNote = yjsNote,
                    onOpenInBrowser = {
                        openShareInBrowser(session.server, "/doc-shares/${spaceId}?doc=${d.id}")
                    },
                )
            }
        }
    }
}

@Composable
private fun SpaceRow(s: AgentSpace, sel: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (sel) Color(0xFFE8EEFB) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(KbIconGradient(KbTileTeal.first, KbTileTeal.second)),
            contentAlignment = Alignment.Center,
        ) {
            MenuGlyph(MenuKind.SHARES, Color.White, Modifier.size(13.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.name.ifBlank { "未命名空间" },
                fontSize = 12.5.sp,
                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                color = KbTextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (s.type.isNotBlank()) {
                Text(spaceTypeLabel(s.type), fontSize = 10.sp, color = KbTextSub)
            }
        }
    }
}

@Composable
private fun ShareDocRow(d: SharedDocItem, sel: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (sel) KbSidebarSelected else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            d.title.ifBlank { "（无标题）" },
            fontSize = 12.5.sp,
            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
            color = KbTextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            listOfNotNull(
                d.ownerName.takeIf { it.isNotBlank() },
                fmtSharedTime(d.updatedAt).takeIf { it.isNotBlank() },
                fmtShareSize(d.size).takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            fontSize = 10.5.sp,
            color = KbTextSub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ShareReader(
    doc: SharedDocItem,
    spaceName: String,
    server: String,
    html: String,
    live: Boolean,
    yjsNote: String = "",
    onOpenInBrowser: () -> Unit,
) {
    val blocks = remember(doc.id, html) { DocHtml.parse(html) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 24.dp, top = 16.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        doc.title.ifBlank { "（无标题）" },
                        fontSize = 22.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = KbTextMain,
                        modifier = Modifier.weight(1f),
                    )
                    val perm = doc.myPerm
                    if (!perm.isNullOrBlank()) {
                        Text(permLabel(perm), fontSize = 10.5.sp, color = Color(0xFF94A3B8))
                        Spacer(Modifier.width(6.dp))
                    }
                    TextButton(onClick = onOpenInBrowser, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("在 Web 端打开", fontSize = 11.5.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                // 元信息分两行：标签单独一行（数量不定），作者/时间单独一行（可省略）
                Column(Modifier.fillMaxWidth()) {
                    if (spaceName.isNotBlank() || splitSharedTags(doc.tags).isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (spaceName.isNotBlank()) {
                                ShareChip(spaceName, Color(0xFFE0F2FE), Color(0xFF0369A1))
                            }
                            // 正文来源：协同实时（relay 5s 内落库）/ 保存快照
                            if (live) ShareChip("协同实时", Color(0xFFD1FAE5), Color(0xFF047857))
                            else ShareChip("快照", Color(0xFFF3F4F6), Color(0xFF6B7280))
                            for (t in splitSharedTags(doc.tags).take(3)) {
                                ShareChip(t, Color(0xFFF3F4F6), Color(0xFF4B5563))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        listOfNotNull(
                            doc.ownerName.takeIf { it.isNotBlank() },
                            "更新于 ${fmtSharedTime(doc.updatedAt)}",
                            fmtShareSize(doc.size).takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        fontSize = 12.sp,
                        color = KbTextSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = KbBorder)
                Spacer(Modifier.height(14.dp))
            }
        }
        if (blocks.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(
                        // 空正文不是「文档坏了」：协同文档的正文只存在 yjs_state 里，
                        // 桌面端要么靠 /yjs 接口本地解析，要么只能读 content 快照
                        when {
                            yjsNote.contains("404") ->
                                "这篇文档的正文保存在协同状态里，而服务端还没有提供读取接口" +
                                    "（需要部署后端 1.1.3+）。桌面端暂时读不到正文。"
                            yjsNote == "tooLarge" ->
                                "这篇文档的协同状态超过 8MB，服务端不下发。请用 Web 端打开阅读。"
                            yjsNote == "parse" ->
                                "协同状态解析失败（内容可能包含桌面端暂不支持的节点）。请用 Web 端打开。"
                            yjsNote == "empty" ->
                                "协同状态为空，保存的快照也是空的。请用 Web 端打开确认内容。"
                            doc.content.isNullOrBlank() ->
                                "这篇文档只在协同编辑器里写过，还没有保存过 HTML 快照，桌面端没有正文可显示。"
                            else ->
                                "正文里只有桌面端画不出来的块（图块 / 内嵌视频等），" +
                                    "已在上方逐块标注；完整内容请用 Web 端打开。"
                        },
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        color = KbTextSub,
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onOpenInBrowser, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("在浏览器中打开这篇文档 →", fontSize = 13.sp)
                    }
                }
            }
        } else {
            items(blocks.size) { i ->
                DocBlockView(blocks[i], server = server, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ShareChip(text: String, bg: Color, fg: Color) {
    Text(
        text,
        fontSize = 11.sp,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 140.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun ShareHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = KbTextSub, textAlign = TextAlign.Center, lineHeight = 19.sp)
    }
}

@Composable
private fun ShareError(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, fontSize = 12.sp, color = ShareErrRed, textAlign = TextAlign.Center, maxLines = 4)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRetry) { Text("重试", fontSize = 12.sp) }
    }
}

private fun spaceTypeLabel(type: String): String = when (type.uppercase()) {
    "PERSONAL" -> "个人空间"
    "TEAM" -> "团队空间"
    "PROJECT" -> "项目空间"
    "PUBLIC" -> "公开空间"
    else -> type.lowercase()
}

private fun permLabel(perm: String): String = when (perm.uppercase()) {
    "OWNER" -> "所有者"
    "EDIT" -> "可编辑"
    "VIEW" -> "只读"
    else -> perm.lowercase()
}

private fun splitSharedTags(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(Regex("[,，、;；\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.take(4)
}

/** 字节数 → 人类可读（列表里显示文档占用） */
private fun fmtShareSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}

/** "2026-11-13T12:34:56" → "2026-11-13 12:34"（缺省显示 —） */
private fun fmtSharedTime(raw: String?): String {
    if (raw.isNullOrBlank()) return "—"
    return raw.replace('T', ' ').take(16)
}

private fun openShareInBrowser(server: String, path: String) {
    val base = server.trimEnd('/')
    val url = if (path.startsWith("http")) path else "$base/${path.trimStart('/')}"
    runCatching { Desktop.getDesktop().browse(URI.create(url)) }
}