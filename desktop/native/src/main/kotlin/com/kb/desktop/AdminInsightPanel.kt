package com.kb.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.launch

// 管理模块（2026-11-16）：看板 / 审核 / 用户 / 部门 / 公告 / 审计 / 登录日志
//   权限全部由后端判定（ADMIN 或细粒度码），403 文案原样透出；
//   只做日常运维能闭环的轻操作，重配置（外观/水印/SSO/AI 模型…）仍回 Web 端。
//   列表均取第一页 50 条（不做翻页），避免客户端变成半个后台。




// ---------------- 看板 ----------------

private val STAT_LABELS = mapOf(
    "docTotal" to "文章总数",
    "docPublished" to "已发布",
    "docPending" to "待审核",
    "docDraft" to "草稿",
    "docBlocked" to "已封禁",
    "docToday" to "今日新增",
    "docYesterday" to "昨日新增",
    "viewsTotal" to "总浏览",
    "userTotal" to "用户总数",
    "userAdmin" to "管理员",
    "userBlocked" to "已停用",
    "userToday" to "今日注册",
    "commentTotal" to "评论总数",
    "likeTotal" to "点赞总数",
    "favTotal" to "收藏总数",
    "notiTotal" to "通知总数",
)

@Composable
internal fun StatsTab(session: Session, onOpenDoc: (Long) -> Unit = {}) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    // 看板要图表数据：用原始 JSON（adminStats 会把数组折成「N 项」）
    var raw by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(session.server, session.token, tick) {
        loading = true
        err = null
        try {
            raw = api.adminStatsJson(session.token)
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    fun prim(key: String) = (raw?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    fun num(key: String): Int = prim(key)?.toDoubleOrNull()?.toInt() ?: 0
    fun values(key: String): List<String> =
        (raw?.get(key) as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            .orEmpty()
    fun nums(key: String): List<Int> = values(key).mapNotNull { it.toDoubleOrNull()?.toInt() }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("数据看板", "聚合统计（只读）", onRefresh = { tick++ })
        Box(Modifier.weight(1f)) {
            when {
                loading -> KbSkeletonList()
                err != null -> AdminError(err ?: "", { tick++ })
                raw == null -> AdminHint("没有可展示的统计数据（需要 STATS_VIEW 权限）")
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 第一行：四个核心指标
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val tiles = listOf(
                                Triple("文章总数", num("docTotal").toString(), KbTileBlue),
                                Triple("用户总数", num("userTotal").toString(), KbTileViolet),
                                Triple("评论总数", num("commentTotal").toString(), KbTileGreen),
                                Triple("总浏览", num("viewsTotal").toString(), KbTileAmber),
                            )
                            for ((title, value, tint) in tiles) {
                                Box(Modifier.weight(1f)) { StatTile(title, value, emojiFor(title), tint) }
                            }
                        }
                    }
                    // 第二行：文档状态环形 + 互动总量柱状
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DonutChart(
                                title = "文档状态",
                                segments = listOf(
                                    Triple("已发布", num("docPublished"), Color(0xFF10B981)),
                                    Triple("待审核", num("docPending"), Color(0xFFF59E0B)),
                                    Triple("草稿", num("docDraft"), Color(0xFF94A3B8)),
                                    Triple("已封禁", num("docBlocked"), Color(0xFFEF4444)),
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            BarChart(
                                title = "互动总量",
                                items = listOf(
                                    "评论" to num("commentTotal"),
                                    "点赞" to num("likeTotal"),
                                    "收藏" to num("favTotal"),
                                    "通知" to num("notiTotal"),
                                ),
                                tint = KbTileBlue,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    // 第三行：7 日趋势
                    val labels = values("trendLabels")
                    if (labels.isNotEmpty()) {
                        item {
                            LineChart(
                                title = "近 7 日趋势",
                                labels = labels,
                                series = listOf(
                                    Triple("文档", nums("docTrend7"), Color(0xFF4F46E5)),
                                    Triple("用户", nums("userTrend7"), Color(0xFF10B981)),
                                    Triple("评论", nums("commentTrend7"), Color(0xFFF59E0B)),
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    // 第四行：其余指标
                    val rest = listOf(
                        "今日新增" to num("docToday"),
                        "昨日新增" to num("docYesterday"),
                        "今日注册" to num("userToday"),
                        "管理员" to num("userAdmin"),
                        "已停用" to num("userBlocked"),
                        "通知总数" to num("notiTotal"),
                    )
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (chunk in rest.chunked(3)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for ((label, v) in chunk) {
                                        KbSurface(Modifier.fillMaxWidth()) {
                                            Row(
                                                Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(label, fontSize = 11.5.sp, color = KbTextSub, modifier = Modifier.weight(1f))
                                                Text(
                                                    v.toString(),
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = KbTextMain,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 第五行：热门文档 + 最近动态（对齐 Web 看板）
                    val topDocs = (raw?.get("topDocs") as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }.orEmpty()
                    val activities = (raw?.get("activities") as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }.orEmpty()
                    if (topDocs.isNotEmpty() || activities.isNotEmpty()) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (topDocs.isNotEmpty()) {
                                    KbSurface(Modifier.weight(1f)) {
                                        Column(Modifier.padding(14.dp)) {
                                            Text(
                                                "热门文档",
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = KbTextMain,
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            for ((i, d) in topDocs.take(5).withIndex()) {
                                                val title = (d["title"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
                                                val views = (d["views"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "0"
                                                val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                                val isHover by hover.collectIsHoveredAsState()
                                                Row(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .hoverable(hover)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(if (isHover) KbHoverBg else Color.Transparent)
                                                        .clickable(enabled = title.isNotBlank()) {
                                                            scope.launch {
                                                                val hit = runCatching {
                                                                    api.searchDocs(session.token, title)
                                                                }.getOrNull()?.firstOrNull { it.title == title }
                                                                if (hit != null) onOpenDoc(hit.id)
                                                                else openInBrowser(session.server, "/search?q=" + java.net.URLEncoder.encode(title, "UTF-8"))
                                                            }
                                                        }
                                                        .padding(horizontal = 4.dp, vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        ((i + 1).toString()).padStart(2, '0'),
                                                        fontSize = 10.5.sp,
                                                        color = KbAccent,
                                                        fontWeight = FontWeight.SemiBold,
                                                        modifier = Modifier.width(20.dp),
                                                    )
                                                    Text(
                                                        title.ifBlank { "（无标题）" },
                                                        fontSize = 11.5.sp,
                                                        color = KbTextMain,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    Text(views + " 阅", fontSize = 10.5.sp, color = KbTextSub)
                                                }
                                            }
                                        }
                                    }
                                }
                                if (activities.isNotEmpty()) {
                                    KbSurface(Modifier.weight(1f)) {
                                        Column(Modifier.padding(14.dp)) {
                                            Text(
                                                "最近动态",
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = KbTextMain,
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            for (a in activities.take(6)) {
                                                fun f(k: String) = (a[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
                                                Row(
                                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Box(
                                                        Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                                            .background(KbAccent)
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Column(Modifier.weight(1f)) {
                                                        Text(
                                                            f("text").ifBlank { f("type") },
                                                            fontSize = 11.5.sp,
                                                            color = KbTextMain,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                        Text(
                                                            listOf(f("author"), f("time")).filter { it.isNotBlank() }.joinToString(" · "),
                                                            fontSize = 10.sp,
                                                            color = KbTextSub,
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
                }
            }
        }
    }
}

/** 指标卡用的小图标（避免为一个字符串再引图标库） */
private fun emojiFor(title: String): String = when (title) {
    "文章总数" -> "📄"
    "用户总数" -> "👤"
    "评论总数" -> "💬"
    "总浏览" -> "👁"
    else -> "📊"
}

// ---------------- 操作审计 ----------------

@Composable
internal fun AuditTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    var rows by remember { mutableStateOf<List<AuditItem>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }
    var page by remember { mutableStateOf(0) }
    var size by remember { mutableStateOf(20) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(session.server, session.token, tick, page, size) {
        loading = true
        err = null
        try {
            val box = api.adminAuditPage(session.token, page, size)
            rows = box.items
            total = box.total
            if (rows.isEmpty() && page > 0 && box.total > 0) {
                page = ((box.total - 1) / size).toInt().coerceAtLeast(0)
            }
        } catch (e: Exception) { err = e.message ?: "加载失败" } finally { loading = false }
    }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("操作审计", "共 $total 条", onRefresh = { tick++ })
        KbTableHead(listOf("动作" to 1.1f, "操作人" to 1.2f, "对象" to 1.8f, "详情 / IP" to 2.2f, "时间" to 1.2f))
        Box(Modifier.weight(1f)) {
            when {
                loading -> KbSkeletonList()
                err != null -> AdminError(err ?: "", { tick++ })
                rows.isEmpty() -> AdminHint("暂无审计记录")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { r ->
                        KbTableRow {
                            Box(Modifier.weight(1.1f)) { KbTag(r.action.ifBlank { "ACTION" }, KbTone.Neutral) }
                            Text(
                                r.actorName.ifBlank { "—" },
                                modifier = Modifier.weight(1.2f),
                                fontSize = 12.sp,
                                color = KbTextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                r.targetName.ifBlank { r.targetType },
                                modifier = Modifier.weight(1.8f),
                                fontSize = 11.sp,
                                color = KbTextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOf(r.detail, r.ip).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" },
                                modifier = Modifier.weight(2.2f),
                                fontSize = 10.5.sp,
                                color = KbTextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                fmtAdminTime(r.createdAt),
                                modifier = Modifier.weight(1.2f),
                                fontSize = 11.sp,
                                color = KbTextSub,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = KbBorder)
        KbPager(
            total = total,
            page = page,
            size = size,
            onPage = { page = it },
            onSize = { size = it; page = 0 },
        )
    }
}

// ---------------- 登录日志 ----------------

@Composable
internal fun LoginLogsTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    var rows by remember { mutableStateOf<List<LoginLogItem>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }
    var page by remember { mutableStateOf(0) }
    var size by remember { mutableStateOf(20) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(session.server, session.token, tick, page, size) {
        loading = true
        err = null
        try {
            val box = api.adminLoginLogsPage(session.token, page, size)
            rows = box.items
            total = box.total
            if (rows.isEmpty() && page > 0 && box.total > 0) {
                page = ((box.total - 1) / size).toInt().coerceAtLeast(0)
            }
        } catch (e: Exception) { err = e.message ?: "加载失败" } finally { loading = false }
    }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("登录日志", "共 $total 条", onRefresh = { tick++ })
        KbTableHead(listOf("结果" to 0.8f, "账号" to 1.5f, "IP / 原因" to 2.4f, "时间" to 1.3f))
        Box(Modifier.weight(1f)) {
            when {
                loading -> KbSkeletonList()
                err != null -> AdminError(err ?: "", { tick++ })
                rows.isEmpty() -> AdminHint("暂无登录记录")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { r ->
                        KbTableRow {
                            Box(Modifier.weight(0.8f)) {
                                KbTag(if (r.success) "成功" else "失败", if (r.success) KbTone.Success else KbTone.Danger)
                            }
                            Text(
                                r.username.ifBlank { "（未知账号）" },
                                modifier = Modifier.weight(1.5f),
                                fontSize = 12.5.sp,
                                color = KbTextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOf(r.ip, r.reason).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" },
                                modifier = Modifier.weight(2.4f),
                                fontSize = 10.5.sp,
                                color = KbTextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                fmtAdminTime(r.createdAt),
                                modifier = Modifier.weight(1.3f),
                                fontSize = 11.sp,
                                color = KbTextSub,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = KbBorder)
        KbPager(
            total = total,
            page = page,
            size = size,
            onPage = { page = it },
            onSize = { size = it; page = 0 },
        )
    }
}
