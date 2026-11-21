package com.kb.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 管理 → 空间申请：团队空间**创建申请**与**扩容申请**审批。
 *
 * 后端：`GET /api/admin/doc-shares/create-requests`、`/capacity-requests`（都只回 PENDING），
 * `POST .../{rid}/approve|reject`（驳回要 `{comment}`；扩容通过后服务端自动调整容量）。
 * 测试报告 N12/N16 说「后台共享文档管理/成员表空」——这里是申请审批这条真实可用的补位。
 */
@Composable
internal fun SpacesTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("create") }   // create | capacity
    var creates by remember { mutableStateOf<List<SpaceCreateRequestItem>>(emptyList()) }
    var capacities by remember { mutableStateOf<List<SpaceCapacityRequestItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var rejectTarget by remember { mutableStateOf<Pair<String, Long>?>(null) }  // (kind, id)
    var confirmTarget by remember { mutableStateOf<Pair<String, Long>?>(null) }

    LaunchedEffect(kind, tick) {
        loading = true
        err = null
        try {
            if (kind == "create") creates = api.spaceCreateRequests(session.token)
            else capacities = api.spaceCapacityRequests(session.token)
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    fun approve(k: String, id: Long) {
        scope.launch {
            try {
                if (k == "create") api.approveSpaceCreateRequest(session.token, id)
                else api.approveSpaceCapacityRequest(session.token, id)
                msg = if (k == "create") "已通过，空间已创建" else "已通过，容量已调整"
                tick++
            } catch (e: Exception) {
                msg = e.message ?: "操作失败"
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("空间申请", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
            Spacer(Modifier.width(10.dp))
            SortChip("创建申请 ${creates.size}", sel = kind == "create") { kind = "create" }
            Spacer(Modifier.width(4.dp))
            SortChip("扩容申请 ${capacities.size}", sel = kind == "capacity") { kind = "capacity" }
            Spacer(Modifier.weight(1f))
            if (msg.isNotEmpty()) {
                Text(msg, fontSize = 10.5.sp, color = KbPrimary, modifier = Modifier.padding(end = 8.dp))
            }
            TextButton(onClick = { tick++ }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                Text("刷新", fontSize = 11.sp)
            }
        }
        androidx.compose.material3.HorizontalDivider(color = KbBorder)

        when {
            loading -> CenterText("加载中…")
            err != null -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("加载失败：${err}", fontSize = 12.sp, color = KbTextSub)
                TextButton(onClick = { tick++ }) { Text("重试", fontSize = 12.sp) }
            }
            kind == "create" && creates.isEmpty() -> CenterText("没有待审批的创建申请")
            kind == "capacity" && capacities.isEmpty() -> CenterText("没有待审批的扩容申请")
            kind == "create" -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(creates, key = { it.id }) { r ->
                    RequestCard(
                        title = "「${r.requestedName.ifBlank { "未命名空间" }}」· ${r.applicantName.ifBlank { "?" }}",
                        lines = listOfNotNull(
                            r.requestedDescription.takeIf { it.isNotBlank() }?.let { "描述：$it" },
                            r.requestedMembers.takeIf { it.isNotBlank() }?.let { "成员：$it" },
                            r.reason.takeIf { it.isNotBlank() }?.let { "理由：$it" },
                            r.createdAt.replace('T', ' ').take(16),
                        ),
                        onApprove = { confirmTarget = "create" to r.id },
                        onReject = { rejectTarget = "create" to r.id },
                    )
                }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(capacities, key = { it.id }) { r ->
                    RequestCard(
                        title = "「${r.shareName.ifBlank { "空间 ${r.shareId}" }}」· ${r.applicantName.ifBlank { "?" }}",
                        lines = listOfNotNull(
                            "容量：${formatBytes(r.currentCapacity)} → ${formatBytes(r.requestedCapacity)}",
                            r.reason.takeIf { it.isNotBlank() }?.let { "理由：$it" },
                            r.createdAt.replace('T', ' ').take(16),
                        ),
                        onApprove = { confirmTarget = "capacity" to r.id },
                        onReject = { rejectTarget = "capacity" to r.id },
                    )
                }
            }
        }
    }

    confirmTarget?.let { (k, id) ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text(if (k == "create") "通过创建申请？" else "通过扩容申请？") },
            text = {
                Text(
                    if (k == "create") "通过后会立即创建团队空间，申请人成为所有者。"
                    else "通过后会立即把空间容量调整到申请值。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmTarget = null
                    approve(k, id)
                }) { Text("通过") }
            },
            dismissButton = { TextButton(onClick = { confirmTarget = null }) { Text("取消") } },
        )
    }

    rejectTarget?.let { (k, id) ->
        RejectDialog(
            onDismiss = { rejectTarget = null },
            onSubmit = { reason ->
                rejectTarget = null
                scope.launch {
                    try {
                        if (k == "create") api.rejectSpaceCreateRequest(session.token, id, reason)
                        else api.rejectSpaceCapacityRequest(session.token, id, reason)
                        msg = "已驳回"
                        tick++
                    } catch (e: Exception) {
                        msg = e.message ?: "驳回失败"
                    }
                }
            },
        )
    }
}

@Composable
private fun RequestCard(
    title: String,
    lines: List<String>,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(10.dp))
            .border(1.dp, KbBorder, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        lines.forEach { Text(it, fontSize = 11.sp, color = KbTextSub) }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onReject, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                Text("驳回", fontSize = 11.sp, color = KbTileRose.second)
            }
            TextButton(onClick = onApprove, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                Text("通过", fontSize = 11.sp, color = KbPrimary)
            }
        }
    }
}

@Composable
private fun RejectDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("驳回申请") },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .background(Color.White, RoundedCornerShape(6.dp))
                        .border(1.dp, KbBorder, RoundedCornerShape(6.dp))
                        .padding(8.dp),
                ) {
                    if (reason.isEmpty()) {
                        Text("驳回理由（会记录给申请人）", fontSize = 11.sp, color = KbTextSub.copy(alpha = 0.7f))
                    }
                    BasicTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        textStyle = TextStyle(fontSize = 11.5.sp, color = KbTextMain),
                        cursorBrush = SolidColor(KbPrimary),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = reason.isNotBlank(), onClick = { onSubmit(reason.trim()) }) {
                Text("确认驳回", color = KbTileRose.second)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = KbTextSub)
    }
}

/** 容量展示：0 = 不限；否则按 KB/MB/GB 取合适单位（与 SpaceQuota 口径一致，字节）。 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "不限"
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1) String.format("%.1f GB", gb)
    else String.format("%.0f MB", bytes.toDouble() / (1024.0 * 1024.0))
}