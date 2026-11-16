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
internal fun StatsTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    var stats by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        loading = true
        err = null
        try {
            stats = api.adminStats(session.token)
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    val shown = STAT_LABELS.entries.mapNotNull { (k, label) -> stats[k]?.takeIf { it.isNotBlank() }?.let { label to it } }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("数据看板", "聚合统计（只读）", onRefresh = { tick++ })
        Box(Modifier.weight(1f)) {
            when {
                loading -> AdminHint("加载中…")
                err != null -> AdminError(err ?: "", { tick++ })
                shown.isEmpty() -> AdminHint("没有可展示的统计数据（需要 STATS_VIEW 权限）")
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown.size) { i ->
                        val (label, value) = shown[i]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White)
                                .border(1.dp, KbBorder, RoundedCornerShape(10.dp))
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, fontSize = 12.5.sp, color = KbTextSub, modifier = Modifier.weight(1f))
                            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- 操作审计 ----------------

@Composable
internal fun AuditTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    var rows by remember { mutableStateOf<List<AuditItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        loading = true
        err = null
        try { rows = api.adminAudit(session.token) } catch (e: Exception) { err = e.message ?: "加载失败" } finally { loading = false }
    }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("操作审计", "${rows.size} 条（最近 50）", onRefresh = { tick++ })
        Box(Modifier.weight(1f)) {
            when {
                loading -> AdminHint("加载中…")
                err != null -> AdminError(err ?: "", { tick++ })
                rows.isEmpty() -> AdminHint("暂无审计记录")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { r ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AdminChip(r.action.ifBlank { "ACTION" }, Color(0xFFF1F5F9), KbTextSub)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    r.actorName.ifBlank { "—" },
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = KbTextMain,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    r.targetName.ifBlank { r.targetType },
                                    fontSize = 11.5.sp,
                                    color = KbTextSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(fmtAdminTime(r.createdAt), fontSize = 10.5.sp, color = KbTextSub)
                            }
                            if (r.detail.isNotBlank() || r.ip.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    listOf(r.detail, r.ip).filter { it.isNotBlank() }.joinToString(" · "),
                                    fontSize = 10.5.sp,
                                    color = KbTextSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                    }
                }
            }
        }
    }
}

// ---------------- 登录日志 ----------------

@Composable
internal fun LoginLogsTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    var rows by remember { mutableStateOf<List<LoginLogItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        loading = true
        err = null
        try { rows = api.adminLoginLogs(session.token) } catch (e: Exception) { err = e.message ?: "加载失败" } finally { loading = false }
    }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("登录日志", "${rows.size} 条（最近 50）", onRefresh = { tick++ })
        Box(Modifier.weight(1f)) {
            when {
                loading -> AdminHint("加载中…")
                err != null -> AdminError(err ?: "", { tick++ })
                rows.isEmpty() -> AdminHint("暂无登录记录")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { r ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AdminChip(if (r.success) "成功" else "失败", if (r.success) Color(0xFFD1FAE5) else Color(0xFFFEE2E2), if (r.success) Color(0xFF047857) else Color(0xFFB91C1C))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.username.ifBlank { "（未知账号）" }, fontSize = 12.5.sp, color = KbTextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    listOf(r.ip, r.reason).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" },
                                    fontSize = 10.5.sp,
                                    color = KbTextSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(fmtAdminTime(r.createdAt), fontSize = 10.5.sp, color = KbTextSub)
                        }
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                    }
                }
            }
        }
    }
}
