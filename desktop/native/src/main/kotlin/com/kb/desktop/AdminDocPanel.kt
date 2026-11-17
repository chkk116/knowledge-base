package com.kb.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 管理：帖子管理（2026-11-16）
//   GET /api/admin/docs?keyword=（全部状态，取第一页 50 条）+ 封禁/解封（block|unblock）+ 删除进回收站（DELETE）。
//   批量操作与彻底删除留 Web（不可逆、误删代价高）。

private val DOC_STATUS_LABEL = mapOf(
    "PUBLISHED" to "已发布",
    "PENDING_REVIEW" to "待审核",
    "PENDING" to "待审核",
    "DRAFT" to "草稿",
    "BLOCKED" to "已封禁",
    "DELETED" to "回收站",
)

@Composable
internal fun DocManageTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<AdminDocItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmingId by remember { mutableStateOf<Long?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick, q) {
        // 输入防抖：每个字符都发请求并把列表替换成「加载中…」，会闪烁且丢滚动位置
        delay(300)
        loading = true
        err = null
        try {
            rows = api.adminDocs(session.token, q.trim())
        } catch (e: java.util.concurrent.CancellationException) {
            throw e   // 取消不是失败：q/tick 变化会取消上一次请求，不能弹错误也不能让旧结果落盘
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }
    // 搜索或手动刷新后，旧的「确认删除」待确认状态必须清掉，避免误触删错行
    LaunchedEffect(tick, q) { confirmingId = null }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("帖子", "${rows.size} 篇（最多 50）", onRefresh = { tick++ })
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchBox(q, { q = it }, Modifier.weight(1f), "搜索标题 / 正文")
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = KbBorder)
        Box(Modifier.weight(1f)) {
            when {
                loading -> AdminHint("加载中…")
                err != null -> AdminError(err ?: "", { tick++ })
                rows.isEmpty() -> AdminHint(if (q.isNotBlank()) "没有匹配的帖子" else "还没有帖子")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { d ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        d.title.ifBlank { "（无标题）" },
                                        fontSize = 13.sp,
                                        color = if (d.status == "BLOCKED") Color(0xFF9CA3AF) else KbTextMain,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    val label = DOC_STATUS_LABEL[d.status] ?: d.status.ifBlank { "—" }
                                    val tone = when (d.status) {
                                        "PUBLISHED" -> Color(0xFF047857) to Color(0xFFD1FAE5)
                                        "BLOCKED" -> Color(0xFFB91C1C) to Color(0xFFFEE2E2)
                                        "DELETED" -> Color(0xFF6B7280) to Color(0xFFF3F4F6)
                                        else -> Color(0xFFB45309) to Color(0xFFFEF3C7)
                                    }
                                    AdminChip(label, tone.second, tone.first)
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    listOfNotNull(
                                        d.category?.takeIf { it.isNotBlank() },
                                        d.ownerName.takeIf { it.isNotBlank() },
                                        fmtAdminTime(d.updatedAt).takeIf { it.isNotBlank() },
                                    ).joinToString(" · "),
                                    fontSize = 10.5.sp,
                                    color = KbTextSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (d.status != "DELETED") {
                                AdminButton(if (d.status == "BLOCKED") "解封" else "封禁", enabled = !busy) {
                                    scope.launch {
                                        busy = true
                                        try {
                                            api.setDocBlocked(session.token, d.id, d.status != "BLOCKED")
                                            toast = if (d.status == "BLOCKED") "已解封" else "已封禁"
                                            tick++
                                        } catch (e: java.util.concurrent.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            toast = e.message ?: "操作失败"
                                        } finally { busy = false }
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            if (confirmingId == d.id) {
                                AdminButton("确认删除", danger = true, enabled = !busy) {
                                    scope.launch {
                                        busy = true
                                        try {
                                            api.moveDocToRecycle(session.token, d.id)
                                            confirmingId = null
                                            toast = "已移入回收站（可在「回收站」恢复）"
                                            tick++   // 与封禁路径一致：刷新列表，让服务端第 51 条回填、计数不漂移
                                        } catch (e: java.util.concurrent.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            toast = e.message ?: "删除失败"
                                        } finally { busy = false }
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                AdminButton("取消", enabled = !busy) { confirmingId = null }
                            } else {
                                AdminButton("删除", danger = true, enabled = !busy) { confirmingId = d.id }
                            }
                        }
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                    }
                }
            }
        }
        Text(
            "删除只移入回收站，可在「回收站」恢复；批量操作与彻底删除请在 Web 端进行",
            fontSize = 10.sp,
            color = KbTextSub,
            maxLines = 2,
            lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
        )
    }
    AdminToast(toast)
}

// 管理：帖子回收站（2026-11-16）
//   GET /api/admin/docs/recycle（POST_MANAGE）→ 恢复 POST /api/admin/docs/{id}/restore。
//   桌面端只做「恢复」；彻底删除与批量操作留在 Web（不可逆，且回收站本就是为了防误删）。

@Composable
internal fun RecycleTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<AdminDocItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick) {
        loading = true
        err = null
        try {
            rows = api.recycleDocs(session.token)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally { loading = false }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("回收站", "${rows.size} 篇", onRefresh = { tick++ })
        Text(
            "恢复会按原状态放回列表；彻底删除请在 Web 端操作（不可逆）。",
            fontSize = 10.5.sp,
            color = Color(0xFFB45309),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
        HorizontalDivider(color = KbBorder)
        Box(Modifier.weight(1f)) {
            when {
                loading -> AdminHint("加载中…")
                err != null -> AdminError(err ?: "", { tick++ })
                rows.isEmpty() -> AdminHint("回收站是空的")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { d ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    d.title.ifBlank { "（无标题）" },
                                    fontSize = 13.sp,
                                    color = KbTextMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    listOfNotNull(
                                        d.category?.takeIf { it.isNotBlank() },
                                        d.ownerName.takeIf { it.isNotBlank() },
                                        d.status.takeIf { it.isNotBlank() },
                                        fmtAdminTime(d.updatedAt).takeIf { it.isNotBlank() },
                                    ).joinToString(" · "),
                                    fontSize = 10.5.sp,
                                    color = KbTextSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            AdminButton("恢复", enabled = !busy) {
                                scope.launch {
                                    busy = true
                                    try {
                                        api.restoreDoc(session.token, d.id)
                                        rows = rows.filterNot { it.id == d.id }
                                        toast = "已恢复「${d.title.ifBlank { "无标题" }}」"
                                    } catch (e: java.util.concurrent.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        toast = e.message ?: "恢复失败"
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                    }
                }
            }
        }
    }
    AdminToast(toast)
}