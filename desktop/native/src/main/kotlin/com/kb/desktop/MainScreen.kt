package com.kb.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import io.github.kdroidfilter.webview.web.WebContent
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.WebViewState
import kotlinx.coroutines.launch
import java.net.URI

enum class MenuKind(val label: String) {
    HOME("首页"), DOCS("文章"), CHAT("AI 问答"), FILES("我的文件"), SETTINGS("设置")
}

/** 侧栏收成图标栏的宽度阈值（低于它窗口放不下完整侧栏+内容） */
private val RAIL_BREAK = 880.dp

@Composable
fun MainScreen(session: Session, onLogout: () -> Unit) {
    var selected by remember { mutableStateOf(MenuKind.HOME) }
    var update by remember { mutableStateOf<AppVersionInfo?>(null) }
    // AI 问答里点「引用来源」→ 跳到文章面板并打开对应文章（DocsPanel 读一次后消费掉）
    var jumpDocId by remember { mutableStateOf<Long?>(null) }

    // 启动后静默检查更新：发现更高 versionCode 就在顶部提示（不打断使用）
    LaunchedEffect(Unit) {
        try {
            val v = ApiClient(session.server).appVersion(AppInfo.channel)
            if (v.versionCode > AppInfo.versionCode) update = v
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // 协程取消必须继续传播，不能吞掉
        } catch (e: Exception) {
            // 静默：更新检查失败不打断主界面
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(KbBg)) {
        val narrow = maxWidth < RAIL_BREAK

        Row(Modifier.fillMaxSize()) {
            Sidebar(
                session = session,
                selected = selected,
                onSelect = { selected = it },
                rail = narrow,
                onLogout = onLogout,
            )
            if (!narrow) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(KbBorder))
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Header(selected)
                val u = update
                if (u != null) {
                    UpdateBanner(
                        info = u,
                        onOpen = { selected = MenuKind.SETTINGS },
                        onDismiss = { update = null },
                    )
                }
                Box(Modifier.fillMaxSize().padding(if (narrow) 10.dp else 20.dp)) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .border(1.dp, KbBorder, RoundedCornerShape(12.dp)),
                    ) {
                        when (selected) {
                            MenuKind.HOME -> HomePanel(session)
                            MenuKind.DOCS -> DocsPanel(session, jumpDocId) { jumpDocId = null }
                            MenuKind.FILES -> FilesPanel(session)
                            MenuKind.SETTINGS -> SettingsPanel(session)
                            MenuKind.CHAT -> ChatPanel(
                                session,
                                onOpenDoc = { id ->
                                    jumpDocId = id
                                    selected = MenuKind.DOCS
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(
    session: Session,
    selected: MenuKind,
    onSelect: (MenuKind) -> Unit,
    rail: Boolean,
    onLogout: () -> Unit,
) {
    Column(
        Modifier
            .width(if (rail) 60.dp else 216.dp)
            .fillMaxHeight()
            .background(Color.White)
    ) {
        Spacer(Modifier.height(14.dp))
        // 品牌行：窄栏只留图标
        if (rail) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(KbIconGradient(KbTileViolet.first, KbTileViolet.second)),
                    contentAlignment = Alignment.Center,
                ) { BrandGlyphMini(Color.White, Modifier.size(19.dp)) }
            }
        } else {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(KbIconGradient(KbTileViolet.first, KbTileViolet.second)),
                    contentAlignment = Alignment.Center,
                ) { BrandGlyphMini(Color.White, Modifier.size(19.dp)) }
                Spacer(Modifier.width(10.dp))
                Text("知识库", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
            }
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        Column(
            Modifier.padding(horizontal = if (rail) 8.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (kind in MenuKind.entries) {
                MenuItem(kind, sel = kind == selected, rail = rail) { onSelect(kind) }
            }
        }

        Spacer(Modifier.weight(1f))
        HorizontalDivider()

        // 用户区：窄栏只留头像（点击即退出）
        if (rail) {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                UserAvatar(session.username, onClick = onLogout)
            }
        } else {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(session.username)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(session.username, fontSize = 12.5.sp, color = KbTextMain, maxLines = 1)
                }
                TextButton(onClick = onLogout, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("退出", fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun MenuItem(kind: MenuKind, sel: Boolean, rail: Boolean, onClick: () -> Unit) {
    val tile = when (kind) {
        MenuKind.HOME -> KbTileBlue
        MenuKind.DOCS -> KbTileViolet
        MenuKind.CHAT -> KbTileGreen
        MenuKind.FILES -> KbTileAmber
        MenuKind.SETTINGS -> KbTileBlue
    }
    if (rail) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) KbSidebarSelected else Color.Transparent)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { IconTile(kind, tile, 24.dp) }
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (sel) KbSidebarSelected else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(kind, tile, 24.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                kind.label,
                fontSize = 13.sp,
                color = if (sel) KbTextMain else KbTextSub,
                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun IconTile(kind: MenuKind, tile: Pair<Color, Color>, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(7.dp))
            .background(KbIconGradient(tile.first, tile.second)),
        contentAlignment = Alignment.Center,
    ) { MenuGlyph(kind, Color.White, Modifier.size(size * 0.58f)) }
}

@Composable
private fun UserAvatar(username: String, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(KbIconGradient(KbTileBlue.first, KbTileBlue.second))
            .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            username.take(1).uppercase(),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Header(kind: MenuKind) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(Color.White)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(kind.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(KbBorder))
}

@Composable
private fun HorizontalDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(KbBorder))
}

@Composable
private fun PanelHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = KbTextSub, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HomePanel(session: Session) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            "原生桌面端已接通文章与文件",
            style = androidx.compose.ui.text.TextStyle(brush = Brush.linearGradient(listOf(KbPrimaryLight, KbPrimaryDeep))),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(14.dp))
        val lines = listOf(
            "原生 UI：Compose Multiplatform（Kotlin），非 WebView 套壳",
            "登录：滑块验证码 + 账号密码，与 Web 端同一套接口",
            "文章：原生列表 + 原生阅读（正文/目录/评论原生渲染）；新建/编辑用 Web 端编辑器",
            "我的文件：原生列表，双击在系统浏览器打开",
        )
        lines.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(KbPrimaryLight)
                )
                Spacer(Modifier.width(10.dp))
                Text(line, fontSize = 13.sp, color = KbTextSub)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun UpdateBanner(info: AppVersionInfo, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFFFF7E6)).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("发现新版本 ${info.versionName}", fontSize = 12.sp, color = Color(0xFF92400E), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(12.dp))
        Text(
            "查看更新", fontSize = 11.5.sp, color = Color(0xFFB45309), fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onOpen),
        )
        Spacer(Modifier.weight(1f))
        Text("关闭", fontSize = 11.sp, color = KbTextSub, modifier = Modifier.clickable(onClick = onDismiss))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFFDE68A)))
}

@Composable
private fun SettingsPanel(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var remote by remember { mutableStateOf<AppVersionInfo?>(null) }

    Column(Modifier.fillMaxSize().padding(26.dp)) {
        Text("设置", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, KbBorder, RoundedCornerShape(12.dp))
                .padding(20.dp),
        ) {
            Text("关于", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
            Spacer(Modifier.height(12.dp))
            SettingRow("当前版本", "${AppInfo.versionName}（${AppInfo.versionCode}）")
            SettingRow("更新通道", AppInfo.channel)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (checking) return@Button
                    checking = true
                    error = null
                    scope.launch {
                        try {
                            remote = api.appVersion(AppInfo.channel)
                            checked = true
                        } catch (e: Exception) {
                            // 失败时清掉上一次结果，避免「已发现新版本」与新报错同时显示
                            remote = null
                            checked = false
                            error = e.message ?: "检查失败"
                        } finally {
                            checking = false
                        }
                    }
                },
                enabled = !checking,
                shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KbPrimary, contentColor = Color.White),
            ) { Text(if (checking) "检查中…" else "检查更新", fontSize = 13.sp) }

            Spacer(Modifier.height(14.dp))
            val e = error
            val r = remote
            when {
                e != null -> Text(e, fontSize = 12.sp, color = Color(0xFFDC2626))
                checked && r != null -> {
                    if (r.versionCode > AppInfo.versionCode) {
                        Text(
                            "发现新版本 ${r.versionName}", fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, color = Color(0xFFB45309),
                        )
                        if (r.notes.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(r.notes, fontSize = 12.sp, color = KbTextSub)
                        }
                        Spacer(Modifier.height(10.dp))
                        if (r.url.isNotBlank()) {
                            Button(
                                onClick = { openInBrowser(session.server, r.url) },
                                shape = RoundedCornerShape(9.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = KbPrimary, contentColor = Color.White),
                            ) { Text("下载更新", fontSize = 13.sp) }
                        } else {
                            Text("安装包尚未上传，请联系管理员", fontSize = 12.sp, color = KbTextSub)
                        }
                    } else {
                        Text("已是最新版本", fontSize = 12.sp, color = Color(0xFF059669))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.5.sp, color = KbTextSub, modifier = Modifier.width(84.dp))
        Text(value, fontSize = 12.5.sp, color = KbTextMain)
    }
}

@Composable
private fun DocsPanel(
    session: Session,
    jumpDocId: Long? = null,
    onJumpConsumed: () -> Unit = {},
) {
    val api = remember(session) { ApiClient(session.server) }
    var docs by remember { mutableStateOf<List<DocSummary>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<DocSummary?>(null) }
    var detail by remember { mutableStateOf<DocDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var detailTick by remember { mutableStateOf(0) }
    var wv2Ok by remember { mutableStateOf<Boolean?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Long?>(null) }

    // 外部跳转（AI 问答引用来源）：只拿到 id 时也直接打开详情
    LaunchedEffect(jumpDocId) {
        val id = jumpDocId ?: return@LaunchedEffect
        selected = DocSummary(id = id, title = "", ownerName = "", updatedAt = "", views = 0, category = null, snippet = null)
        onJumpConsumed()
    }
    val webState = remember(session) {
        WebViewState(WebContent.Data(BLANK_READER, null)).apply {
            // 编辑视图加载 Web 端 SPA 页：页面脚本执行前注入 token（会话内存值，匿名档案不落盘）
            webSettings.desktopWebSettings.incognito = true
            webSettings.desktopWebSettings.initScript =
                "try{localStorage.setItem('kb_token',${jsStr(session.token)})}catch(e){}"
        }
    }

    LaunchedEffect(refresh) {
        loading = true
        error = null
        try {
            val page = api.docs(session.token)
            docs = page.items
            total = page.total
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    // 阅读详情：原生渲染（2026-11-13 起不再喂 WebView；WebView 只服务编辑视图）
    LaunchedEffect(selected?.id, detailTick) {
        val s = selected
        if (s == null) {
            detail = null
            detailError = null
            detailLoading = false
            return@LaunchedEffect
        }
        detail = null
        detailError = null
        detailLoading = true
        try {
            detail = api.doc(session.token, s.id)
        } catch (e: Exception) {
            detailError = e.message ?: "加载失败"
        } finally {
            detailLoading = false
        }
    }

    LaunchedEffect(Unit) {
        wv2Ok = hasWebView2Runtime()
    }

    LaunchedEffect(editing, editTarget) {
        if (editing) {
            val root = session.server.trimEnd('/')
            webState.content = WebContent.Url(
                if (editTarget == null) "$root/write" else "$root/write/edit/$editTarget"
            )
        }
    }

    Row(Modifier.fillMaxSize()) {
        // 左：文章列表
        Column(Modifier.width(280.dp).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("全部文章", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
                if (total > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("$total", fontSize = 10.5.sp, color = KbTextSub)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editing = true; editTarget = null }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("新建", fontSize = 11.sp)
                }
                TextButton(onClick = { refresh++ }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("刷新", fontSize = 11.sp)
                }
            }
            HorizontalDivider()
            Box(Modifier.weight(1f)) {
                when {
                    loading -> PanelHint("加载中…")
                    error != null -> Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(error ?: "", fontSize = 12.sp, color = KbTextSub, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { refresh++ }) { Text("重试", fontSize = 12.sp) }
                    }
                    docs.isEmpty() -> PanelHint("暂无文章")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(docs, key = { it.id }) { d ->
                            DocRow(d, sel = selected?.id == d.id) { selected = d }
                        }
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(KbBorder))
        // 右：阅读视图 / 编辑视图（编辑加载 Web 端 SPA 页，token 由 initScript 注入）
        Column(Modifier.weight(1f).fillMaxHeight()) {
            when {
                editing -> {
                    Row(
                        Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { editing = false; detailTick++; refresh++ },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) { Text("← 返回阅读", fontSize = 11.sp) }
                        Text("在页面内保存后点返回，列表会自动刷新", fontSize = 10.5.sp, color = KbTextSub)
                    }
                    HorizontalDivider()
                }
                selected != null -> {
                    Row(
                        Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { editing = true; editTarget = selected?.id },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) { Text("编辑", fontSize = 11.sp) }
                    }
                    HorizontalDivider()
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val s = selected
                val d = detail
                when {
                    !editing && s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(KbIconGradient(KbTileViolet.first, KbTileViolet.second)),
                                contentAlignment = Alignment.Center,
                            ) { MenuGlyph(MenuKind.DOCS, Color.White, Modifier.size(26.dp)) }
                            Spacer(Modifier.height(12.dp))
                            Text("在左侧选择文章查看", fontSize = 13.sp, color = KbTextSub)
                        }
                    }
                    editing -> when {
                        wv2Ok == false -> PanelHint("缺少 WebView2 运行时：请安装微软 Edge WebView2 Evergreen Runtime 后重试")
                        else -> WebView(webState, Modifier.fillMaxSize())
                    }
                    detailError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(detailError ?: "", fontSize = 12.sp, color = KbTextSub, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { detailTick++ }) { Text("重试", fontSize = 12.sp) }
                        }
                    }
                    detailLoading -> PanelHint("加载中…")
                    d != null -> ArticleView(
                        api = api,
                        token = session.token,
                        server = session.server,
                        username = session.username,
                        doc = d,
                        onOpenDoc = { id ->
                            selected = DocSummary(
                                id = id, title = "", ownerName = "", updatedAt = "",
                                views = 0, category = null, snippet = null,
                            )
                        },
                        onEdit = { editing = true; editTarget = d.id },
                    )
                    else -> PanelHint("加载中…")
                }
            }
        }
    }
}

@Composable
private fun DocRow(d: DocSummary, sel: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (sel) KbSidebarSelected else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            d.title.ifBlank { "（无标题）" },
            fontSize = 13.sp,
            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
            color = KbTextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            listOfNotNull(
                d.ownerName.takeIf { it.isNotBlank() },
                fmtTime(d.updatedAt).takeIf { it.isNotBlank() },
                "${d.views} 浏览",
            ).joinToString(" · "),
            fontSize = 10.5.sp,
            color = KbTextSub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!d.snippet.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                d.snippet,
                fontSize = 11.sp,
                color = KbTextSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FilesPanel(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh) {
        loading = true
        error = null
        try {
            files = api.myFiles(session.token)
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("全部文件", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { refresh++ }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("刷新", fontSize = 11.sp)
            }
        }
        HorizontalDivider()
        Box(Modifier.weight(1f)) {
            when {
                loading -> PanelHint("加载中…")
                error != null -> Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(error ?: "", fontSize = 12.sp, color = KbTextSub, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { refresh++ }) { Text("重试", fontSize = 12.sp) }
                }
                files.isEmpty() -> PanelHint("暂无文件")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(files, key = { it.id }) { f ->
                        FileRow(session, f)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(session: Session, f: FileItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { openInBrowser(session.server, f.url) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(KbIconGradient(KbTileAmber.first, KbTileAmber.second)),
            contentAlignment = Alignment.Center,
        ) { MenuGlyph(MenuKind.FILES, Color.White, Modifier.size(17.dp)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                f.name.ifBlank { "（未命名）" },
                fontSize = 13.sp,
                color = KbTextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    humanSize(f.size).takeIf { it.isNotBlank() },
                    fmtTime(f.createdAt).takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                fontSize = 10.5.sp,
                color = KbTextSub,
                maxLines = 1,
            )
        }
        TextButton(onClick = { openInBrowser(session.server, f.url) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text("打开", fontSize = 11.sp)
        }
    }
}

private val BLANK_READER =
    "<html><head><meta charset=\"utf-8\"><style>html,body{margin:0;background:#fff}</style></head><body></body></html>"

/** JS 字符串字面量转义（token 注入 initScript 用）。 */
private fun jsStr(s: String): String =
    "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "\\r").replace("\n", "\\n") + "'"

/** "2026-08-14T12:34:56" → "2026-08-14 12:34" */
private fun fmtTime(raw: String): String = raw.replace("T", " ").take(16)

private fun humanSize(bytes: Long?): String {
    val b = bytes ?: return ""
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return String.format("%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}

private fun openInBrowser(server: String, url: String) {
    val full = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> server.trimEnd('/') + (if (url.startsWith("/")) url else "/$url")
    }
    runCatching { java.awt.Desktop.getDesktop().browse(URI(full)) }
}

private const val WV2_KEY = "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"

/** 检测系统 WebView2 运行时（Evergreen，查注册表 pv 值），Windows Server 不一定自带。 */
private fun hasWebView2Runtime(): Boolean {
    val roots = listOf(
        "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\EdgeUpdate\\Clients\\$WV2_KEY",
        "HKLM\\SOFTWARE\\Microsoft\\EdgeUpdate\\Clients\\$WV2_KEY",
        "HKCU\\Software\\Microsoft\\EdgeUpdate\\Clients\\$WV2_KEY",
    )
    for (root in roots) {
        val out = runCatching {
            ProcessBuilder("reg", "query", root, "/v", "pv")
                .redirectErrorStream(true)
                .start().inputStream.readBytes().toString(Charsets.UTF_8)
        }.getOrNull() ?: continue
        val v = Regex("pv\\s+REG_SZ\\s+(\\S+)").find(out)?.groupValues?.get(1)
        if (!v.isNullOrBlank() && v != "0.0.0.0") return true
    }
    return false
}

/** 菜单线框图标（与 Web 1.3.51 细线风格一致：1.5px 线 + 圆角端点） */
@Composable
fun MenuGlyph(kind: MenuKind, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.width / 24f
        val stroke = Stroke(width = 1.6f * u * 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), stroke.width, StrokeCap.Round)

        when (kind) {
            MenuKind.HOME -> {
                line(3f, 11f, 12f, 4f); line(12f, 4f, 21f, 11f)
                p.moveTo(6f, 9f); p.lineTo(6f, 20f); p.lineTo(18f, 20f); p.lineTo(18f, 9f)
                line(10.5f, 20f, 10.5f, 14.5f); line(10.5f, 14.5f, 13.5f, 14.5f); line(13.5f, 14.5f, 13.5f, 20f)
            }
            MenuKind.DOCS -> {
                p.moveTo(7f, 4f); p.lineTo(17f, 4f); p.lineTo(17f, 20f); p.lineTo(7f, 20f); p.close()
                line(10f, 9.5f, 14f, 9.5f); line(10f, 12.5f, 14f, 12.5f); line(10f, 15.5f, 13f, 15.5f)
            }
            MenuKind.CHAT -> {
                p.moveTo(4f, 5.5f); p.lineTo(20f, 5.5f); p.lineTo(20f, 15.5f); p.lineTo(11f, 15.5f)
                p.lineTo(7.5f, 19f); p.lineTo(7.5f, 15.5f); p.lineTo(4f, 15.5f); p.close()
                line(8.5f, 10.5f, 8.5f, 10.6f); line(12f, 10.5f, 12f, 10.6f); line(15.5f, 10.5f, 15.5f, 10.6f)
            }
            MenuKind.FILES -> {
                p.moveTo(3.5f, 6.5f); p.lineTo(9.5f, 6.5f); p.lineTo(11.5f, 8.5f); p.lineTo(20.5f, 8.5f)
                p.lineTo(20.5f, 18.5f); p.lineTo(3.5f, 18.5f); p.close()
            }
            MenuKind.SETTINGS -> {
                line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f)
                drawCircle(tint, 2.1f * u, Offset(14.5f * u, 7f * u), style = Stroke(stroke.width))
                drawCircle(tint, 2.1f * u, Offset(8.5f * u, 12f * u), style = Stroke(stroke.width))
                drawCircle(tint, 2.1f * u, Offset(16f * u, 17f * u), style = Stroke(stroke.width))
            }
        }
        if (!p.isEmpty) drawPath(p, tint, style = stroke)
    }
}

/** 侧栏品牌小图标（两笔书 + 架线） */
@Composable
private fun BrandGlyphMini(tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.width
        val path = Path()
        path.moveTo(s * 0.14f, s * 0.66f); path.lineTo(s * 0.40f, s * 0.30f); path.lineTo(s * 0.48f, s * 0.38f)
        path.moveTo(s * 0.32f, s * 0.84f); path.lineTo(s * 0.60f, s * 0.48f); path.lineTo(s * 0.70f, s * 0.58f)
        path.moveTo(s * 0.52f, s * 0.96f); path.lineTo(s * 0.78f, s * 0.66f); path.lineTo(s * 0.90f, s * 0.78f)
        drawPath(path, tint, style = Stroke(width = s * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(tint, Offset(s * 0.08f, s * 0.98f), Offset(s * 0.94f, s * 0.98f), s * 0.08f, StrokeCap.Round)
    }
}
