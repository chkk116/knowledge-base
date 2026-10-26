/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.clip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.KbButton
import com.kb.kbplatform.shell.ui.common.TopBar
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 网页剪藏（对标 MobileWebClip）：粘贴链接 → 服务端抓正文预览（POST /clip/preview）→
 * 选空间 → createShareDoc 保存。权限/配额由后端保存路径统一处理。
 */
@Composable
fun ClipScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var spaces by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var chosen by remember { mutableIntStateOf(-1) }
    var showPicker by remember { mutableStateOf(false) }
    var doneMsg by remember { mutableStateOf("") }

    fun doPreview() {
        val u = url.trim()
        if (u.isEmpty() || loading) return
        loading = true
        error = ""
        preview = null
        scope.launch {
            try {
                preview = KbApi.clipPreview(u)
                runCatching { spaces = KbApi.myShares().objects() }
            } catch (e: Exception) {
                error = e.message ?: "抓取失败"
            } finally {
                loading = false
            }
        }
    }

    fun save() {
        val p = preview ?: return
        if (saving) return
        saving = true
        error = ""
        scope.launch {
            try {
                val sid = if (chosen >= 0) spaces.getOrNull(chosen)?.optLong("id", 0) ?: 0L
                else KbApi.personalShare().optLong("id", 0)
                if (sid <= 0) throw IllegalStateException("请先选择目标空间")
                KbApi.createShareDoc(
                    sid,
                    p.optString("title").ifBlank { p.optString("url") },
                    p.optString("content")
                )
                doneMsg = "已剪藏到空间"
            } catch (e: Exception) {
                error = e.message ?: "保存失败"
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "网页剪藏", onBack = onDone)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (doneMsg.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✅", fontSize = 40.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(doneMsg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(20.dp))
                    KbButton(text = "完成", modifier = Modifier.fillMaxWidth(), onClick = { onDone() })
                }
            } else {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("粘贴网页链接 https://…") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = { doPreview() }, enabled = url.isNotBlank() && !loading) {
                            Text(if (loading) "抓取中…" else "抓取")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }

                preview?.let { p ->
                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(
                                p.optString("title").ifBlank { "无标题" },
                                fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                            )
                            if (p.optString("site").isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text(p.optString("site"), fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                p.optString("excerpt").ifBlank { "（无摘要）" },
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 6
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showPicker = true }
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Text("存入空间：", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            spaces.getOrNull(chosen)?.optString("name").orEmpty().ifBlank { "我的文档（默认）" },
                            fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    KbButton(text = if (saving) "保存中…" else "保存为文档", modifier = Modifier.fillMaxWidth(), enabled = !saving, onClick = {
                        save()
                    })
                }
            }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择目标空间") },
            text = {
                Column {
                    spaces.forEachIndexed { i, s ->
                        Text(
                            s.optString("name").ifBlank { "未命名空间" },
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { chosen = i; showPicker = false }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } }
        )
    }
}
