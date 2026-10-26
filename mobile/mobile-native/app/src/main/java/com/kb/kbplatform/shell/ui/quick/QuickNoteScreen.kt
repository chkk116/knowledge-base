/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.quick

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.kb.kbplatform.shell.ui.common.KbButton
import com.kb.kbplatform.shell.ui.common.TopBar
import kotlinx.coroutines.launch

/**
 * 速记（对标 MobileQuickNote）：一段话直接收进「待整理箱」（个人空间 + inbox=true），
 * 标题取首行前 12 字，之后在待整理箱集中补标题、归档。
 */
@Composable
fun QuickNoteScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var doneMsg by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    fun save() {
        val t = text.trim()
        if (t.isEmpty() || saving) return
        saving = true
        error = ""
        scope.launch {
            try {
                val sid = KbApi.personalShare().optLong("id", 0)
                val title = t.lineSequence().firstOrNull()?.take(12) ?: "速记"
                val body = t.split(Regex("\n{2,}"))
                    .filter { it.isNotBlank() }
                    .joinToString("") { "<p>${it.replace("\n", "<br>")}</p>" }
                KbApi.createShareDoc(sid, title, body, inbox = true)
                doneMsg = "已收进待整理箱"
            } catch (e: Exception) {
                error = e.message ?: "保存失败（已存为离线草稿）"
                val sidFallback = runCatching { KbApi.personalShare().optLong("id", 0) }.getOrDefault(0L)
                val draftTitle = t.lineSequence().firstOrNull()?.take(12) ?: "速记"
                com.kb.kbplatform.shell.data.DraftStore.enqueue(ctx, sidFallback, draftTitle, t)
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "速记", onBack = onDone)
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            if (doneMsg.isNotEmpty()) {
                Spacer(Modifier.height(60.dp))
                Text("✅", fontSize = 40.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
                Text(doneMsg, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { doneMsg = ""; text = "" }, modifier = Modifier.weight(1f)) { Text("再记一条") }
                    Spacer(Modifier.padding(start = 10.dp))
                    Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("完成") }
                }
            } else {
                Text(
                    "想到什么写什么，先收进待整理箱",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("一句话、一个想法、一段摘抄…") },
                    minLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(14.dp))
                KbButton(
                    text = if (saving) "保存中…" else "收进待整理箱",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = text.isNotBlank() && !saving,
                    onClick = { save() }
                )
            }
        }
    }
}
