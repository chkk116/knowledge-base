package com.kb.kbplatform.shell.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.KbChip
import com.kb.kbplatform.shell.ui.common.KbEmpty
import com.kb.kbplatform.shell.ui.common.KbError
import com.kb.kbplatform.shell.ui.common.KbLoading
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.formatDateTime
import com.kb.kbplatform.shell.ui.theme.KbGreen
import com.kb.kbplatform.shell.ui.theme.KbRed
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 管理 → 空间申请：团队空间**创建申请**与**扩容申请**审批（仅完全管理员）。
 *
 * 后端：`GET /api/admin/doc-shares/create-requests` / `capacity-requests`（只回 PENDING），
 * `POST .../{rid}/approve|reject`（驳回必带 `comment`；扩容通过后服务端自动调容量）。
 */
@Composable
fun SpacesAdminScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("create") }
    var creates by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var capacities by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var rejectTarget by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var confirmTarget by remember { mutableStateOf<Pair<String, Long>?>(null) }

    LaunchedEffect(kind, tick) {
        loading = true
        err = ""
        try {
            if (kind == "create") creates = KbApi.adminSpaceCreateRequests().objects()
            else capacities = KbApi.adminSpaceCapacityRequests().objects()
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    fun approve(k: String, id: Long) {
        scope.launch {
            try {
                if (k == "create") KbApi.approveSpaceCreate(id) else KbApi.approveSpaceCapacity(id)
                msg = if (k == "create") "已通过，空间已创建" else "已通过，容量已调整"
                tick++
            } catch (e: Exception) {
                msg = e.message ?: "操作失败"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "空间申请", onBack = onBack, actions = {
            TextButton(onClick = { tick++ }) { Text("刷新", fontSize = 13.sp) }
        })
        if (msg.isNotEmpty()) {
            Text(msg, fontSize = 11.5.sp, color = KbGreen, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KbChip("创建申请 ${creates.size}", selected = kind == "create", onClick = { kind = "create" })
            KbChip("扩容申请 ${capacities.size}", selected = kind == "capacity", onClick = { kind = "capacity" })
        }

        when {
            loading -> KbLoading()
            err.isNotEmpty() -> KbError("申请加载失败：$err", onRetry = { tick++ })
            kind == "create" && creates.isEmpty() -> KbEmpty("🏗️", "没有待审批的创建申请")
            kind == "capacity" && capacities.isEmpty() -> KbEmpty("💾", "没有待审批的扩容申请")
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val rows = if (kind == "create") creates else capacities
                items(rows, key = { it.optLong("id", it.hashCode().toLong()) }) { r ->
                    val id = r.optLong("id", 0)
                    KbCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            if (kind == "create") {
                                Text(
                                    "「" + r.optString("requestedName").ifBlank { "未命名空间" } + "」",
                                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                                )
                                Text("申请人：" + r.optString("applicantName").ifBlank { "?" }, fontSize = 12.sp, color = KbTextDim)
                                val desc = r.optString("requestedDescription")
                                if (desc.isNotBlank()) Text("描述：$desc", fontSize = 12.sp, color = KbTextDim)
                                val members = r.optString("requestedMembers")
                                if (members.isNotBlank()) Text("成员：$members", fontSize = 12.sp, color = KbTextDim)
                            } else {
                                Text(
                                    "「" + r.optString("shareName").ifBlank { "空间 " + r.optLong("shareId", 0) } + "」",
                                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                                )
                                Text("申请人：" + r.optString("applicantName").ifBlank { "?" }, fontSize = 12.sp, color = KbTextDim)
                                Text(
                                    "容量：" + formatBytes(r.optLong("currentCapacity", 0)) + " → " +
                                        formatBytes(r.optLong("requestedCapacity", 0)),
                                    fontSize = 12.sp, color = KbTextDim
                                )
                            }
                            val reason = r.optString("reason")
                            if (reason.isNotBlank()) Text("理由：$reason", fontSize = 12.sp, color = KbTextDim)
                            Text(formatDateTime(r.optString("createdAt")), fontSize = 11.sp, color = KbTextDim)
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { rejectTarget = kind to id }) {
                                    Text("驳回", fontSize = 12.sp, color = KbRed)
                                }
                                TextButton(onClick = { confirmTarget = kind to id }) {
                                    Text("通过", fontSize = 12.sp, color = KbGreen)
                                }
                            }
                        }
                    }
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
            dismissButton = { TextButton(onClick = { confirmTarget = null }) { Text("取消") } }
        )
    }

    rejectTarget?.let { (k, id) ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejectTarget = null },
            title = { Text("驳回申请") },
            text = {
                Column {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("驳回理由（会记录给申请人）", fontSize = 11.sp) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = reason.isNotBlank(), onClick = {
                    val text = reason.trim()
                    rejectTarget = null
                    scope.launch {
                        try {
                            if (k == "create") KbApi.rejectSpaceCreate(id, text)
                            else KbApi.rejectSpaceCapacity(id, text)
                            msg = "已驳回"
                            tick++
                        } catch (e: Exception) {
                            msg = e.message ?: "驳回失败"
                        }
                    }
                }) { Text("确认驳回", color = KbRed) }
            },
            dismissButton = { TextButton(onClick = { rejectTarget = null }) { Text("取消") } }
        )
    }
}

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

/** 容量展示：0 = 不限；否则 KB/MB/GB 取合适单位（与后端一致，字节）。 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "不限"
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1) String.format("%.1f GB", gb)
    else String.format("%.0f MB", bytes.toDouble() / (1024.0 * 1024.0))
}