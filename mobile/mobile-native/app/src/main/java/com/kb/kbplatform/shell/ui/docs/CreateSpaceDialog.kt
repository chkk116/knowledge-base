package com.kb.kbplatform.shell.ui.docs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.KbApi
import kotlinx.coroutines.launch

/**
 * 新建团队空间（原生）。
 *
 * `POST /doc-shares {name, description, type:"TEAM"}` 需 `SHARE_CREATE`；非系统管理员团队空间已达上限（2 个）时 403，
 * 此时切换成「提交创建申请」（`POST /doc-shares/create-requests`），管理员在后台审批通过后自动建空间。
 * 容量一律 2GB（扩容走扩容申请）；成员创建后到「成员」里邀请。
 */
@Composable
fun CreateSpaceDialog(onClose: () -> Unit, onCreated: () -> Unit, onRequested: () -> Unit) {
    val cs = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var needRequest by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("新建团队空间", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "创建后默认容量 2GB（不够可在「扩容申请」里提）；成员创建后到「成员」里邀请。",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (note.isNotEmpty()) {
                    Text(note, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("空间名称", fontSize = 11.sp) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("描述（可选）", fontSize = 11.sp) },
                    minLines = 2,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                if (needRequest) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Text(
                        "直接创建被拒（多为团队空间数量已达上限）。可改为提交申请，管理员审批后自动建空间：",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        placeholder = { Text("申请理由（可选）", fontSize = 11.5.sp) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    enabled = !busy && name.trim().isNotEmpty(),
                    onClick = {
                        busy = true
                        note = ""
                        cs.launch {
                            try {
                                if (needRequest) {
                                    KbApi.submitShareCreateRequest(name.trim(), desc.trim(), reason.trim())
                                    onRequested()
                                } else {
                                    KbApi.createShareSpace(name.trim(), desc.trim())
                                    onCreated()
                                }
                            } catch (e: Exception) {
                                val msg = e.message ?: (if (needRequest) "提交申请失败" else "创建失败")
                                note = msg
                                if (msg.contains("上限") || msg.contains("权限")) needRequest = true
                            } finally {
                                busy = false
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text(
                        when {
                            busy -> "提交中…"
                            needRequest -> "提交创建申请"
                            else -> "创建"
                        },
                        fontSize = 12.sp
                    )
                }
                TextButton(onClick = onClose) { Text("取消", fontSize = 12.sp) }
            }
        }
    )
}