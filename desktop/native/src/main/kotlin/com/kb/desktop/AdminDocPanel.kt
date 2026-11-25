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
    var total by remember { mutableStateOf(0L) }
    var page by remember { mutableStateOf(0) }
    var size by remember { mutableStateOf(20) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmingId by remember { mutableStateOf<Long?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick, q, page, size) {
        // 输入防抖：每个字符都发请求并把列表替换成「加载中…」，会闪烁且丢滚动位置
        delay(300)
        loading = true
        err = null
        try {
            val box = api.adminDocsPage(session.token, q.trim(), page, size)
            rows = box.items
            total = box.total
            if (rows.isEmpty() && page > 0 && box.total > 0) {
                page = ((box.total - 1) / size).toInt().coerceAtLeast(0)
            }
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
        AdminHeader("帖子", "共 $total 篇", onRefresh = { tick++ })
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchBox(q, { q = it; page = 0 }, Modifier.weight(1f), "搜索标题 / 正文")
        }
        Spacer(Modifier.height(6.dp))
        KbTableHead(listOf("标题" to 3f, "分类" to 1f, "作者" to 1f, "状态" to 0.9f, "更新时间" to 1.1f, "操作" to 2.4f))
        Box(Modifier.weight(1f)) {
            when {
                loading -> KbSkeletonList()
                err != null -> AdminError(err ?: "", { tick++ })
                rows.isEmpty() -> AdminHint(if (q.isNotBlank()) "没有匹配的帖子" else "还没有帖子")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { d ->
                        KbTableRow {
                            Text(
                                d.title.ifBlank { "（无标题）" },
                                modifier = Modifier.weight(3f),
                                fontSize = 13.sp,
                                color = if (d.status == "BLOCKED") Color(0xFF9CA3AF) else KbTextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                d.category?.takeIf { it.isNotBlank() } ?: "—",
                                modifier = Modifier.weight(1f),
                                fontSize = 11.sp,
                                color = KbTextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                d.ownerName.ifBlank { "—" },
                                modifier = Modifier.weight(1f),
                                fontSize = 11.sp,
                                color = KbTextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Box(Modifier.weight(0.9f)) {
                                val label = DOC_STATUS_LABEL[d.status] ?: d.status.ifBlank { "—" }
                                val tone = when (d.status) {
                                    "PUBLISHED" -> KbTone.Success
                                    "BLOCKED" -> KbTone.Danger
                                    "DELETED" -> KbTone.Neutral
                                    else -> KbTone.Warning
                                }
                                KbTag(label, tone)
                            }
                            Text(
                                fmtAdminTime(d.updatedAt),
                                modifier = Modifier.weight(1.1f),
                                fontSize = 11.sp,
                                color = KbTextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(Modifier.weight(2.4f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (d.status != "DELETED") {
                                    AdminButton(if (d.pinned) "取消置顶" else "置顶", enabled = !busy) {
                                        busy = true
                                        scope.launch {
                                            try {
                                                api.setDocPinned(session.token, d.id, !d.pinned)
                                                rows = rows.map { if (it.id == d.id) it.copy(pinned = !d.pinned) else it }
                                                toast = if (!d.pinned) "已置顶" else "已取消置顶"
                                            } catch (e: java.util.concurrent.CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                toast = e.message ?: "操作失败"
                                            } finally { busy = false }
                                        }
                                    }
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
                                    AdminButton("取消", enabled = !busy) { confirmingId = null }
                                } else {
                                    AdminButton("删除", danger = true, enabled = !busy) { confirmingId = d.id }
                                }
                            }
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
        Text(
            "删除只移入回收站，可在「回收站」恢复；批量操作与彻底删除请在 Web 端进行",
            fontSize = 10.sp,
            color = KbTextSub,
            maxLines = 2,
            lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
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
    var purging by remember { mutableStateOf<Long?>(null) }
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

    var page by remember { mutableStateOf(0) }
    var size by remember { mutableStateOf(20) }
    // 回收站接口返回裸数组（后端不分页）：好在本地切片，恢复一条后页码自动回收
    val pages = if (rows.isEmpty()) 1 else (rows.size + size - 1) / size
    LaunchedEffect(rows.size) { if (page > pages - 1) page = (pages - 1).coerceAtLeast(0) }
    val pageRows = remember(rows, page, size) { rows.drop(page * size).take(size) }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("回收站", "共 ${rows.size} 篇", onRefresh = { tick++ })
        Text(
            "恢复会按原状态放回列表；彻底删除不可恢复，需二次确认。",
            fontSize = 10.5.sp,
            color = Color(0xFFB45309),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
        KbTableHead(listOf("标题" to 3f, "分类" to 1f, "作者" to 1f, "状态" to 0.9f, "更新时间" to 1.1f, "操作" to 1.8f))
        Box(Modifier.weight(1f)) {
            when {
                loading -> KbSkeletonList()
                err != null -> AdminError(err ?: "", { tick++ })
                rows.isEmpty() -> AdminHint("回收站是空的")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(pageRows, key = { it.id }) { d ->
                        KbTableRow {
                            Text(
                                d.title.ifBlank { "（无标题）" },
                                modifier = Modifier.weight(3f),
                                fontSize = 13.sp,
                                color = KbTextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                d.category?.takeIf { it.isNotBlank() } ?: "—",
                                modifier = Modifier.weight(1f),
                                fontSize = 11.sp,
                                color = KbTextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                d.ownerName.ifBlank { "—" },
                                modifier = Modifier.weight(1f),
                                fontSize = 11.sp,
                                color = KbTextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Box(Modifier.weight(0.9f)) {
                                KbTag(DOC_STATUS_LABEL[d.status] ?: d.status.ifBlank { "—" }, KbTone.Neutral)
                            }
                            Text(
                                fmtAdminTime(d.updatedAt),
                                modifier = Modifier.weight(1.1f),
                                fontSize = 11.sp,
                                color = KbTextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(Modifier.weight(1.8f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                if (purging == d.id) {
                                    AdminButton("确认彻底删除", danger = true, enabled = !busy) {
                                        scope.launch {
                                            busy = true
                                            try {
                                                api.purgeDoc(session.token, d.id)
                                                rows = rows.filterNot { it.id == d.id }
                                                purging = null
                                                toast = "已彻底删除（不可恢复）"
                                            } catch (e: java.util.concurrent.CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                toast = e.message ?: "彻底删除失败"
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }
                                    AdminButton("取消", enabled = !busy) { purging = null }
                                } else {
                                    AdminButton("彻底删除", danger = true, enabled = !busy) { purging = d.id }
                                }
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = KbBorder)
        KbPager(
            total = rows.size.toLong(),
            page = page,
            size = size,
            onPage = { page = it },
            onSize = { size = it; page = 0 },
        )
    }
    AdminToast(toast)
}