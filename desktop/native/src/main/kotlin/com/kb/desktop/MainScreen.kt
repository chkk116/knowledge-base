package com.kb.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import io.github.kdroidfilter.webview.web.WebContent
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.WebViewState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.awt.LocalAwtWindow
import java.awt.FileDialog
import java.awt.Frame
import java.net.URI

enum class MenuKind(val label: String) {
    HOME("首页"), DOCS("文章"), SHARES("共享空间"), COURSES("培训"), EXAMS("考试"),
    CHAT("AI 问答"), FILES("我的文件"), MAIL("邮箱"), NOTIFICATIONS("通知"), ADMIN("管理"), SETTINGS("设置")
}

/** 侧栏收成图标栏的宽度阈值（低于它窗口放不下完整侧栏+内容） */
private val RAIL_BREAK = 880.dp
private val KbRailBg = Color(0xFFFAFBFC)   // 侧栏浅底（与 ChatPanel 会话侧栏同色）

@Composable
fun MainScreen(session: Session, onLogout: () -> Unit) {
    var selected by remember { mutableStateOf(MenuKind.HOME) }
    var update by remember { mutableStateOf<AppVersionInfo?>(null) }
    // AI 问答里点「引用来源」→ 跳到文章面板并打开对应文章（DocsPanel 读一次后消费掉）
    var jumpDocId by remember { mutableStateOf<Long?>(null) }
    // 通知未读：60s 轮询（后端另有 SSE，桌面端用简单轮询即可，零额外依赖）
    var notiUnread by remember { mutableStateOf(0) }
    LaunchedEffect(session) {
        val api = ApiClient(session.server)
        while (true) {
            notiUnread = runCatching { api.unreadNotifications(session.token) }.getOrDefault(notiUnread)
            delay(60_000)
        }
    }

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
                notiUnread = notiUnread,
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
                // 2026-11-13：去掉外层 20dp + 白卡 1dp 边框（框套框冗余），内容区直接铺开，留白由各 Panel 自理
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Column(Modifier.fillMaxSize()) {
                        when (selected) {
                            MenuKind.HOME -> HomePanel(
                                session,
                                onNavigate = { selected = it },
                                onOpenDoc = { id ->
                                    jumpDocId = id
                                    selected = MenuKind.DOCS
                                },
                            )
                            MenuKind.DOCS -> DocsPanel(session, jumpDocId) { jumpDocId = null }
                            MenuKind.SHARES -> SharesPanel(session)
                            MenuKind.COURSES -> CoursesPanel(session)
                            MenuKind.EXAMS -> ExamsPanel(session)
                            MenuKind.FILES -> FilesPanel(session)
                            MenuKind.MAIL -> MailPanel(session)
                            MenuKind.ADMIN -> AdminPanel(session)
                            MenuKind.SETTINGS -> SettingsPanel(session, onLogout = onLogout)
                            MenuKind.NOTIFICATIONS -> NotificationsPanel(
                                session = session,
                                onOpenDoc = { id -> jumpDocId = id; selected = MenuKind.DOCS },
                                onUnreadChanged = { notiUnread = it },
                            )
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
    notiUnread: Int = 0,
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
                MenuItem(
                    kind,
                    sel = kind == selected,
                    rail = rail,
                    badge = if (kind == MenuKind.NOTIFICATIONS) notiUnread else 0,
                ) { onSelect(kind) }
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
private fun MenuItem(kind: MenuKind, sel: Boolean, rail: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val tile = when (kind) {
        MenuKind.HOME -> KbTileBlue
        MenuKind.DOCS -> KbTileViolet
        MenuKind.SHARES -> KbTileTeal
        MenuKind.COURSES -> KbTileGreen
        MenuKind.EXAMS -> KbTileRose
        MenuKind.CHAT -> KbTileGreen
        MenuKind.FILES -> KbTileAmber
        MenuKind.MAIL -> KbTileTeal
        MenuKind.NOTIFICATIONS -> KbTileRose
        MenuKind.ADMIN -> KbTileRose
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
            if (badge > 0) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(Color(0xFFF53F3F))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        if (badge > 99) "99+" else "$badge",
                        fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Medium,
                    )
                }
            }
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
private fun HomePanel(
    session: Session,
    onNavigate: (MenuKind) -> Unit,
    onOpenDoc: (Long) -> Unit,
) {
    val api = remember(session) { ApiClient(session.server) }
    var ov by remember { mutableStateOf<HomeOverview?>(null) }
    var notices by remember { mutableStateOf<List<AnnouncementItem>>(emptyList()) }
    var recent by remember { mutableStateOf<List<DocSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableStateOf(0) }

    // 首页各块独立降级：某一块取不到不拖垮整页（概览/公告失败就是空，不弹错）
    LaunchedEffect(refresh) {
        loading = true
        ov = runCatching { api.overview(session.token) }.getOrNull()
        notices = runCatching { api.announcements(session.token) }.getOrDefault(emptyList())
        recent = runCatching { api.docs(session.token, size = 6, sort = "latest").items }.getOrDefault(emptyList())
        loading = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${greeting()}，${session.username}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = KbTextMain,
                )
                Spacer(Modifier.height(4.dp))
                Text("从最近更新的文章继续，或者直接问 AI。", fontSize = 12.5.sp, color = KbTextSub)
            }
            TextButton(onClick = { refresh++ }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(if (loading) "刷新中…" else "刷新", fontSize = 11.5.sp)
            }
        }
        Spacer(Modifier.height(18.dp))

        // 概览：已发布文档 / 本周新增 / 成员（+ 管理员待审核）
        val o = ov
        Row(Modifier.fillMaxWidth()) {
            StatCard("已发布文章", o?.totalDocs ?: 0L, KbTileViolet, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            StatCard("本周新增", o?.weekNew ?: 0L, KbTileGreen, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            StatCard("在册成员", o?.members ?: 0L, KbTileBlue, Modifier.weight(1f))
            val pending = o?.pendingReview
            if (pending != null) {
                Spacer(Modifier.width(12.dp))
                StatCard("待审核", pending, KbTileAmber, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(18.dp))

        // 公告：只显示置顶优先的前两条，避免首页被公告挤满
        if (notices.isNotEmpty()) {
            notices.take(2).forEach { a ->
                AnnouncementCard(a)
                Spacer(Modifier.height(10.dp))
            }
        }

        SectionCard(title = "最近更新", action = "查看全部", onAction = { onNavigate(MenuKind.DOCS) }) {
            when {
                loading && recent.isEmpty() -> Text("加载中…", fontSize = 12.sp, color = KbTextSub)
                recent.isEmpty() -> Text("还没有已发布的文章", fontSize = 12.sp, color = KbTextSub)
                else -> recent.forEach { d ->
                    HomeDocRow(d) { onOpenDoc(d.id) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // 快捷入口
        Row(Modifier.fillMaxWidth()) {
            QuickCard("问 AI", "检索知识库并给出出处", KbTileGreen, Modifier.weight(1f)) {
                onNavigate(MenuKind.CHAT)
            }
            Spacer(Modifier.width(12.dp))
            QuickCard("全部文章", "浏览与新建文档", KbTileViolet, Modifier.weight(1f)) {
                onNavigate(MenuKind.DOCS)
            }
            Spacer(Modifier.width(12.dp))
            QuickCard("我的文件", "附件与上传记录", KbTileAmber, Modifier.weight(1f)) {
                onNavigate(MenuKind.FILES)
            }
        }
    }
}

/** 时段问候（与 Web 端首页口径一致：不出现具体钟点，只分时段）。 */
private fun greeting(): String {
    val h = java.time.LocalTime.now().hour
    return when {
        h < 6 -> "夜深了"
        h < 12 -> "早上好"
        h < 14 -> "中午好"
        h < 18 -> "下午好"
        else -> "晚上好"
    }
}

@Composable
private fun StatCard(label: String, value: Long, tile: Pair<Color, Color>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, KbBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(KbIconGradient(tile.first, tile.second)),
            )
            Spacer(Modifier.width(7.dp))
            Text(label, fontSize = 11.5.sp, color = KbTextSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Text(value.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
    }
}

@Composable
private fun AnnouncementCard(a: AnnouncementItem) {
    // 置顶/紧急用暖黄底提醒，普通通知保持白底卡（避免所有公告都像警告）
    val bg = if (a.pinned) Color(0xFFFFFBEB) else Color.White
    val borderColor = if (a.pinned) Color(0xFFFDE68A) else KbBorder
    val titleColor = if (a.pinned) Color(0xFF92400E) else KbTextMain
    val bodyColor = if (a.pinned) Color(0xFF92400E) else KbTextSub
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (a.pinned) {
                    Text(
                        "置顶",
                        fontSize = 9.5.sp,
                        color = Color(0xFFB45309),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFDE68A))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(a.title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val body = a.content.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            if (body.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(body, fontSize = 11.5.sp, color = bodyColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (a.createdAt.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(fmtTime(a.createdAt).take(10), fontSize = 10.sp, color = if (a.pinned) Color(0xFFB45309) else KbTextSub)
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, KbBorder, RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
            Spacer(Modifier.weight(1f))
            if (action != null && onAction != null) {
                Text(
                    action, fontSize = 11.sp, color = KbPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAction).padding(4.dp),
                )
            }
        }
        HorizontalDivider()
        Column(Modifier.padding(vertical = 6.dp)) { content() }
    }
}

@Composable
private fun HomeDocRow(d: DocSummary, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                d.title.ifBlank { "（无标题）" },
                fontSize = 12.5.sp,
                color = KbTextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(
                    d.category?.takeIf { it.isNotBlank() },
                    d.ownerName.takeIf { it.isNotBlank() },
                    fmtTime(d.updatedAt).takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                fontSize = 10.5.sp,
                color = KbTextSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("${d.views} 浏览", fontSize = 10.5.sp, color = KbTextSub)
    }
}

@Composable
private fun QuickCard(title: String, desc: String, tile: Pair<Color, Color>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, KbBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(KbIconGradient(tile.first, tile.second)),
            contentAlignment = Alignment.Center,
        ) {
            val kind = when (tile) {
                KbTileGreen -> MenuKind.CHAT
                KbTileAmber -> MenuKind.FILES
                else -> MenuKind.DOCS
            }
            MenuGlyph(kind, Color.White, Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 10.5.sp, color = KbTextSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** 通知类型 → 中文说明（后端 NotificationService 实际写入的类型） */
private fun notiTypeLabel(t: String) = when (t) {
    "COMMENT_MENTION" -> "评论中提到了你"
    "DOC_MENTION" -> "文档里提到了你"
    "COMMENT_REPLY" -> "回复了你的评论"
    "COMMENT" -> "新评论"
    "LIKE" -> "点赞"
    "DOC_UPDATE" -> "订阅的文档有更新"
    "REVIEW" -> "内容审核"
    "REVIEW_SUBMIT" -> "审核提交"
    "AVATAR_REVIEW_SUBMIT" -> "头像审核提交"
    "CERTIFICATE" -> "证书"
    "SECURITY_ALERT" -> "安全提醒"
    else -> if (t.isBlank()) "通知" else t
}

/** 站内通知：未读高亮 + 点按已读并跳文章 + 全部已读 + 删除 */
@Composable
private fun NotificationsPanel(
    session: Session,
    onOpenDoc: (Long) -> Unit = {},
    onUnreadChanged: (Int) -> Unit = {},
) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<ApiClient.NotiItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    fun report() = onUnreadChanged(items.count { !it.read })

    LaunchedEffect(tick) {
        loading = true
        err = null
        try {
            items = api.notifications(session.token)
            report()
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("通知", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
            val unreadCount = items.count { !it.read }
            if (unreadCount > 0) {
                Spacer(Modifier.width(8.dp))
                Text("$unreadCount 条未读", fontSize = 12.sp, color = Color(0xFFB45309))
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = unreadCount > 0,
                onClick = {
                    scope.launch {
                        runCatching { api.markAllNotificationsRead(session.token) }
                        items = items.map { it.copy(read = true) }
                        report()
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("全部已读", fontSize = 12.5.sp) }
            TextButton(onClick = { tick++ }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("刷新", fontSize = 12.5.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading && items.isEmpty() -> Text("加载中…", fontSize = 12.5.sp, color = KbTextSub)
            err != null -> Text(err ?: "", fontSize = 12.5.sp, color = Color(0xFFDC2626))
            items.isEmpty() -> Text(
                "暂无通知：有人评论、回复或 @ 你时会出现在这里",
                fontSize = 12.5.sp, color = KbTextSub,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items.size) { i ->
                    val n = items[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (n.read) Color.White else Color(0xFFEFF6FF))
                            .border(1.dp, KbBorder, RoundedCornerShape(10.dp))
                            .clickable {
                                if (!n.read) {
                                    items = items.toMutableList().also { it[i] = n.copy(read = true) }
                                    report()
                                    scope.launch {
                                        runCatching { api.markNotificationRead(session.token, n.id) }
                                    }
                                }
                                // 文章类通知直接跳到那篇文章；共享文档类去共享空间
                                val docId = n.docId
                                if (docId != null && n.shareId == null) onOpenDoc(docId)
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                buildString {
                                    append(notiTypeLabel(n.type))
                                    if (n.actor.isNotBlank()) append(" · ").append(n.actor)
                                },
                                fontSize = 11.5.sp, color = KbTextSub,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                n.summary.ifBlank { "（无摘要）" },
                                fontSize = 13.sp,
                                color = KbTextMain,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                n.createdAt.replace('T', ' ').take(16),
                                fontSize = 11.sp, color = KbTextSub,
                            )
                        }
                        if (!n.read) {
                            Text("未读", fontSize = 10.5.sp, color = Color(0xFF2563EB))
                        }
                        TextButton(
                            onClick = {
                                items = items.filterNot { it.id == n.id }
                                report()
                                scope.launch {
                                    runCatching { api.deleteNotification(session.token, n.id) }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text("删除", fontSize = 11.5.sp, color = Color(0xFFDC2626)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(session: Session, onLogout: () -> Unit = {}) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var remote by remember { mutableStateOf<AppVersionInfo?>(null) }
    // 本地数据：图片缓存条数（内存态）与操作结果提示
    var imgCacheCount by remember { mutableStateOf(docImageCacheSize()) }
    var localMsg by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(26.dp)) {
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

        Spacer(Modifier.height(16.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, KbBorder, RoundedCornerShape(12.dp))
                .padding(20.dp),
        ) {
            Text("账号", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
            Spacer(Modifier.height(12.dp))
            SettingRow("服务器", session.server)
            SettingRow("登录账号", session.username)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onLogout() },
                    shape = RoundedCornerShape(9.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF3F4F6), contentColor = KbTextMain,
                    ),
                ) { Text("退出登录", fontSize = 13.sp) }
            }
        }

        Spacer(Modifier.height(16.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, KbBorder, RoundedCornerShape(12.dp))
                .padding(20.dp),
        ) {
            Text("本地数据", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
            Spacer(Modifier.height(12.dp))
            SettingRow("正文图片缓存", "$imgCacheCount 张（内存，重启清空）")
            SettingRow("配置目录", ConfigStore.dirPath().ifBlank { "（未创建）" })
            Spacer(Modifier.height(4.dp))
            Text(
                "只清本机缓存与配置，不动服务器上的任何内容；token 不落盘，退出登录即失效。",
                fontSize = 11.5.sp, color = KbTextSub,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val n = clearDocImageCache()
                        imgCacheCount = docImageCacheSize()
                        localMsg = "已清空 $n 张图片缓存"
                    },
                    shape = RoundedCornerShape(9.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF3F4F6), contentColor = KbTextMain,
                    ),
                ) { Text("清空图片缓存", fontSize = 13.sp) }

                Button(
                    onClick = {
                        val dir = ConfigStore.dirPath()
                        if (dir.isNotBlank()) {
                            runCatching { java.awt.Desktop.getDesktop().open(java.io.File(dir)) }
                                .onFailure { localMsg = "打开目录失败：${it.message}" }
                        } else localMsg = "配置目录尚未创建"
                    },
                    shape = RoundedCornerShape(9.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF3F4F6), contentColor = KbTextMain,
                    ),
                ) { Text("打开配置目录", fontSize = 13.sp) }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    val ok = ConfigStore.clear()
                    localMsg = if (ok) "本地配置已清除" else "清除失败（文件被占用？）"
                    onLogout()
                },
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) { Text("清除本地配置并退出", fontSize = 12.5.sp, color = Color(0xFFDC2626)) }
            if (localMsg.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(localMsg, fontSize = 12.sp, color = Color(0xFF059669))
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
    val scope = rememberCoroutineScope()
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
    // 列表侧：搜索（输入即搜，350ms 防抖）与排序切换
    var q by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("latest") }
    var results by remember { mutableStateOf<List<DocSummary>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    // 列表模式：全部文章 / 我的订阅（订阅列表来自 GET /api/docs/subscriptions）
    var mode by remember { mutableStateOf("all") }
    var subs by remember { mutableStateOf<List<SubscriptionItem>>(emptyList()) }
    var subLoading by remember { mutableStateOf(false) }
    var subErr by remember { mutableStateOf<String?>(null) }
    // 作者视角：我的帖子（草稿提交审核 / 待审撤回 / 删除）
    var mineItems by remember { mutableStateOf<List<AdminDocItem>>(emptyList()) }
    var mineLoading by remember { mutableStateOf(false) }
    var mineErr by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mode, refresh) {
        if (mode == "sub") {
            subLoading = true
            subErr = null
            try {
                subs = api.mySubscriptions(session.token)
            } catch (e: Exception) {
                subErr = e.message ?: "加载失败"
            } finally {
                subLoading = false
            }
            return@LaunchedEffect
        }
        if (mode == "mine") {
            mineLoading = true
            mineErr = null
            try {
                mineItems = api.myOwnDocs(session.token)
            } catch (e: Exception) {
                mineErr = e.message ?: "加载失败"
            } finally {
                mineLoading = false
            }
        }
    }

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

    LaunchedEffect(refresh, sort) {
        loading = true
        error = null
        try {
            val page = api.docs(session.token, sort = sort)
            docs = page.items
            total = page.total
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    // 搜索：清空输入即回到服务端分页列表；失败保留上一次结果并给出小提示，不替掉整个列表
    LaunchedEffect(q) {
        val query = q.trim()
        if (query.isEmpty()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(350)
        try {
            results = api.searchDocs(session.token, query).map { d ->
                DocSummary(
                    id = d.id, title = d.title, ownerName = "", updatedAt = "",
                    views = 0, category = null, snippet = d.snippet,
                )
            }
            error = null
        } catch (e: Exception) {
            error = e.message ?: "搜索失败"
        } finally {
            searching = false
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
        // 左：文章列表（浅底侧栏，与 ChatPanel 会话侧栏同款；与右侧已有 1dp 分隔线）
        Column(Modifier.width(280.dp).fillMaxHeight().background(KbRailBg)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (mode) {
                        "sub" -> "我的订阅"
                        "mine" -> "我的帖子"
                        else -> "文章"
                    },
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain
                )
                val count = when {
                    mode == "sub" -> subs.size.toLong()
                    mode == "mine" -> mineItems.size.toLong()
                    q.trim().isNotEmpty() -> results.size.toLong()
                    else -> total
                }
                if (count > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("$count", fontSize = 10.5.sp, color = KbTextSub)
                }
                Spacer(Modifier.weight(1f))
                if (mode == "all") {
                    TextButton(onClick = { editing = true; editTarget = null }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("新建", fontSize = 11.sp)
                    }
                }
                TextButton(onClick = { refresh++ }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("刷新", fontSize = 11.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeChip("全部", mode == "all") { mode = "all" }
                Spacer(Modifier.width(6.dp))
                ModeChip("订阅", mode == "sub") { mode = "sub" }
                Spacer(Modifier.width(6.dp))
                ModeChip("我的", mode == "mine") { mode = "mine" }
                Spacer(Modifier.weight(1f))
                if (mode == "all" && searching) Text("搜索中…", fontSize = 10.sp, color = KbTextSub)
            }
            if (mode == "all") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchBox(q, { q = it }, Modifier.weight(1f))
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SortChip("最新", sort == "latest") { sort = "latest" }
                    Spacer(Modifier.width(6.dp))
                    SortChip("最热", sort == "hot") { sort = "hot" }
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
            Box(Modifier.weight(1f)) {
                val inSub = mode == "sub"
                val inMine = mode == "mine"
                val shown = if (q.trim().isNotEmpty()) results else docs
                when {
                    inMine && mineLoading -> PanelHint("加载中…")
                    inMine && mineErr != null -> Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(mineErr ?: "", fontSize = 12.sp, color = KbTextSub, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { refresh++ }) { Text("重试", fontSize = 12.sp) }
                    }
                    inMine && mineItems.isEmpty() -> PanelHint("还没有写过文章\n可点上方「新建」在 Web 端编辑器里写")
                    inMine -> LazyColumn(Modifier.fillMaxSize()) {
                        items(mineItems, key = { it.id }) { m ->
                            MineRow(
                                item = m,
                                sel = selected?.id == m.id,
                                onOpen = {
                                    if (m.status == "PUBLISHED" || m.status == "BLOCKED") {
                                        selected = DocSummary(
                                            id = m.id, title = m.title, ownerName = m.ownerName,
                                            updatedAt = m.updatedAt, views = m.views,
                                            category = m.category, snippet = null,
                                        )
                                    }
                                },
                                onSubmit = {
                                    scope.launch {
                                        runCatching { api.submitDocReview(session.token, m.id) }
                                            .onFailure { detailError = it.message ?: "提交失败" }
                                        refresh++
                                    }
                                },
                                onWithdraw = {
                                    scope.launch {
                                        runCatching { api.withdrawDocReview(session.token, m.id) }
                                            .onFailure { detailError = it.message ?: "撤回失败" }
                                        refresh++
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        runCatching { api.deleteOwnDoc(session.token, m.id) }
                                            .onFailure { detailError = it.message ?: "删除失败" }
                                        refresh++
                                    }
                                },
                            )
                        }
                    }
                    inSub && subLoading -> PanelHint("加载中…")
                    inSub && subErr != null -> Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(subErr ?: "", fontSize = 12.sp, color = KbTextSub, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { refresh++ }) { Text("重试", fontSize = 12.sp) }
                    }
                    inSub && subs.isEmpty() -> PanelHint("还没有订阅任何文章\n在文章页左侧点「订阅」")
                    inSub -> LazyColumn(Modifier.fillMaxSize()) {
                        items(subs, key = { it.docId }) { s ->
                            SubRow(s, sel = selected?.id == s.docId && s.docId != 0L) {
                                if (!s.deleted) {
                                    selected = DocSummary(
                                        id = s.docId, title = s.title, ownerName = "", updatedAt = "",
                                        views = 0, category = s.category, snippet = null,
                                    )
                                }
                            }
                        }
                    }
                    loading && q.trim().isEmpty() -> PanelHint("加载中…")
                    error != null && shown.isEmpty() -> Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(error ?: "", fontSize = 12.sp, color = KbTextSub, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { refresh++ }) { Text("重试", fontSize = 12.sp) }
                    }
                    shown.isEmpty() -> PanelHint(if (q.trim().isNotEmpty()) "没有匹配的文章" else "暂无文章")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.id }) { d ->
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
internal fun SearchBox(
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索标题或正文",
) {
    Row(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(1.dp, KbBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(12.dp)) {
            val r = size.minDimension * 0.30f
            drawCircle(
                color = KbTextSub,
                radius = r,
                center = Offset(size.width * 0.44f, size.height * 0.44f),
                style = Stroke(width = 1.4f),
            )
            drawLine(
                KbTextSub,
                Offset(size.width * 0.66f, size.height * 0.66f),
                Offset(size.width * 0.94f, size.height * 0.94f),
                strokeWidth = 1.4f,
                cap = StrokeCap.Round,
            )
        }
        Spacer(Modifier.width(7.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, fontSize = 11.5.sp, color = KbTextSub)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = TextStyle(fontSize = 11.5.sp, color = KbTextMain),
                cursorBrush = SolidColor(KbPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Text(
                "×",
                fontSize = 15.sp,
                color = KbTextSub,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onValue("") }
                    .padding(start = 6.dp),
            )
        }
    }
}

@Composable
internal fun SortChip(label: String, sel: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 10.5.sp,
        color = if (sel) KbPrimary else KbTextSub,
        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (sel) KbSidebarSelected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun ModeChip(label: String, sel: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        color = if (sel) Color.White else KbTextSub,
        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (sel) KbPrimary else Color.Transparent)
            .border(1.dp, if (sel) KbPrimary else KbBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

@Composable
private fun SubRow(s: SubscriptionItem, sel: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !s.deleted, onClick = onClick)
            .background(if (sel) KbSidebarSelected else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            s.title.ifBlank { "（无标题）" },
            fontSize = 13.sp,
            fontWeight = if (s.deleted) FontWeight.Normal else if (sel) FontWeight.SemiBold else FontWeight.Medium,
            color = if (s.deleted) Color(0xFF9CA3AF) else KbTextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            listOfNotNull(
                s.category?.takeIf { it.isNotBlank() },
                "订阅于 ${fmtTime(s.subscribedAt)}".takeIf { s.subscribedAt.isNotBlank() },
                "文档已删除".takeIf { s.deleted },
            ).joinToString(" · ").ifBlank { "已订阅" },
            fontSize = 10.5.sp,
            color = KbTextSub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 我的帖子行（作者视角）：状态徽标 + 草稿提交审核 / 待审撤回 / 删除 */
@Composable
private fun MineRow(
    item: AdminDocItem,
    sel: Boolean,
    onOpen: () -> Unit,
    onSubmit: () -> Unit,
    onWithdraw: () -> Unit,
    onDelete: () -> Unit,
) {
    val (statusText, statusColor) = when (item.status) {
        "PUBLISHED" -> "已发布" to Color(0xFF059669)
        "PENDING" -> "待审核" to Color(0xFFB45309)
        "BLOCKED" -> "已封禁" to Color(0xFFDC2626)
        "DRAFT" -> "草稿" to Color(0xFF6B7280)
        else -> (item.status.ifBlank { "—" }) to Color(0xFF6B7280)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .background(if (sel) KbSidebarSelected else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.title.ifBlank { "（无标题）" },
                fontSize = 12.5.sp,
                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                color = KbTextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(statusText, fontSize = 10.sp, color = statusColor)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            listOfNotNull(
                item.updatedAt.replace('T', ' ').take(16).takeIf { it.isNotBlank() }?.let { "更新 $it" },
                "${item.views} 阅",
            ).joinToString(" · "),
            fontSize = 10.5.sp,
            color = KbTextSub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (item.status == "DRAFT") {
                TextButton(onClick = onSubmit, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("提交审核", fontSize = 10.5.sp)
                }
            }
            if (item.status == "PENDING") {
                TextButton(onClick = onWithdraw, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("撤回", fontSize = 10.5.sp)
                }
            }
            TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("删除", fontSize = 10.5.sp, color = Color(0xFFDC2626))
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
                // 搜索结果不带浏览量，0 浏览不显示（避免搜索列表满屏「0 浏览」）
                "${d.views} 浏览".takeIf { d.views > 0 },
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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun FilesPanel(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    val awtFrame = LocalAwtWindow.current as? Frame
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var uploading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var pendingDel by remember { mutableStateOf<Long?>(null) }
    // 右侧预览选中项
    var sel by remember { mutableStateOf<FileItem?>(null) }

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

    // 上传：系统文件选择器（模态）→ multipart POST /api/files/mine
    fun pickAndUpload() {
        val frame = awtFrame ?: run { msg = "无法打开文件选择器"; return }
        val fd = FileDialog(frame, "选择要上传的文件", FileDialog.LOAD)
        fd.isMultipleMode = false
        fd.isVisible = true
        val f = fd.files.firstOrNull() ?: return
        // 后端只收图片 / 音频 / 视频 / PDF（FileService.ALLOWED + 魔数嗅探），
        // 这里先按扩展名拦一道并直接给出 MIME：guessContentTypeFromName 对部分扩展名会返回空，
        // 落成 octet-stream 会被后端拒，用户只看到一句莫名其妙的失败。
        val ext = f.extension.lowercase()
        val mime = UPLOAD_EXT_MIME[ext]
        if (mime == null) {
            msg = "仅支持图片 / 音频 / 视频 / PDF（单个 ≤ 50MB）"
            return
        }
        uploading = true
        msg = ""
        scope.launch {
            try {
                api.uploadMyFile(session.token, f, mime)
                msg = "上传成功：${f.name}"
                refresh++
            } catch (e: Exception) {
                msg = e.message ?: "上传失败"
            } finally {
                uploading = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("全部文件", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = !uploading,
                onClick = { pickAndUpload() },
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) { Text(if (uploading) "上传中…" else "上传", fontSize = 11.sp) }
            TextButton(onClick = { refresh++ }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("刷新", fontSize = 11.sp)
            }
        }
        if (msg.isNotEmpty()) {
            Text(
                msg, fontSize = 11.sp, color = KbPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider()
        Row(Modifier.weight(1f)) {
            // 左：文件列表（点一下即选中并在右侧预览）
            Column(Modifier.width(320.dp).fillMaxHeight()) {
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
                        FileRow(
                            session = session,
                            f = f,
                            selected = sel?.id == f.id,
                            onSelect = { sel = f },
                            confirming = pendingDel == f.id,
                            onCopy = {
                                runCatching {
                                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                        java.awt.datatransfer.StringSelection(f.url), null,
                                    )
                                    msg = "已复制文件链接"
                                }.onFailure { msg = "复制失败：${it.message}" }
                            },
                            onDelete = {
                                if (pendingDel == f.id) {
                                    pendingDel = null
                                    scope.launch {
                                        runCatching { api.deleteMyFile(session.token, f.id) }
                                            .onSuccess { msg = "已删除：${f.name}"; refresh++ }
                                            .onFailure { msg = it.message ?: "删除失败" }
                                    }
                                } else {
                                    pendingDel = f.id   // 第一次点只进入「确认删除?」，再点才真正删
                                }
                            },
                        )
                    }
                }
            }
        }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(KbBorder))
            // 右：预览（图片原生渲染，PDF / 文本 / 其它交给 WebView）
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val cur = sel
                if (cur == null) PanelHint("选择一个文件查看预览")
                else FilePreview(session, cur)
            }
        }
    }
}

@Composable
private fun FileRow(
    session: Session,
    f: FileItem,
    selected: Boolean = false,
    onSelect: () -> Unit = {},
    confirming: Boolean = false,
    onCopy: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .background(if (selected) KbSidebarSelected else Color.Transparent)
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
        TextButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text("复制", fontSize = 11.sp)
        }
        TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(
                if (confirming) "确认删除?" else "删除",
                fontSize = 11.sp,
                color = if (confirming) Color(0xFFDC2626) else KbTextSub,
            )
        }
    }
}

private val BLANK_READER =
    "<html><head><meta charset=\"utf-8\"><style>html,body{margin:0;background:#fff}</style></head><body></body></html>"

/** 上传白名单：扩展名 → MIME（与后端 FileService.ALLOWED 对齐，兼做本地拦截与类型推断） */
private val UPLOAD_EXT_MIME = mapOf(
    "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
    "webp" to "image/webp", "bmp" to "image/bmp",
    "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "m4a" to "audio/mp4",
    "flac" to "audio/flac", "aac" to "audio/aac",
    "mp4" to "video/mp4", "webm" to "video/webm",
    "pdf" to "application/pdf",
)

/** 预览判定：图片原生渲染更清晰，其余（PDF / 文本 / 压缩包）交给 WebView 或系统 */
private fun isImageFile(f: FileItem): Boolean {
    val ct = f.contentType.orEmpty().lowercase()
    if (ct.startsWith("image/")) return true
    val ext = f.name.substringAfterLast('.', "").lowercase()
    return ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
}

@Composable
private fun FilePreview(session: Session, f: FileItem) {
    if (isImageFile(f)) {
        DocImage(f.url, session.server, Modifier.fillMaxSize().padding(12.dp))
    } else {
        val full = session.server.trimEnd('/') + f.url
        val st = remember(full) { WebViewState(WebContent.Url(full)) }
        WebView(st, Modifier.fillMaxSize())
    }
}

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

internal fun openInBrowser(server: String, url: String) {
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
            MenuKind.SHARES -> {
                // 两页重叠的文档（共享/协作语义）
                p.moveTo(4f, 9f); p.lineTo(4f, 20f); p.lineTo(13f, 20f); p.lineTo(13f, 9f); p.close()
                p.moveTo(9f, 4f); p.lineTo(19f, 4f); p.lineTo(19f, 15.5f); p.lineTo(15.5f, 15.5f)
                line(6.5f, 13f, 10.5f, 13f); line(6.5f, 16.5f, 10.5f, 16.5f)
            }
            MenuKind.EXAMS -> {
                // 试卷 + 勾选
                p.moveTo(6f, 3.5f); p.lineTo(18f, 3.5f); p.lineTo(18f, 20.5f); p.lineTo(6f, 20.5f); p.close()
                line(9f, 12f, 11f, 14.5f); line(11f, 14.5f, 15.5f, 9f)
            }
            MenuKind.COURSES -> {
                // 打开的书（培训）
                p.moveTo(12f, 6.5f); p.lineTo(12f, 19f)
                p.moveTo(12f, 7f); p.lineTo(5f, 5.5f); p.lineTo(5f, 17.5f); p.lineTo(12f, 19f)
                p.moveTo(12f, 7f); p.lineTo(19f, 5.5f); p.lineTo(19f, 17.5f); p.lineTo(12f, 19f)
            }
            MenuKind.ADMIN -> {
                // 盾牌 + 勾（管理）
                p.moveTo(12f, 3.5f); p.lineTo(19.5f, 6.5f); p.lineTo(19.5f, 12.5f)
                p.lineTo(16f, 17f); p.lineTo(12f, 20f); p.lineTo(8f, 17f); p.lineTo(4.5f, 12.5f)
                p.lineTo(4.5f, 6.5f); p.close()
                line(9f, 11.5f, 11f, 13.5f); line(11f, 13.5f, 15f, 9f)
            }
            MenuKind.SETTINGS -> {
                line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f)
                drawCircle(tint, 2.1f * u, Offset(14.5f * u, 7f * u), style = Stroke(stroke.width))
                drawCircle(tint, 2.1f * u, Offset(8.5f * u, 12f * u), style = Stroke(stroke.width))
                drawCircle(tint, 2.1f * u, Offset(16f * u, 17f * u), style = Stroke(stroke.width))
            }
            MenuKind.NOTIFICATIONS -> {
                // 铃铛：提手 + 铃身折线 + 底座 + 铃舌
                line(12f, 4f, 12f, 6f)
                line(8f, 6.5f, 6.2f, 9f); line(6.2f, 9f, 6.2f, 15f); line(6.2f, 15f, 5f, 17.5f)
                line(5f, 17.5f, 19f, 17.5f); line(19f, 17.5f, 17.8f, 15f); line(17.8f, 15f, 17.8f, 9f)
                line(17.8f, 9f, 16f, 6.5f); line(16f, 6.5f, 8f, 6.5f)
                line(10f, 20f, 14f, 20f)
            }
            MenuKind.MAIL -> {
                // 信封：外框 + 折角
                p.moveTo(4f, 6.5f); p.lineTo(20f, 6.5f); p.lineTo(20f, 17.5f); p.lineTo(4f, 17.5f); p.close()
                line(4f, 7f, 12f, 13f); line(12f, 13f, 20f, 7f)
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
