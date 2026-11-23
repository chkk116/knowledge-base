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




// ---------------- 审核队列 ----------------

@Composable
internal fun ReviewTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf<List<AdminDocItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var rejecting by remember { mutableStateOf<Long?>(null) }
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick) {
        loading = true
        err = null
        try {
            docs = api.adminPendingDocs(session.token)
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    fun approve(id: Long) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                api.approveDoc(session.token, id)
                docs = docs.filterNot { it.id == id }
                toast = "已通过"
            } catch (e: Exception) {
                toast = e.message ?: "操作失败"
            } finally {
                busy = false
            }
        }
    }

    fun reject(id: Long) {
        val r = reason.trim()
        if (r.isEmpty()) { toast = "请填写驳回理由"; return }
        if (busy) return
        scope.launch {
            busy = true
            try {
                api.rejectDoc(session.token, id, r)
                docs = docs.filterNot { it.id == id }
                rejecting = null
                reason = ""
                toast = "已驳回并退回作者草稿"
            } catch (e: Exception) {
                toast = e.message ?: "操作失败"
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("审核队列", "共 ${docs.size} 篇待审核", onRefresh = { tick++ })
        KbTableHead(listOf("标题" to 3f, "分类" to 1f, "作者" to 1f, "更新时间" to 1.1f, "操作" to 1.8f))
        Box(Modifier.weight(1f)) {
            when {
                loading -> KbSkeletonList()
                err != null -> AdminError(err ?: "", { tick++ })
                docs.isEmpty() -> AdminHint("没有待审核的文章")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(docs, key = { it.id }) { d ->
                        KbTableRow(vertical = 10) {
                            Column(Modifier.weight(3f)) {
                                Text(
                                    d.title.ifBlank { "（无标题）" },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = KbTextMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (rejecting == d.id) {
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = reason,
                                        onValueChange = { reason = it },
                                        placeholder = { Text("驳回理由（作者可见）", fontSize = 11.5.sp, color = KbTextSub) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = KbPrimary,
                                            unfocusedBorderColor = KbBorder,
                                        ),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                        AdminButton("确认驳回", danger = true, enabled = !busy) { reject(d.id) }
                                        AdminButton("取消", enabled = !busy) { rejecting = null; reason = "" }
                                    }
                                }
                            }
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
                            Text(
                                fmtAdminTime(d.updatedAt),
                                modifier = Modifier.weight(1.1f),
                                fontSize = 11.sp,
                                color = KbTextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(Modifier.weight(1.8f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (rejecting != d.id) {
                                    AdminButton("通过", enabled = !busy) { approve(d.id) }
                                    AdminButton("驳回", danger = true, enabled = !busy) { rejecting = d.id; reason = "" }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    AdminToast(toast)
}

// ---------------- 阅卷 ----------------

@Composable
internal fun GradingTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<GradingPendingItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<GradingDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var scores by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var comments by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick) {
        loading = true
        err = null
        try { rows = api.gradingPending(session.token) } catch (e: Exception) { err = e.message ?: "加载失败" } finally { loading = false }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    fun open(recordId: Long) {
        scope.launch {
            detailLoading = true
            detail = null
            scores = emptyMap()
            comments = emptyMap()
            try {
                val d = api.gradingDetail(session.token, recordId)
                detail = d
                comments = d?.items.orEmpty().filter { it.comment.isNotBlank() }.associate { it.questionId to it.comment }
            } catch (e: Exception) {
                toast = e.message ?: "加载失败"
            } finally {
                detailLoading = false
            }
        }
    }

    fun submit() {
        val d = detail ?: return
        val pending = d.items.filter { it.pending }
        val marks = LinkedHashMap<Long, Pair<Int, String>>()
        for ((i, q) in pending.withIndex()) {
            val raw = scores[q.questionId]?.trim().orEmpty()
            val n = raw.toIntOrNull()
            if (n == null) { toast = "请给第 ${i + 1} 题打分"; return }
            if (n < 0 || (q.maxScore > 0 && n > q.maxScore)) { toast = "第 ${i + 1} 题分数需在 0~${q.maxScore}"; return }
            marks[q.questionId] = n to comments[q.questionId].orEmpty()
        }
        if (marks.isEmpty()) { toast = "没有待打分的题目"; return }
        scope.launch {
            busy = true
            try {
                api.submitGrading(session.token, d.recordId, marks)
                toast = "已提交阅卷"
                detail = null
                tick++
            } catch (e: Exception) {
                toast = e.message ?: "提交失败"
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val d = detail
        if (d == null) {
            AdminHeader("阅卷", "共 ${rows.size} 场待阅", onRefresh = { tick++ })
            KbTableHead(listOf("考生" to 1.2f, "试卷" to 2f, "得分" to 0.8f, "待阅" to 0.8f, "提交时间" to 1.1f, "操作" to 0.9f))
            Box(Modifier.weight(1f)) {
                when {
                    loading -> KbSkeletonList()
                    err != null -> AdminError(err ?: "", { tick++ })
                    rows.isEmpty() -> AdminHint("没有待阅卷的考试")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { it.recordId }) { r ->
                            KbTableRow {
                                Text(
                                    r.user.ifBlank { "—" },
                                    modifier = Modifier.weight(1.2f),
                                    fontSize = 12.5.sp,
                                    color = KbTextMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    r.paperTitle.ifBlank { "试卷" },
                                    modifier = Modifier.weight(2f),
                                    fontSize = 11.sp,
                                    color = KbTextSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${r.score}",
                                    modifier = Modifier.weight(0.8f),
                                    fontSize = 12.sp,
                                    color = KbTextMain,
                                    maxLines = 1,
                                )
                                Box(Modifier.weight(0.8f)) {
                                    KbTag("${r.pendingCount}", if (r.pendingCount > 0) KbTone.Warning else KbTone.Success)
                                }
                                Text(
                                    fmtAdminTime(r.submittedAt),
                                    modifier = Modifier.weight(1.1f),
                                    fontSize = 11.sp,
                                    color = KbTextSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Box(Modifier.weight(0.9f)) {
                                    AdminButton("阅卷", enabled = !detailLoading) { open(r.recordId) }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { detail = null; tick++ }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("← 返回列表", fontSize = 11.sp)
                }
                Text("记录 #${d.recordId}", fontSize = 11.sp, color = KbTextSub)
                Spacer(Modifier.weight(1f))
                AdminButton(if (busy) "提交中…" else "提交阅卷", enabled = !busy) { submit() }
            }
            HorizontalDivider(color = KbBorder)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(d.items.size) { idx ->
                    val q = d.items[idx]
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White)
                            .border(1.dp, if (q.pending) Color(0xFFFDE68A) else KbBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("第 ${idx + 1} 题", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
                            Spacer(Modifier.width(6.dp))
                            Text("满分 ${q.maxScore}", fontSize = 10.5.sp, color = KbTextSub)
                            Spacer(Modifier.weight(1f))
                            if (!q.pending) AdminChip("已评 ${q.score}", Color(0xFFD1FAE5), Color(0xFF047857))
                            else AdminChip("待评分", Color(0xFFFEF3C7), Color(0xFFB45309))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(plainAdminHtml(q.content), fontSize = 12.sp, color = KbTextMain, lineHeight = 19.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("学生答案", fontSize = 10.5.sp, color = KbTextSub)
                        Text(
                            plainAdminHtml(q.userAnswer).ifBlank { "（未作答）" },
                            fontSize = 12.sp,
                            color = Color(0xFF374151),
                            lineHeight = 19.sp,
                        )
                        if (q.pending) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = scores[q.questionId].orEmpty(),
                                    onValueChange = { scores = scores + (q.questionId to it) },
                                    modifier = Modifier.width(96.dp),
                                    singleLine = true,
                                    placeholder = { Text("分数", fontSize = 11.5.sp, color = KbTextSub) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KbPrimary, unfocusedBorderColor = KbBorder),
                                )
                                OutlinedTextField(
                                    value = comments[q.questionId].orEmpty(),
                                    onValueChange = { comments = comments + (q.questionId to it) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    placeholder = { Text("评语（可选，学生可见）", fontSize = 11.5.sp, color = KbTextSub) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KbPrimary, unfocusedBorderColor = KbBorder),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    AdminToast(toast)
}

// ---------------- 举报 ----------------

@Composable
internal fun ReportsTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<ReportItem>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }
    var page by remember { mutableStateOf(0) }
    var size by remember { mutableStateOf(20) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var handling by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(status, tick, page, size) {
        loading = true
        err = null
        try {
            val box = api.adminReportsPage(session.token, status, page, size)
            rows = box.items
            total = box.total
            if (rows.isEmpty() && page > 0 && box.total > 0) {
                page = ((box.total - 1) / size).toInt().coerceAtLeast(0)
            }
        } catch (e: Exception) { err = e.message ?: "加载失败" } finally { loading = false }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    fun handle(id: Long, to: String) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                api.handleReport(session.token, id, to, note.trim())
                handling = null
                note = ""
                toast = if (to == "HANDLED") "已标记处理" else "已驳回举报"
                tick++
            } catch (e: Exception) {
                toast = e.message ?: "操作失败"
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("举报 / 反馈", "共 $total 条", onRefresh = { tick++ })
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ReportChip("全部", status == null) { status = null; page = 0 }
            ReportChip("待处理", status == "PENDING") { status = "PENDING"; page = 0 }
            ReportChip("已处理", status == "HANDLED") { status = "HANDLED"; page = 0 }
            ReportChip("已驳回", status == "REJECTED") { status = "REJECTED"; page = 0 }
        }
        Spacer(Modifier.height(6.dp))
        KbTableHead(listOf("举报内容" to 3f, "状态" to 0.9f, "时间" to 1.2f, "操作" to 1f))
        Box(Modifier.weight(1f)) {
            when {
                loading -> KbSkeletonList()
                err != null -> AdminError(err ?: "", { tick++ })
                rows.isEmpty() -> AdminHint("没有举报记录")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { r ->
                        KbTableRow(vertical = 9) {
                            Column(Modifier.weight(3f)) {
                                Text(
                                    r.reason.ifBlank { "（无原因）" },
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = KbTextMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    listOf(r.targetType, r.targetId?.let { "#$it" } ?: "").filter { it.isNotBlank() }.joinToString(" ") +
                                        if (r.detail.isNotBlank()) " · ${r.detail}" else "",
                                    fontSize = 10.5.sp,
                                    color = KbTextSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (r.status == "PENDING" && handling == r.id) {
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = note,
                                        onValueChange = { note = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        placeholder = { Text("处理备注（可选）", fontSize = 11.5.sp, color = KbTextSub) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KbPrimary, unfocusedBorderColor = KbBorder),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                        AdminButton("确认已处理", enabled = !busy) { handle(r.id, "HANDLED") }
                                        AdminButton("驳回举报", danger = true, enabled = !busy) { handle(r.id, "REJECTED") }
                                        AdminButton("取消", enabled = !busy) { handling = null; note = "" }
                                    }
                                }
                            }
                            Box(Modifier.weight(0.9f)) {
                                KbTag(
                                    when (r.status) {
                                        "PENDING" -> "待处理"
                                        "HANDLED" -> "已处理"
                                        "REJECTED" -> "已驳回"
                                        else -> r.status.ifBlank { "—" }
                                    },
                                    when (r.status) {
                                        "PENDING" -> KbTone.Warning
                                        "HANDLED" -> KbTone.Success
                                        "REJECTED" -> KbTone.Neutral
                                        else -> KbTone.Neutral
                                    },
                                )
                            }
                            Text(
                                fmtAdminTime(r.createdAt),
                                modifier = Modifier.weight(1.2f),
                                fontSize = 11.sp,
                                color = KbTextSub,
                                maxLines = 1,
                            )
                            Box(Modifier.weight(1f)) {
                                if (r.status == "PENDING" && handling != r.id) {
                                    AdminButton("处理", enabled = !busy) { handling = r.id; note = "" }
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
    }
    AdminToast(toast)
}

@Composable
private fun ReportChip(label: String, sel: Boolean, onClick: () -> Unit) {
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

private fun plainAdminHtml(html: String?): String {
    if (html.isNullOrBlank()) return ""
    return html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
}
