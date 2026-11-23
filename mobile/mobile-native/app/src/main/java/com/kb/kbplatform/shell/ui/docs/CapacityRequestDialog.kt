package com.kb.kbplatform.shell.ui.docs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import com.kb.kbplatform.shell.ui.common.KbChip
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 空间扩容申请（原生）：选目标容量 + 理由，提交后由系统管理员在后台审批，通过会自动把上限调到申请值。
 *
 * 后端：`POST /doc-shares/{sid}/capacity-requests {requestedCapacity, reason}`（需 OWNER/ADMIN；0 = 不限）；
 * `GET …/{sid}/capacity-requests` → 全部申请记录（含已处理与驳回理由）。
 */
@Composable
fun CapacityRequestDialog(
    shareId: Long,
    shareName: String,
    currentCapacity: Long,
    used: Long,
    onClose: () -> Unit,
    onSubmitted: () -> Unit,
) {
    val cs = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var targetGb by remember { mutableIntStateOf(8) }
    var unlimited by remember { mutableStateOf(false) }
    var customGb by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }

    LaunchedEffect(tick) {
        loading = true
        err = ""
        try {
            val arr = KbApi.shareCapacityRequests(shareId)
            rows = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (e: Exception) {
            err = e.message ?: "申请记录加载失败"
        } finally {
            loading = false
        }
    }

    fun gbToBytes(gb: Double): Long = (gb * 1024.0 * 1024.0 * 1024.0).toLong()

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("扩容申请", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "「" + shareName.ifBlank { "空间 $shareId" } + "」· 当前上限：" +
                        (if (currentCapacity > 0) fmtCapBytes(currentCapacity) else "不限") +
                        " · 已用：" + fmtCapBytes(used),
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (note.isNotEmpty()) {
                    Text(note, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (g in listOf(4, 8, 16, 32, 64)) {
                        KbChip("${g} GB", selected = !unlimited && customGb.isBlank() && targetGb == g, onClick = {
                            targetGb = g
                            unlimited = false
                            customGb = ""
                        })
                        Spacer(Modifier.size(4.dp))
                    }
                    KbChip("不限", selected = unlimited && customGb.isBlank(), onClick = {
                        unlimited = true
                        customGb = ""
                    })
                }
                OutlinedTextField(
                    value = customGb,
                    onValueChange = { customGb = it },
                    placeholder = { Text("自定义 GB（留空用上面的预设）", fontSize = 11.5.sp) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    placeholder = { Text("申请理由（可选）", fontSize = 11.5.sp) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Text("申请记录", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                    when {
                        loading -> Text("加载中…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        err.isNotEmpty() -> Text(err, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        rows.isEmpty() -> Text(
                            "还没有提交过扩容申请",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> LazyColumn(Modifier.fillMaxWidth()) {
                            // 没有 id 的历史行没有稳定 key：JSONObject.hashCode() 是身份哈希，
                            // 多行同时缺省会撞 key 直接崩列表。这类行不进 keyed 列表。
                            items(rows.filter { it.has("id") && !it.isNull("id") }, key = { it.optLong("id") }) { r ->
                                val status = r.optString("status").uppercase()
                                val label = when (status) {
                                    "APPROVED" -> "已通过"
                                    "REJECTED" -> "已驳回"
                                    else -> "待审批"
                                }
                                val color = when (status) {
                                    "APPROVED" -> MaterialTheme.colorScheme.primary
                                    "REJECTED" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.tertiary
                                }
                                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            capOf(r.optLong("currentCapacity", 0)) + " → " +
                                                capOf(r.optLong("requestedCapacity", 0)),
                                            fontSize = 12.sp
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(label, fontSize = 11.sp, color = color)
                                    }
                                    val cm = r.optString("reviewComment")
                                    if (cm.isNotBlank()) {
                                        Text("审批意见：" + cm, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val customText = customGb.trim()
                        val custom = if (customText.isEmpty()) null else customText.toDoubleOrNull()
                        if (customText.isNotEmpty() && (custom == null || custom <= 0)) {
                            note = "自定义容量需要是大于 0 的数字（GB）"
                            return@TextButton
                        }
                        val bytes = when {
                            custom != null -> gbToBytes(custom)
                            unlimited -> 0L
                            else -> gbToBytes(targetGb.toDouble())
                        }
                        // 0 表示不限；其余必须不小于已用，否则管理员批了也立刻超限
                        if (bytes > 0 && bytes <= used) {
                            note = "目标容量（${fmtCapBytes(bytes)}）需要大于当前已用（${fmtCapBytes(used)}）"
                            return@TextButton
                        }
                        note = ""
                        busy = true
                        cs.launch {
                            try {
                                KbApi.requestShareCapacity(shareId, bytes, reason.trim())
                                note = "已提交，等待管理员审批"
                                reason = ""
                                tick++
                                onSubmitted()
                            } catch (e: Exception) {
                                note = e.message ?: "提交失败"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) { Text(if (busy) "提交中…" else "提交申请", fontSize = 12.sp) }
                TextButton(onClick = onClose) { Text("关闭", fontSize = 12.sp) }
            }
        }
    )
}

private fun fmtCapBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1) String.format("%.1f GB", gb)
    else String.format("%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
}

private fun capOf(bytes: Long): String = if (bytes <= 0L) "不限" else fmtCapBytes(bytes)
