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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

// 共享文档空间（2026-11-16 新增）：左＝我的空间，中＝空间内文档，右＝只读阅读
//   ⚠️ 正文取的是后端 content 字段（HTML 快照）；协同实时正文在 kb_shared_document.yjs_state，
//   REST 不下发（@JsonIgnore），桌面端只读快照属于既定口径，编辑仍在 Web 端。

private val ShareRailBg = Color(0xFFFAFBFC)
private val ShareErrRed = Color(0xFFDC2626)

/** 左侧「全部文档」虚拟空间：spaceId 用这个值表示跨空间视图（真实空间 id 都是正数） */
private const val ALL_SPACES = -1L

@Composable
fun SharesPanel(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var spaces by remember { mutableStateOf<List<AgentSpace>>(emptyList()) }
    var spaceId by remember { mutableStateOf<Long?>(null) }
    var docs by remember { mutableStateOf<List<SharedDocItem>>(emptyList()) }
    var docId by remember { mutableStateOf<Long?>(null) }
    // 文档实际所属空间：跨空间视图里 spaceId 是虚拟值，详情/版本/浏览器跳转都要用真实 spaceId
    var docShareId by remember { mutableStateOf<Long?>(null) }
    var detail by remember { mutableStateOf<SharedDocItem?>(null) }
    // 协同正文（本地解析 yjs_state）：拿到就用，拿不到/解析失败回退 content 快照
    var yjsHtml by remember { mutableStateOf<String?>(null) }
    var yjsLive by remember { mutableStateOf(false) }
    // 拿不到协同正文时的原因（404 未部署 / empty 无状态 / parse 解析失败 / tooLarge 超限），
    // 只用于空正文时把「不可解析」换成能照着做的提示
    var yjsNote by remember { mutableStateOf("") }
    // 历史版本：既用于手动回滚，也作为「拿不到 /yjs 又没快照」时的兜底正文来源
    var versions by remember { mutableStateOf<List<ApiClient.ShareVersionItem>>(emptyList()) }
    var versionsLoading by remember { mutableStateOf(false) }
    var versionsErr by remember { mutableStateOf<String?>(null) }
    var showVersions by remember { mutableStateOf(false) }
    var versionNote by remember { mutableStateOf("") }
    var versionsTick by remember { mutableStateOf(0) }
    // 空间成员管理（仅 OWNER/ADMIN 能改；后端仍会二次校验）
    var showMembers by remember { mutableStateOf(false) }
    // 外部分享链接（`/s/{token}`，只有能管理该文档的人可生成/撤销）
    var showShareLink by remember { mutableStateOf(false) }
    // 文档级权限（单篇覆盖空间权限，能管理该文档的人可改）
    var showDocPerm by remember { mutableStateOf(false) }
    // 文档整理：改标签 / 移动到其他空间
    var showOrganize by remember { mutableStateOf(false) }

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
            if (spaceId == null || (spaceId != ALL_SPACES && spaces.none { it.id == spaceId })) spaceId = spaces.firstOrNull()?.id
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
        docShareId = null
        detail = null
        try {
            docs = if (sid == ALL_SPACES) api.allShareDocs(session.token)
            else api.shareDocs(session.token, sid)
            val first = docs.firstOrNull()
            docId = first?.id
            // 「全部文档」视图下 sid 是虚拟哨兵 -1，不能当 shareId 用，否则后续详情/版本/链接全部 404
            docShareId = first?.takeIf { it.shareId > 0 }?.shareId
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            docsErr = e.message ?: "加载失败"
        } finally {
            docsLoading = false
        }
    }

    LaunchedEffect(docShareId, docId, detailTick) {
        val sid = docShareId ?: return@LaunchedEffect
        val did = docId ?: return@LaunchedEffect
        detailLoading = true
        detailErr = null
        detail = null
        try {
            detail = api.shareDocDetail(session.token, sid, did)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
                // 协同状态可达 8MB，base64 解码 + 解析放后台线程，别卡住 UI
                val html = withContext(Dispatchers.Default) {
                    YjsHtml.toHtml(java.util.Base64.getDecoder().decode(b64))
                }
                if (!html.isNullOrBlank()) {
                    yjsHtml = html
                    yjsLive = true
                } else {
                    yjsNote = "parse"
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 回退快照，不打断阅读；记下原因，供空正文时给出准确提示
            yjsNote = e.message ?: "error"
        }

        // 兜底：没有 /yjs（旧 jar）且 content 快照也是空的时候，用最近一个历史版本的
        // yjs_state 解析正文——versions 是早已部署的端点，用户不必等后端升级。
        versionNote = ""
        if (yjsHtml == null && detail?.content.isNullOrBlank()) {
            runCatching { api.shareDocVersions(session.token, sid, did) }.getOrNull()?.let { vs ->
                val v = vs.firstOrNull()
                val html = v?.yjsState?.let { b64 ->
                    withContext(Dispatchers.Default) {
                        runCatching { YjsHtml.toHtml(java.util.Base64.getDecoder().decode(b64)) }.getOrNull()
                    }
                }
                if (!html.isNullOrBlank()) {
                    yjsHtml = html
                    versionNote = "版本 #${v.id}（${fmtSharedTime(v.createdAt)}）"
                }
            }
        }
    }

    // 版本面板打开时才拉列表（后端返回完整实体，含大字段，不能每次进详情都拉）
    LaunchedEffect(showVersions, docShareId, docId, versionsTick) {
        if (!showVersions) return@LaunchedEffect
        val sid = docShareId ?: return@LaunchedEffect
        val did = docId ?: return@LaunchedEffect
        versionsLoading = true
        versionsErr = null
        try {
            versions = api.shareDocVersions(session.token, sid, did)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            versionsErr = e.message ?: "版本列表加载失败"
        } finally {
            versionsLoading = false
        }
    }

    val space = spaces.firstOrNull { it.id == (docShareId ?: spaceId) }

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
                        // 跨空间视图：一次列出所有空间里我可见的文档（后端 /docs/all，带截断护栏）
                        item {
                            AllDocsRow(
                                sel = spaceId == ALL_SPACES,
                                count = if (spaceId == ALL_SPACES) docs.size else 0,
                            ) { spaceId = ALL_SPACES }
                        }
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
                    if (spaceId == ALL_SPACES) "全部文档" else (space?.name?.ifBlank { "文档" } ?: "文档"),
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
                if (spaceId != null && spaceId != ALL_SPACES) {
                    TextButton(onClick = { showMembers = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("成员", fontSize = 11.sp)
                    }
                }
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
                    docs.isEmpty() -> ShareHint(
                        if (spaceId == ALL_SPACES) "所有空间里都还没有文档\n可在 Web 端新建或导入"
                        else "这个空间还没有文档\n可在 Web 端新建或导入"
                    )
                    shownDocs.isEmpty() -> ShareHint("没有匹配的文档")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(shownDocs, key = { it.id }) { d ->
                            // 跨空间视图里空间名要从文档自带的 shareId 反查，点选时同步真实 shareId
                            val spaceLabel = spaces.firstOrNull { it.id == d.shareId }?.name
                            ShareDocRow(
                                d,
                                sel = d.id == docId,
                                spaceLabel = spaceLabel,
                            ) {
                                docId = d.id
                                docShareId = d.shareId.takeIf { it > 0 }
                            }
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
                    versionNote = versionNote,
                    onOpenVersions = { showVersions = true },
                    onOpenShare = { showShareLink = true },
                    onOpenPerm = { showDocPerm = true },
                    onOpenOrganize = { showOrganize = true },
                    onOpenInBrowser = {
                        val sid = docShareId
                        if (sid == null || sid <= 0) {
                            yjsNote = "unknown-space"   // 未知空间不拼 -1 链接
                        } else {
                            openShareInBrowser(session.server, "/doc-shares/$sid?doc=${d.id}")
                        }
                    },
                )
            }
        }
    }

    // 历史版本面板：查看 / 恢复 / 把当前内容存成新版本
    if (showVersions) {
        val sid = docShareId
        val did = docId
        AlertDialog(
            onDismissRequest = { showVersions = false },
            title = { Text("历史版本", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.width(440.dp).heightIn(max = 420.dp)) {
                    Text(
                        "恢复会写回标题/正文/协同状态，协同端重连后生效；列表里是手动保存的快照。",
                        fontSize = 11.5.sp,
                        color = KbTextSub,
                    )
                    Spacer(Modifier.height(8.dp))
                    when {
                        versionsLoading -> Text("加载中…", fontSize = 12.5.sp, color = KbTextSub)
                        versionsErr != null -> Text(versionsErr ?: "", fontSize = 12.5.sp, color = Color(0xFFDC2626))
                        versions.isEmpty() -> Text(
                            "还没有历史版本。可先点下方「保存当前为新版本」留一个还原点。",
                            fontSize = 12.5.sp, color = KbTextSub,
                        )
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(versions.size) { i ->
                                val v = versions[i]
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "#${v.id}  ${fmtSharedTime(v.createdAt)}",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = KbTextMain,
                                        )
                                        Text(
                                            (if (v.contentLen > 0) "快照 ${v.contentLen} 字符" else "仅协同状态") +
                                                (if (v.yjsState != null) " · 含协同状态" else ""),
                                            fontSize = 11.sp,
                                            color = KbTextSub,
                                        )
                                    }
                                    TextButton(
                                        enabled = sid != null && did != null,
                                        onClick = {
                                            if (sid == null || did == null) return@TextButton
                                            scope.launch {
                                                runCatching { api.restoreShareDocVersion(session.token, sid, did, v.id) }
                                                    .onSuccess {
                                                        showVersions = false
                                                        detailTick++
                                                    }
                                                    .onFailure { versionsErr = it.message ?: "恢复失败" }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) { Text("恢复", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        TextButton(
                            enabled = sid != null && did != null,
                            onClick = {
                                if (sid == null || did == null) return@TextButton
                                scope.launch {
                                    runCatching { api.snapshotShareDoc(session.token, sid, did) }
                                        .onSuccess { versionsTick++ }
                                        .onFailure { versionsErr = it.message ?: "保存版本失败" }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text("保存当前为新版本", fontSize = 12.sp) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showVersions = false }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("关闭", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    // 成员管理：空间 meta（所有者/我的权限/容量）来自 /my 已拉到的 spaces，改动后刷新空间列表
    val memberSpace = spaces.firstOrNull { it.id == spaceId }
    if (showMembers && memberSpace != null) {
        SpaceMembersDialog(
            session = session,
            api = api,
            space = memberSpace,
            onClose = { showMembers = false },
            onChanged = { spacesTick++ },
        )
    }

    // 外部分享链接：必须用文档真实 shareId（跨空间视图下 spaceId 是 -1 哨兵）
    val shareSid = docShareId
    val shareDid = docId
    if (showShareLink && shareSid != null && shareSid > 0 && shareDid != null) {
        ShareLinkDialog(
            session = session,
            api = api,
            shareId = shareSid,
            docId = shareDid,
            docTitle = detail?.title.orEmpty(),
            onClose = { showShareLink = false },
        )
    }

    // 文档级权限：同样用真实 shareId；改动后重拉详情（我的权限可能变化）
    if (showDocPerm && shareSid != null && shareSid > 0 && shareDid != null) {
        DocPermDialog(
            session = session,
            api = api,
            shareId = shareSid,
            docId = shareDid,
            docTitle = detail?.title.orEmpty(),
            onClose = { showDocPerm = false },
            onChanged = { detailTick++ },
        )
    }

    // 文档整理（标签 / 移动）：改动后刷新列表；移动成功后该文档已不在当前空间，直接收起阅读区
    val organizeDoc = detail
    if (showOrganize && shareSid != null && shareSid > 0 && shareDid != null && organizeDoc != null) {
        DocOrganizeDialog(
            session = session,
            api = api,
            shareId = shareSid,
            doc = organizeDoc,
            allSpaces = spaces,
            onClose = { showOrganize = false },
            onChanged = {
                detailTick++
                docsTick++
            },
            onMoved = {
                showOrganize = false
                detailTick++
                docsTick++
            },
        )
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
private fun ShareDocRow(
    d: SharedDocItem,
    sel: Boolean,
    spaceLabel: String? = null,
    onClick: () -> Unit,
) {
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
                // 跨空间视图里补上所属空间，否则同名文档分不清
                spaceLabel?.takeIf { it.isNotBlank() },
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

/** 左侧「全部文档」入口：跨空间聚合视图，条数与当前空间列表同源 */
@Composable
private fun AllDocsRow(sel: Boolean, count: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (sel) Color(0xFFE8EEFB) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).background(Color(0xFFE6F0FF), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("全", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2563EB))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "全部文档",
                fontSize = 12.5.sp,
                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                color = KbTextMain,
                maxLines = 1,
            )
            Text(
                if (sel && count > 0) "$count 篇（跨空间）" else "跨空间检索",
                fontSize = 10.5.sp,
                color = KbTextSub,
                maxLines = 1,
            )
        }
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
    versionNote: String = "",
    onOpenVersions: () -> Unit = {},
    onOpenShare: () -> Unit = {},
    onOpenPerm: () -> Unit = {},
    onOpenOrganize: () -> Unit = {},
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
                    TextButton(onClick = onOpenShare, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("分享", fontSize = 11.5.sp)
                    }
                    TextButton(onClick = onOpenPerm, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("权限", fontSize = 11.5.sp)
                    }
                    TextButton(onClick = onOpenOrganize, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("整理", fontSize = 11.5.sp)
                    }
                    TextButton(onClick = onOpenVersions, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("版本", fontSize = 11.5.sp)
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
                            // 兜底正文来自历史版本时标出来源，别让人误以为是当前内容
                            if (versionNote.isNotBlank()) ShareChip(versionNote, Color(0xFFFFF7E6), Color(0xFFAD6800))
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
    "ADMIN" -> "可管理"
    "WRITE", "EDIT" -> "可编辑"
    "READ", "VIEW" -> "只读"
    else -> perm.lowercase()
}

private val SHARED_TAG_SEP = Regex("[,，、;；\\s]+")

private fun splitSharedTags(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(SHARED_TAG_SEP).map { it.trim() }.filter { it.isNotEmpty() }.take(4)
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

/**
 * 空间成员管理（OWNER/ADMIN 可增删改；其他成员只读）。
 *
 * 后端：`GET /{sid}/members`（不含所有者行）、`POST /{sid}/members {userId,permission}`、
 * `PUT /{sid}/members/{userId} {permission}`、`DELETE /{sid}/members/{userId}`、
 * `GET /users?keyword=`（邀请候选，启用账号最多 20 个）。权限值只有 READ / WRITE / ADMIN。
 */
@Composable
private fun SpaceMembersDialog(
    session: Session,
    api: ApiClient,
    space: AgentSpace,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<ShareMemberItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var busyId by remember { mutableStateOf<Long?>(null) }
    // 邀请面板
    var inviting by remember { mutableStateOf(false) }
    var kw by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<InviteUserItem>>(emptyList()) }
    var invitePerm by remember { mutableStateOf("WRITE") }
    val canManage = space.myPermission.uppercase().let { it == "OWNER" || it == "ADMIN" }

    LaunchedEffect(tick) {
        loading = true
        err = null
        try {
            members = api.shareMembers(session.token, space.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            err = e.message ?: "成员列表加载失败"
        } finally {
            loading = false
        }
    }

    // 邀请候选：展开面板时按关键词防抖查询（空关键词返回最近 20 个启用账号）
    LaunchedEffect(inviting, kw) {
        if (!inviting) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        candidates = runCatching { api.shareInviteUsers(session.token, kw.trim()) }.getOrDefault(emptyList())
    }

    fun applyPerm(uid: Long, perm: String) {
        busyId = uid
        scope.launch {
            try {
                api.updateShareMember(session.token, space.id, uid, perm)
                note = "已把权限改为「${permLabel(perm)}」"
                tick++
                onChanged()
            } catch (e: Exception) {
                note = e.message ?: "改权限失败"
            } finally {
                busyId = null
            }
        }
    }

    fun remove(uid: Long) {
        busyId = uid
        scope.launch {
            try {
                api.removeShareMember(session.token, space.id, uid)
                note = "已移除该成员"
                tick++
                onChanged()
            } catch (e: Exception) {
                note = e.message ?: "移除失败"
            } finally {
                busyId = null
            }
        }
    }

    fun invite(u: InviteUserItem) {
        busyId = u.id
        scope.launch {
            try {
                api.addShareMember(session.token, space.id, u.id, invitePerm)
                note = "已邀请「${u.displayName.ifBlank { u.username }}」加入，权限「${permLabel(invitePerm)}」"
                tick++
                onChanged()
            } catch (e: Exception) {
                note = e.message ?: "邀请失败"
            } finally {
                busyId = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("成员 · ${space.name}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(460.dp)) {
                // meta：所有者 / 我的权限 / 容量占用（后端瞬态字段，缺省不显示）
                Text(
                    "所有者：${space.ownerName.ifBlank { "未知" }} · 我的权限：${permLabel(space.myPermission.ifBlank { "READ" })}" +
                        if (space.capacity > 0) " · 已用 ${fmtShareSize(space.used).ifBlank { "0 B" }} / ${fmtShareSize(space.capacity)}" else "",
                    fontSize = 11.5.sp,
                    color = KbTextSub,
                )
                if (note.isNotEmpty()) {
                    Text(note, fontSize = 11.5.sp, color = ShareErrRed, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = KbBorder)
                Box(Modifier.heightIn(max = 300.dp)) {
                    when {
                        loading -> ShareHint("加载中…")
                        err != null -> ShareError(err ?: "", onRetry = { tick++ })
                        else -> LazyColumn(Modifier.fillMaxWidth()) {
                            // 所有者行（不可改/不可移除）
                            if (space.ownerName.isNotBlank()) {
                                item {
                                    MemberRow(
                                        name = space.ownerName,
                                        sub = "所有者",
                                        perm = "OWNER",
                                        canManage = false,
                                        busy = false,
                                        onPerm = {},
                                        onRemove = {},
                                    )
                                }
                            }
                            if (members.isEmpty()) {
                                item { ShareHint("除所有者外还没有成员\n点下方「邀请成员」添加") }
                            }
                            items(members, key = { it.userId }) { m ->
                                MemberRow(
                                    name = m.displayName.ifBlank { m.username.ifBlank { "用户 ${m.userId}" } },
                                    sub = m.username,
                                    perm = m.permission,
                                    canManage = canManage,
                                    busy = busyId == m.userId,
                                    onPerm = { p -> applyPerm(m.userId, p) },
                                    onRemove = { remove(m.userId) },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = KbBorder)
                if (!canManage) {
                    Text("只有所有者或「可管理」成员能增删成员", fontSize = 11.sp, color = KbTextSub)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { inviting = !inviting; if (!inviting) kw = "" },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text(if (inviting) "收起邀请" else "＋ 邀请成员", fontSize = 12.sp) }
                        if (inviting) {
                            Text("新成员权限：", fontSize = 11.5.sp, color = KbTextSub)
                            for (p in listOf("READ", "WRITE", "ADMIN")) {
                                SharePermChip(p, selected = invitePerm == p) { invitePerm = p }
                            }
                        }
                    }
                    if (inviting) {
                        OutlinedTextField(
                            value = kw,
                            onValueChange = { kw = it },
                            placeholder = { Text("按用户名 / 昵称搜索", fontSize = 11.5.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Box(Modifier.heightIn(max = 180.dp)) {
                            if (candidates.isEmpty()) {
                                ShareHint("没有可邀请的用户")
                            } else {
                                LazyColumn(Modifier.fillMaxWidth()) {
                                    items(candidates, key = { it.id }) { u ->
                                        Row(
                                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                u.displayName.ifBlank { u.username },
                                                fontSize = 12.5.sp,
                                                color = KbTextMain,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            TextButton(
                                                enabled = busyId != u.id,
                                                onClick = { invite(u) },
                                                contentPadding = PaddingValues(horizontal = 6.dp),
                                            ) { Text(if (busyId == u.id) "加入中…" else "加入", fontSize = 11.5.sp) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("关闭", fontSize = 12.sp)
            }
        },
    )
}

/** 成员行：可管理时显示「只读 / 可编辑 / 可管理」三档切换 + 移除。 */
@Composable
private fun MemberRow(
    name: String,
    sub: String,
    perm: String,
    canManage: Boolean,
    busy: Boolean,
    onPerm: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(ShareRailBg).border(1.dp, KbBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1), fontSize = 11.sp, color = KbTextSub)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 12.5.sp, color = KbTextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank() && sub != name) {
                Text(sub, fontSize = 10.5.sp, color = KbTextSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (canManage && perm.uppercase() != "OWNER") {
            for (p in listOf("READ", "WRITE", "ADMIN")) {
                TextButton(
                    enabled = !busy,
                    onClick = { if (p != perm.uppercase()) onPerm(p) },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(
                        if (perm.uppercase() == p) "● ${permLabel(p)}" else permLabel(p),
                        fontSize = 10.5.sp,
                        color = if (perm.uppercase() == p) ShareErrRed else KbTextSub,
                    )
                }
            }
            TextButton(enabled = !busy, onClick = onRemove, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("移除", fontSize = 10.5.sp, color = ShareErrRed)
            }
        } else {
            ShareChip(permLabel(perm), ShareRailBg, KbTextSub)
        }
    }
}

/** 邀请权限小签（选中态描边） */
@Composable
private fun SharePermChip(perm: String, selected: Boolean, onClick: () -> Unit) {
    ShareToggleChip(permLabel(perm), selected, onClick)
}

/** 通用选择小签（内容原样显示；有效期 / 复制限制这类非权限值用这个） */
@Composable
private fun ShareToggleChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 10.5.sp,
        color = if (selected) KbTextMain else KbTextSub,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) ShareRailBg else Color.Transparent)
            .border(1.dp, if (selected) KbTextSub else KbBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 外部分享链接（文档级，只有能管理该文档的人可操作）。
 *
 * 后端：`GET|POST|DELETE /{sid}/docs/{docId}/public-link`，POST body `{password?, expireDays?, noCopy}`；
 * 返回 `token/hasPassword/expireAt/noCopy/views`。访问地址是 Web 端 `/s/{token}`（有密码要输密码），
 * 撤销后历史链接立即 404。有效期 0 = 永久。
 */
@Composable
private fun ShareLinkDialog(
    session: Session,
    api: ApiClient,
    shareId: Long,
    docId: Long,
    docTitle: String,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<SharePublicLink?>(null) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    // 设置项（默认沿用当前值）
    var pwd by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(0) }
    var noCopy by remember { mutableStateOf(false) }

    LaunchedEffect(tick) {
        loading = true
        err = null
        try {
            val v = api.sharePublicLink(session.token, shareId, docId)
            info = v
            noCopy = v.noCopy
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            err = e.message ?: "分享状态加载失败"
        } finally {
            loading = false
        }
    }

    val url = if (info?.enabled == true && !info!!.token.isNullOrBlank())
        session.server.trimEnd('/') + "/s/" + info!!.token else ""

    fun save() {
        busy = true
        scope.launch {
            try {
                info = api.saveSharePublicLink(
                    session.token, shareId, docId,
                    password = pwd.ifBlank { null },
                    expireDays = days,
                    noCopy = noCopy,
                )
                note = if (url.isBlank()) "已开启外部分享" else "已更新分享设置（链接不变）"
                pwd = ""
                tick++
            } catch (e: Exception) {
                note = e.message ?: "生成失败"
            } finally {
                busy = false
            }
        }
    }

    fun copy(text: String) {
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                java.awt.datatransfer.StringSelection(text), null,
            )
            note = "链接已复制"
        }.onFailure { note = "复制失败：${it.message}" }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("外部分享链接", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(480.dp)) {
                Text(
                    "「${docTitle.ifBlank { "当前文档" }}」· 拿到链接的人无需登录即可只读打开",
                    fontSize = 11.5.sp,
                    color = KbTextSub,
                )
                if (note.isNotEmpty()) {
                    Text(note, fontSize = 11.5.sp, color = ShareErrRed, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> ShareHint("加载中…")
                    err != null -> ShareError(err ?: "", onRetry = { tick++ })
                    else -> {
                        if (url.isNotBlank()) {
                            Text("访问地址", fontSize = 11.sp, color = KbTextSub)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    url,
                                    fontSize = 11.5.sp,
                                    color = KbTextMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { copy(url) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                    Text("复制", fontSize = 11.5.sp)
                                }
                                TextButton(
                                    onClick = { openShareInBrowser(session.server, url) },
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                ) { Text("打开", fontSize = 11.5.sp) }
                            }
                            Text(
                                buildString {
                                    append(if (info?.hasPassword == true) "需要密码" else "无密码")
                                    append(" · ")
                                    append(if (!info?.expireAt.isNullOrBlank()) "有效期至 " + fmtSharedTime(info?.expireAt) else "永久有效")
                                    if (info?.noCopy == true) append(" · 禁止复制")
                                    append(" · 已浏览 ${info?.views ?: 0} 次")
                                },
                                fontSize = 11.sp,
                                color = KbTextSub,
                            )
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = KbBorder)
                            Spacer(Modifier.height(8.dp))
                        }
                        // 设置区（已开启时是「更新设置」，未开启时是「开启」）
                        OutlinedTextField(
                            value = pwd,
                            onValueChange = { pwd = it },
                            label = { Text("访问密码（留空 = 无密码）", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("有效期：", fontSize = 11.5.sp, color = KbTextSub)
                            for (d in listOf(0, 7, 30, 90)) {
                                ShareToggleChip(if (d == 0) "永久" else "${d} 天", days == d) { days = d }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("复制限制：", fontSize = 11.5.sp, color = KbTextSub)
                            ShareToggleChip("允许复制", !noCopy) { noCopy = false }
                            ShareToggleChip("禁止复制", noCopy) { noCopy = true }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row {
                            TextButton(enabled = !busy, onClick = { save() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(if (busy) "提交中…" else if (url.isBlank()) "生成链接" else "更新设置", fontSize = 12.sp)
                            }
                            if (url.isNotBlank()) {
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        busy = true
                                        scope.launch {
                                            try {
                                                api.revokeSharePublicLink(session.token, shareId, docId)
                                                info = null
                                                note = "已撤销，历史链接立即失效"
                                                tick++
                                            } catch (e: Exception) {
                                                note = e.message ?: "撤销失败"
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) { Text("撤销链接", fontSize = 12.sp, color = ShareErrRed) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("关闭", fontSize = 12.sp)
            }
        },
    )
}

/**
 * 文档级权限（单篇覆盖空间权限）。
 *
 * 后端：`GET /{sid}/docs/{docId}/members` → `[{userId,username,displayName,avatar,spacePermission,permission,override}]`
 * （`spacePermission=null` 表示已不在空间里的历史覆盖）；`PUT …/members/{userId} {permission}` 传**与空间权限相同**的
 * 值等于清除单篇设置（返回 `cleared=true`，回退继承）；`DELETE …/members/{userId}` 直接恢复继承。
 * 仅「能管理该文档」的人可查/可改（空间 OWNER/ADMIN，或文档级 ADMIN），其余 403 原文透出。
 */
@Composable
private fun DocPermDialog(
    session: Session,
    api: ApiClient,
    shareId: Long,
    docId: Long,
    docTitle: String,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<DocMemberItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var busyId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(tick) {
        loading = true
        err = null
        try {
            members = api.docMembers(session.token, shareId, docId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            err = e.message ?: "文档权限加载失败"
        } finally {
            loading = false
        }
    }

    fun setPerm(m: DocMemberItem, perm: String) {
        busyId = m.userId
        scope.launch {
            try {
                val cleared = api.setDocMember(session.token, shareId, docId, m.userId, perm)
                note = if (cleared) "已恢复继承（与空间权限同为「${permLabel(perm)}」）"
                else "已把这条文档的权限设为「${permLabel(perm)}」"
                tick++
                onChanged()
            } catch (e: Exception) {
                note = e.message ?: "改权限失败"
            } finally {
                busyId = null
            }
        }
    }

    fun inherit(m: DocMemberItem) {
        busyId = m.userId
        scope.launch {
            try {
                api.clearDocMember(session.token, shareId, docId, m.userId)
                note = "已恢复继承空间权限"
                tick++
                onChanged()
            } catch (e: Exception) {
                note = e.message ?: "恢复继承失败"
            } finally {
                busyId = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("文档权限 · ${docTitle.ifBlank { "当前文档" }}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(520.dp)) {
                Text(
                    "单篇可覆盖空间权限；选回与空间相同的档位即自动恢复继承（「单独」标记消失）",
                    fontSize = 11.5.sp,
                    color = KbTextSub,
                )
                if (note.isNotEmpty()) {
                    Text(note, fontSize = 11.5.sp, color = ShareErrRed, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = KbBorder)
                Box(Modifier.heightIn(max = 340.dp)) {
                    when {
                        loading -> ShareHint("加载中…")
                        err != null -> ShareError(err ?: "", onRetry = { tick++ })
                        members.isEmpty() -> ShareHint("这个空间还没有其他成员\n先在「成员」里邀请")
                        else -> LazyColumn(Modifier.fillMaxWidth()) {
                            items(members, key = { it.userId }) { m ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            m.displayName.ifBlank { m.username.ifBlank { "用户 ${m.userId}" } },
                                            fontSize = 12.5.sp,
                                            color = KbTextMain,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "空间：" + permLabel(m.spacePermission ?: "无") +
                                                if (m.override) " · 本篇单独设置" else "",
                                            fontSize = 10.5.sp,
                                            color = if (m.override) ShareErrRed else KbTextSub,
                                        )
                                    }
                                    for (p in listOf("READ", "WRITE", "ADMIN")) {
                                        TextButton(
                                            enabled = busyId != m.userId,
                                            onClick = { if (p != m.permission.uppercase()) setPerm(m, p) },
                                            contentPadding = PaddingValues(horizontal = 4.dp),
                                        ) {
                                            Text(
                                                if (p == m.permission.uppercase()) "● ${permLabel(p)}" else permLabel(p),
                                                fontSize = 10.5.sp,
                                                color = if (p == m.permission.uppercase()) ShareErrRed else KbTextSub,
                                            )
                                        }
                                    }
                                    TextButton(
                                        enabled = busyId != m.userId && m.override,
                                        onClick = { inherit(m) },
                                        contentPadding = PaddingValues(horizontal = 4.dp),
                                    ) { Text("继承", fontSize = 10.5.sp, color = KbTextSub) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("关闭", fontSize = 12.sp)
            }
        },
    )
}

/**
 * 文档整理：改标签 / 移动到其他空间。
 *
 * 后端：`PUT …/docs/{docId}/tags {tags:[…]}`（标签在库里是逗号分隔字符串，返回更新后的文档）；
 * `POST …/docs/{docId}/move {targetShareId}`（需空间/文档管理员或**文档所有者**，目标空间要有写权限）。
 */
@Composable
private fun DocOrganizeDialog(
    session: Session,
    api: ApiClient,
    shareId: Long,
    doc: SharedDocItem,
    allSpaces: List<AgentSpace>,
    onClose: () -> Unit,
    onChanged: () -> Unit,
    onMoved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tags by remember(doc.id) { mutableStateOf(splitSharedTags(doc.tags).joinToString("，")) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var movingTo by remember { mutableStateOf<Long?>(null) }
    var confirmingTarget by remember { mutableStateOf<AgentSpace?>(null) }

    // 只有能写的空间才能作为目标（后端也会再校验）
    val targets = allSpaces.filter { s ->
        s.id != shareId && s.myPermission.uppercase().let { it == "WRITE" || it == "ADMIN" || it == "OWNER" }
    }

    fun saveTags() {
        busy = true
        scope.launch {
            try {
                val list = tags.split(SHARED_TAG_SEP).map { it.trim() }.filter { it.isNotEmpty() }.take(20)
                api.updateShareDocTags(session.token, shareId, doc.id, list)
                note = if (list.isEmpty()) "已清空标签" else "已保存 " + list.size + " 个标签"
                onChanged()
            } catch (e: Exception) {
                note = e.message ?: "保存标签失败"
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("整理 · ${doc.title.ifBlank { "当前文档" }}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(500.dp)) {
                if (note.isNotEmpty()) {
                    Text(note, fontSize = 11.5.sp, color = ShareErrRed, modifier = Modifier.padding(bottom = 4.dp))
                }
                Text("标签（逗号分隔，最多 20 个）", fontSize = 11.sp, color = KbTextSub)
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    singleLine = true,
                    placeholder = { Text("例如：产品，需求，2026", fontSize = 11.5.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(top = 4.dp)) {
                    TextButton(enabled = !busy, onClick = { saveTags() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(if (busy) "保存中…" else "保存标签", fontSize = 12.sp)
                    }
                    TextButton(
                        enabled = !busy && splitSharedTags(doc.tags).isNotEmpty(),
                        onClick = { tags = "" },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("清空输入", fontSize = 12.sp) }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = KbBorder)
                Spacer(Modifier.height(8.dp))
                Text(
                    "移动到其他空间（我的权限为可编辑/可管理的空间才列出；需要你对本文档有管理权或你是作者）",
                    fontSize = 11.sp,
                    color = KbTextSub,
                )
                Spacer(Modifier.height(4.dp))
                Box(Modifier.heightIn(max = 220.dp)) {
                    if (targets.isEmpty()) {
                        ShareHint("没有可移动的目标空间")
                    } else {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(targets, key = { it.id }) { s ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            s.name.ifBlank { "空间 ${s.id}" },
                                            fontSize = 12.5.sp,
                                            color = KbTextMain,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            spaceTypeLabel(s.type) + " · " + permLabel(s.myPermission),
                                            fontSize = 10.5.sp,
                                            color = KbTextSub,
                                        )
                                    }
                                    TextButton(
                                        enabled = movingTo == null,
                                        onClick = { confirmingTarget = s },
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                    ) { Text(if (movingTo == s.id) "移动中…" else "移到这里", fontSize = 11.5.sp) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("关闭", fontSize = 12.sp)
            }
        },
    )

    // 移动二次确认（不可撤销的整理动作）
    confirmingTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmingTarget = null },
            title = { Text("移动到「${target.name.ifBlank { "空间 " + target.id }}」？") },
            text = { Text("移动后本文档会从当前空间消失，历史版本随文档一起走；归属人不变。", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    val t = target
                    confirmingTarget = null
                    movingTo = t.id
                    busy = true
                    scope.launch {
                        try {
                            api.moveShareDoc(session.token, shareId, doc.id, t.id)
                            note = "已移动到「" + t.name.ifBlank { "空间 " + t.id } + "」"
                            onMoved()
                        } catch (e: Exception) {
                            note = e.message ?: "移动失败"
                        } finally {
                            movingTo = null
                            busy = false
                        }
                    }
                }) { Text("确认移动") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingTarget = null }) { Text("取消") }
            },
        )
    }
}
