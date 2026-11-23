package com.kb.kbplatform.shell.ui.docs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
 * 空间设置：改名 / 改描述 / 删除。
 *
 * 后端：`PUT /doc-shares/{id} {name, description}`（需 OWNER/ADMIN，容量不在这里改）；
 * `DELETE /doc-shares/{id}`（**仅所有者或系统管理员**），会连带删除该空间全部文档、版本、成员与文档级权限。
 */
@Composable
fun SpaceSettingsDialog(
    shareId: Long,
    name: String,
    description: String,
    myPermission: String,
    type: String,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
) {
    val cs = rememberCoroutineScope()
    var nameState by remember(shareId) { mutableStateOf(name) }
    var descState by remember(shareId) { mutableStateOf(description) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val canEdit = myPermission.uppercase().let { it == "OWNER" || it == "ADMIN" }
    val canDelete = myPermission.uppercase() == "OWNER"

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("空间设置", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "「" + name.ifBlank { "空间 $shareId" } + "」· " +
                        typeLabel(type) + " · 我的权限：" + permLabelOf(myPermission),
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
                OutlinedTextField(
                    value = nameState,
                    onValueChange = { nameState = it },
                    label = { Text("空间名称", fontSize = 11.sp) },
                    singleLine = true,
                    enabled = canEdit && !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                OutlinedTextField(
                    value = descState,
                    onValueChange = { descState = it },
                    label = { Text("描述", fontSize = 11.sp) },
                    minLines = 2,
                    enabled = canEdit && !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                if (!canEdit) {
                    Text(
                        "只有空间所有者或「可管理」成员能改名/改描述",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                if (canDelete) {
                    Text(
                        "危险操作：删除空间会连同该空间全部文档、历史版本与成员权限一起删除，不可恢复。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(
                        enabled = !busy,
                        onClick = { confirming = true },
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("删除这个空间", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text(
                        "只有空间所有者能删除空间",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    enabled = canEdit && !busy && nameState.trim().isNotEmpty(),
                    onClick = {
                        busy = true
                        cs.launch {
                            try {
                                KbApi.updateShareSpace(shareId, nameState.trim(), descState.trim())
                                note = "已保存"
                                onSaved()
                            } catch (e: Exception) {
                                note = e.message ?: "保存失败"
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text(if (busy) "保存中…" else "保存", fontSize = 12.sp) }
                TextButton(onClick = onClose) { Text("关闭", fontSize = 12.sp) }
            }
        }
    )

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("确定删除「" + nameState.ifBlank { "空间 $shareId" } + "」？") },
            text = {
                Text(
                    "该空间的全部文档、历史版本、成员与文档级权限都会被删除，无法恢复。",
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    busy = true
                    cs.launch {
                        try {
                            KbApi.deleteShareSpace(shareId)
                            onDeleted()
                        } catch (e: Exception) {
                            note = e.message ?: "删除失败"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("取消") }
            }
        )
    }
}

private fun typeLabel(type: String): String = when (type.uppercase()) {
    "PERSONAL" -> "个人空间"
    "TEAM" -> "团队空间"
    "PROJECT" -> "项目空间"
    "PUBLIC" -> "公开空间"
    else -> type.ifBlank { "团队空间" }
}

private fun permLabelOf(perm: String): String = when (perm.uppercase()) {
    "OWNER" -> "所有者"
    "ADMIN" -> "可管理"
    "WRITE", "EDIT" -> "可编辑"
    "READ", "VIEW" -> "只读"
    else -> "只读"
}
