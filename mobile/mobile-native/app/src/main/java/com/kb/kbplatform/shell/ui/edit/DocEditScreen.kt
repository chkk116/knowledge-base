/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.ui.common.KbButton
import com.kb.kbplatform.shell.ui.common.TopBar
import kotlinx.coroutines.launch

/**
 * 文档编辑（原生）· 编辑器 = compose-rich-editor（GitHub 活跃维护，HTML 导入导出），
 * 保存走 REST PUT（后写赢）。与 Web 端 Tiptap+Yjs 的「多人实时协同」不同：
 * 原生版为单人编辑保存；协同编辑仍可在 Web 端进行，双方内容互通（同一存储）。
 */
@Composable
fun DocEditScreen(shareId: Long, docId: Long, titleHint: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val richState = remember { RichTextState() }
    var title by remember { mutableStateOf(titleHint) }
    var loaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var savedAt by remember { mutableStateOf("") }

    LaunchedEffect(shareId, docId) {
        try {
            val d = KbApi.getShareDoc(shareId, docId)
            title = d.optString("title").ifBlank { titleHint }
            richState.setHtml(d.optString("content"))
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
            loaded = true
        }
    }

    fun save() {
        if (saving) return
        saving = true
        error = ""
        scope.launch {
            try {
                KbApi.updateShareDoc(shareId, docId, title.trim(), richState.toHtml())
                savedAt = "已保存"
            } catch (e: Exception) {
                error = e.message ?: "保存失败"
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "编辑", onBack = onBack) {
            Text(
                if (saving) "保存中…" else if (savedAt.isNotEmpty()) savedAt else "",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 6.dp)
            )
            androidx.compose.material3.TextButton(
                onClick = { save() },
                enabled = loaded && !saving
            ) { Text("保存", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }

        if (loading) {
            com.kb.kbplatform.shell.ui.common.KbLoading()
        } else {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("文档标题") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "原生编辑为单人保存模式 · 需要多人实时协同请在 Web 端编辑",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                BasicRichTextEditor(
                    state = richState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                KbButton(
                    text = if (saving) "保存中…" else "保存文档",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = loaded && !saving,
                    onClick = { save() }
                )
            }
        }
    }
}
