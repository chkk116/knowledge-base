package com.kb.kbplatform.shell.ui.docs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.KbApi
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 文档整理（清单右下角「⋯」入口）：改标签 / 移动到其他空间。
 *
 * 后端：`PUT /doc-shares/{sid}/docs/{docId}/tags {tags:[…]}`（库里存逗号分隔字符串）、
 * `POST …/docs/{docId}/move {targetShareId}`（需空间/文档管理员或**文档所有者**，目标空间要可写，
 * 归属人不变）。标签最多取 20 个，分隔符兼容中英文逗号/顿号/分号/空白。
 */
@Composable
fun DocOrganizeDialog(
    shareId: Long,
    docId: Long,
    title: String,
    tagsRaw: String,
    spaces: List<JSONObject>,
    onClose: () -> Unit,
    onDone: (String) -> Unit,
) {
    val cs = rememberCoroutineScope()
    var tags by remember(docId) { mutableStateOf(splitTags(tagsRaw).joinToString("，")) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var movingTo by remember { mutableStateOf<Long?>(null) }
    var confirmTarget by remember { mutableStateOf<JSONObject?>(null) }

    // 目标空间：排除当前空间，且我的权限为可写
    val targets = spaces.filter { s ->
        s.optLong("id", 0) != shareId &&
            s.optString("myPermission").uppercase().let { it == "WRITE" || it == "ADMIN" || it == "OWNER" }
    }

    fun saveTags() {
        busy = true
        cs.launch {
            try {
                val list = splitTags(tags).take(20)
                KbApi.updateDocTags(shareId, docId, list)
                note = if (list.isEmpty()) "已清空标签" else "已保存 " + list.size + " 个标签"
                onDone(note)
            } catch (e: Exception) {
                note = e.message ?: "保存标签失败"
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("整理文档", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "「" + title.ifBlank { "当前文档" } + "」",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (note.isNotEmpty()) {
                    Text(
                        note,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    "标签（逗号分隔，最多 20 个）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    singleLine = true,
                    placeholder = { Text("例如：产品，需求，2026", fontSize = 11.5.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.padding(top = 2.dp)) {
                    TextButton(enabled = !busy, onClick = { saveTags() }) {
                        Text(if (busy) "保存中…" else "保存标签", fontSize = 12.sp)
                    }
                    TextButton(enabled = !busy && tags.isNotEmpty(), onClick = { tags = "" }) {
                        Text("清空输入", fontSize = 12.sp)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "移动到其他空间（只列出你可写的空间；你需要对本文档有管理权或是作者）",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(top = 2.dp)) {
                    if (targets.isEmpty()) {
                        Text(
                            "没有可移动的目标空间",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(targets, key = { it.optLong("id", it.hashCode().toLong()) }) { s ->
                                val sid = s.optLong("id", 0)
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            s.optString("name").ifBlank { "空间 $sid" },
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            permLabelOf(s.optString("myPermission")),
                                            fontSize = 10.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(
                                        enabled = movingTo == null,
                                        onClick = { confirmTarget = s },
                                        contentPadding = PaddingValues(horizontal = 6.dp)
                                    ) { Text(if (movingTo == sid) "移动中…" else "移到这里", fontSize = 11.5.sp) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("关闭") }
        }
    )

    confirmTarget?.let { target ->
        val targetId = target.optLong("id", 0)
        val targetName = target.optString("name").ifBlank { "空间 $targetId" }
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text("移动到「$targetName」？") },
            text = { Text("移动后本文档会从当前空间消失，历史版本随文档一起走；归属人不变。", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmTarget = null
                    movingTo = targetId
                    busy = true
                    cs.launch {
                        try {
                            KbApi.moveShareDoc(shareId, docId, targetId)
                            note = "已移动到「$targetName」"
                            onDone(note)
                            onClose()
                        } catch (e: Exception) {
                            note = e.message ?: "移动失败"
                        } finally {
                            movingTo = null
                            busy = false
                        }
                    }
                }) { Text("确认移动") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) { Text("取消") }
            }
        )
    }
}

private val TAG_SEP = Regex("[,，、;；\\s]+")

private fun splitTags(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return raw.split(TAG_SEP).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

private fun permLabelOf(perm: String): String = when (perm.uppercase()) {
    "OWNER" -> "所有者"
    "ADMIN" -> "可管理"
    "WRITE", "EDIT" -> "可编辑"
    "READ", "VIEW" -> "只读"
    else -> "只读"
}