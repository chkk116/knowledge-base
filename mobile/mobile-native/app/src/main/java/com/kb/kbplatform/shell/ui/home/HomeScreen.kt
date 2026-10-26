/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Style
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.kb.kbplatform.shell.ui.theme.KbToneKind
import com.kb.kbplatform.shell.ui.theme.kbIconGradient
import com.kb.kbplatform.shell.ui.theme.kbTone
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.TokenStore
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.ShareInbox
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.KbEmpty
import com.kb.kbplatform.shell.ui.common.KbSkeletonHome
import com.kb.kbplatform.shell.ui.common.kbItemEnter
import com.kb.kbplatform.shell.ui.common.kbPress
import com.kb.kbplatform.shell.ui.common.formatDateTime
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * 金刚区入口：图标用 Material 线性图标 + 浅色底瓦片，对齐套壳 `.q-ico.*`。
 * 原来用 emoji（📷✏️🔗…），和网页端的单色线性图标观感差得远。
 */
private data class Quick(
    val label: String,
    val icon: ImageVector,
    val tone: KbToneKind,
    val route: String?
)

/**
 * 首页 · 严格对标 MobileHome.vue：
 * 蓝色 hero（问候+姓名+头像 / 搜索条+AI 按钮）→ 分享/待复习提示卡 → 快捷宫格 →
 * 四联统计 → 公告条 → 最近文档横滑 → 空间列表。
 */
@Composable
fun HomeScreen(
    openWeb: (path: String, title: String) -> Unit,
    openRoute: (String) -> Unit,
    /**
     * 切主框架的 Tab（0 首页 / 1 文档库 / 2 消息 / 3 我的）。
     *
     * 「我的」「文档库」是 Tab 页，**不是 NavHost 路由**——原来头像写 openRoute("profile")、
     * 空间行写 openRoute("docs")，点下去直接抛
     * `Navigation destination that matches route profile cannot be found` 闪退。
     */
    switchTab: (Int) -> Unit = {}
) {
    var overview by remember { mutableStateOf<JSONObject?>(null) }
    var announcement by remember { mutableStateOf<JSONObject?>(null) }
    var dueCards by remember { mutableIntStateOf(0) }
    var recent by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var spaces by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val sharePending by ShareInbox.pending.collectAsState()
    val draftCtx = androidx.compose.ui.platform.LocalContext.current
    var draftCount by remember { mutableIntStateOf(0) }
    // 公告详情：原生弹窗展示，不再跳 /m/（原来点公告会整页打开网页版首页，很突兀）
    var annDetail by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) { draftCount = com.kb.kbplatform.shell.data.DraftStore.list(draftCtx).size }

    LaunchedEffect(Unit) {
        loading = true
        error = ""
        try {
            coroutineScope {
                val ov = async { runCatching { KbApi.overview() }.getOrNull() }
                val an = async { runCatching { KbApi.announcements().objects() }.getOrDefault(emptyList()) }
                val due = async { runCatching { KbApi.dueCards().objects().size }.getOrDefault(0) }
                val all = async { runCatching { KbApi.allShareDocs().objects() }.getOrDefault(emptyList()) }
                val sp = async { runCatching { KbApi.myShares().objects() }.getOrDefault(emptyList()) }
                overview = ov.await()
                announcement = an.await().firstOrNull()
                dueCards = due.await()
                recent = all.await().sortedByDescending { it.optString("updatedAt") }.take(10)
                spaces = sp.await()
            }
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Hero(onAvatar = { switchTab(3) }, onSearch = { openRoute("search") }, onAi = { openRoute("ai") })

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loading) {
                // 骨架屏：先把问候语/九宫格/统计条的版式画出来，数据到了直接填（原来是一个转圈）
                item { KbSkeletonHome() }
            } else if (error.isNotEmpty()) {
                item { KbEmpty("⚠️", error) }
            } else {
                if (sharePending.isNotEmpty()) {
                    item {
                        NoticeCard(
                            Icons.Filled.CloudUpload, KbToneKind.Blue,
                            "收到 ${sharePending.size} 个分享文件", "点击导入到知识库"
                        ) { openRoute("share-import") }
                    }
                }
                if (draftCount > 0) {
                    item {
                        NoticeCard(
                            Icons.Filled.CloudUpload, KbToneKind.Orange,
                            "$draftCount 篇草稿待同步", "联网后会自动上传到「我的文档」",
                            onClick = { openRoute("drafts") }
                        )
                    }
                }
                if (dueCards > 0) {
                    item {
                        NoticeCard(
                            Icons.Filled.Style, KbToneKind.Orange,
                            "$dueCards 张卡片待复习", "花两分钟过一遍，别让记过的忘掉"
                        ) { openRoute("cards") }
                    }
                }
                item {
                    // 金刚区（对齐套壳 .quicks）：**不放在卡片里**，直接铺在页面底色上，
                    // 4 列、间距 10、瓦片 52dp/圆角 16、图标 23dp 单色、文字 12sp
                    // 12 个入口 / 3 行，与套壳首页的金刚区同密度（那边是 14 个）
                    val quicks = listOf(
                        Quick("拍照建文档", Icons.Filled.PhotoCamera, KbToneKind.Primary, "capture"),
                        Quick("速记", Icons.Filled.EditNote, KbToneKind.Cyan, "quick"),
                        Quick("网页剪藏", Icons.Filled.Link, KbToneKind.Blue, "clip"),
                        Quick("复习卡片", Icons.Filled.Style, KbToneKind.Orange, "cards"),
                        Quick("我的文档", Icons.Filled.FolderOpen, KbToneKind.Blue, "all-docs"),
                        Quick("AI 问答", Icons.Filled.AutoAwesome, KbToneKind.Cyan, "ai"),
                        Quick("发现文章", Icons.Filled.Explore, KbToneKind.Green, "discover"),
                        Quick("搜索", Icons.Filled.Search, KbToneKind.Gray, "search"),
                        Quick("我的收藏", Icons.Filled.Star, KbToneKind.Orange, "favorites"),
                        Quick("课程", Icons.Filled.MenuBook, KbToneKind.Blue, "courses"),
                        Quick("考试", Icons.Filled.EmojiEvents, KbToneKind.Green, "exams"),
                        Quick("我的数据", Icons.Filled.Insights, KbToneKind.Red, "stats")
                    )
                    Column(
                        Modifier.padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        quicks.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { q ->
                                    QuickTile(q, Modifier.weight(1f)) { openRoute(q.route!!) }
                                }
                            }
                        }
                    }
                }
                item {
                    // 数据概览（对齐套壳 .ovw）：白卡 + 竖分割线，整块可点进「我的数据」
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .kbPress(scaleDown = 0.99f) { openRoute("stats") }
                            .padding(horizontal = 6.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Stat(overview?.optLong("totalDocs", -1) ?: -1, "文档", Modifier.weight(1f))
                        StatDivider()
                        Stat(overview?.optLong("weekNew", -1) ?: -1, "本周新增", Modifier.weight(1f))
                        StatDivider()
                        Stat(-1, "连续天数", Modifier.weight(1f))
                        StatDivider()
                        Stat(-1, "本周阅读", Modifier.weight(1f))
                    }
                }
                announcement?.let { a ->
                    item {
                        // 公告（对齐套壳 .ann）：主色浅底 + 主色图标，标题 13.5 粗、正文 12 两行截断
                        val tone = kbTone(KbToneKind.Primary)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(tone.bg)
                                .kbPress(scaleDown = 0.99f) { annDetail = a }
                                .padding(horizontal = 13.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Campaign, null,
                                tint = tone.fg, modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    a.optString("title").ifBlank { "公告" },
                                    fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    a.optString("content"),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                Icons.Filled.ChevronRight, null,
                                tint = KbTextDim, modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                if (recent.isNotEmpty()) {
                    item {
                        SectionTitle("最近文档")
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(recent, key = { it.optLong("id", it.hashCode().toLong()) }) { d ->
                                val docId = if (d.has("docId")) d.optLong("docId") else d.optLong("id", 0)
                                KbCard(
                                    Modifier
                                        .size(width = 168.dp, height = 104.dp)
                                        .clickable {
                                            openRoute(
                                                "reader/${d.optLong("shareId", 0)}/$docId?title=" +
                                                    android.net.Uri.encode(d.optString("title"))
                                            )
                                        }
                                ) {
                                    Column(Modifier.padding(13.dp)) {
                                        val tagTone = kbTone(KbToneKind.Primary)
                                        Text(
                                            d.optString("spaceName").ifBlank { "空间" },
                                            fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                                            color = tagTone.fg,
                                            modifier = Modifier
                                                .background(tagTone.bg, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 6.dp, vertical = 1.dp),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            d.optString("title").ifBlank { "未命名文档" },
                                            fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            formatDateTime(d.optString("updatedAt")),
                                            fontSize = 10.sp, color = KbTextDim
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (spaces.isNotEmpty()) {
                    item {
                        SectionTitle("我的空间")
                        Spacer(Modifier.height(8.dp))
                        KbCard {
                            spaces.take(5).forEach { s ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { switchTab(1) }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier
                                            .size(34.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            s.optString("name").ifBlank { "空" }.take(1),
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold, fontSize = 14.sp
                                        )
                                    }
                                    Spacer(Modifier.size(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(s.optString("name").ifBlank { "未命名空间" }, fontSize = 14.sp)
                                        Text(
                                            s.optString("type") + " · " + s.optLong("memberCount", 0) + " 名成员",
                                            fontSize = 11.sp, color = KbTextDim
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    annDetail?.let { a ->
        AlertDialog(
            onDismissRequest = { annDetail = null },
            title = {
                Text(
                    a.optString("title").ifBlank { "公告" },
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    a.optString("content").ifBlank { "（无正文）" },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { annDetail = null }) { Text("知道了") }
            }
        )
    }
}

private fun greet(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..8 -> "早上好"
        in 9..11 -> "上午好"
        in 12..13 -> "中午好"
        in 14..17 -> "下午好"
        else -> "晚上好"
    }
}

@Composable
private fun Hero(onAvatar: () -> Unit, onSearch: () -> Unit, onAi: () -> Unit) {
    val me = TokenStore.info.value
    Column(
        Modifier
            .fillMaxWidth()
            // 对齐套壳 .hero：**纯色主色**，不是渐变（网页端明确写了「不渐变、不用紫」）
            .background(
                MaterialTheme.colorScheme.primary,
                RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
            )
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 18.dp)
            // 首屏 Hero 淡入并自上轻落，避免整页「瞬间出现」
            .kbItemEnter(index = 0, fromBottom = -10)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greet(), fontSize = 13.sp, color = Color.White.copy(alpha = 0.82f))
                Text(
                    me?.optString("displayName").orEmpty().ifBlank { "…" },
                    fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, letterSpacing = (-0.3).sp
                )
            }
            // 头像：44dp + 2dp 半透明白描边（套壳 .hero-avatar）
            val avatarUrl = me?.optString("avatar").orEmpty()
            Box(
                Modifier
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                    .clip(CircleShape)
                    .kbPress(scaleDown = 0.92f, onClick = onAvatar),
                contentAlignment = Alignment.Center
            ) {
                if (avatarUrl.isNotBlank()) {
                    coil.compose.AsyncImage(
                        model = avatarUrl,
                        contentDescription = "头像",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        (me?.optString("displayName") ?: "").ifBlank { "?" }.take(1),
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 搜索条：42dp 高、圆角 12、白色 20% 底（套壳 .searchbar）
            Row(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .kbPress(scaleDown = 0.99f, onClick = onSearch)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Search, null,
                    tint = Color.White.copy(alpha = 0.88f), modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "搜索文档、标签、内容",
                    fontSize = 14.sp, color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            // AI 问答：与搜索条同高的方形按钮（套壳 .ai-btn）
            Box(
                Modifier
                    .size(42.dp)
                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .kbPress(scaleDown = 0.92f, onClick = onAi),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.AutoAwesome, null,
                    tint = Color.White, modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun NoticeCard(
    icon: ImageVector,
    tone: KbToneKind,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    // 对齐套壳 .offline-card：浅色底 + 同色深字（不是白卡），图标是 38dp 渐变瓦片
    val t = kbTone(tone)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.bg)
            .kbPress(scaleDown = 0.985f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(Brush.linearGradient(kbIconGradient(tone)), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = t.fg)
            Text(desc, fontSize = 12.sp, color = t.fg.copy(alpha = 0.82f))
        }
        Icon(
            Icons.Filled.ChevronRight, null,
            tint = t.fg.copy(alpha = 0.6f), modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun Stat(num: Long, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (num >= 0) num.toString() else "—",
            fontSize = 17.sp, fontWeight = FontWeight.Bold,
            // 数字用正文色而不是主色：套壳里主色只留给「主行动点 + 选中态」
            color = MaterialTheme.colorScheme.onSurface,
            // 等宽数字，避免数字变化时左右跳动
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
        )
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = KbTextDim)
    }
}

/** 概览卡里的竖分割线（套壳 .ovw i：1px × 20px） */
@Composable
private fun StatDivider() {
    Box(
        Modifier
            .size(width = 1.dp, height = 20.dp)
            .background(MaterialTheme.colorScheme.outline)
    )
}

/** 区块标题（套壳 .m-sec-title：12.5sp、字重 500、淡灰、字距 .3） */
@Composable
private fun SectionTitle(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = KbTextDim,
            letterSpacing = 0.3.sp,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/**
 * 金刚区单个入口（套壳 .quick）：52dp 浅色瓦片 + 23dp 单色图标 + 12sp 文字，
 * 按下只有瓦片缩到 0.92，文字不动。
 */
@Composable
private fun QuickTile(q: Quick, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tone = kbTone(q.tone)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "quickTileScale"
    )
    Column(
        modifier.clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            onClick = onClick
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(52.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(tone.bg, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(q.icon, null, tint = tone.fg, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(
            q.label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
