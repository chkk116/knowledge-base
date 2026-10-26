/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.kb.kbplatform.shell.data.ShareInbox
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.CenterHint
import com.kb.kbplatform.shell.ui.common.TopBar
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 分享导入（原生）：与 Web 版 MobileShareImport 同源流程——
 * 收到的文件逐个 上传(/api/files) → 建文档(POST /doc-shares/{id}/docs)；
 * 可选目标空间，或一键「收进待整理箱」（个人空间 + inbox=true）。
 */
@Composable
fun ShareImportScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pending by ShareInbox.pending.collectAsState()
    val shareText by ShareInbox.text.collectAsState()

    var spaces by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var chosen by remember { mutableIntStateOf(-1) }
    var loading by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var doneCount by remember { mutableIntStateOf(0) }
    var failCount by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var finishedMsg by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            spaces = KbApi.myShares().objects()
        } catch (e: Exception) {
            error = e.message ?: "加载空间失败"
        } finally {
            loading = false
        }
    }

    fun titleOf(name: String): String = name.substringBeforeLast('.').ifBlank { name }

    /** 图片嵌正文，其他类型落附件链接——与 Web 版 buildContent 的产出一致 */
    fun buildContent(mime: String, url: String): String =
        if (mime.startsWith("image/")) "<p><img src=\"$url\" style=\"max-width:100%\"/></p>"
        else "<p><a href=\"$url\">${url.substringAfterLast('/')}</a></p>"

    fun importAll(inbox: Boolean) {
        if (importing) return
        importing = true
        doneCount = 0
        failCount = 0
        scope.launch {
            try {
                val sid: Long = if (inbox) {
                    KbApi.personalShare().optLong("id", 0)
                } else {
                    spaces.getOrNull(chosen)?.optLong("id", 0) ?: 0L
                }
                if (sid <= 0) throw IllegalStateException("请先选择目标空间")

                for (f in pending) {
                    try {
                        val up = KbApi.uploadFile(f.file, f.mime)
                        val url = up.optJSONObject("data")?.optString("url")
                            ?: up.optString("url")
                        if (url.isBlank()) throw IllegalStateException("上传失败")
                        KbApi.createShareDoc(sid, titleOf(f.file.name), buildContent(f.mime, url), inbox)
                        doneCount++
                    } catch (e: Exception) {
                        failCount++
                    }
                }
                // 分享文本单独存一篇速记
                val t = shareText
                if (t != null && pending.isEmpty()) {
                    runCatching {
                        val sid2 = sid
                        KbApi.createShareDoc(sid2, "分享速记", "<p>$t</p>", inbox)
                    }
                }
                ShareInbox.clear()
                finishedMsg = "已导入 $doneCount 个" + (if (failCount > 0) "，$failCount 个失败" else "")
            } catch (e: Exception) {
                error = e.message ?: "导入失败"
            } finally {
                importing = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "导入分享", onBack = {
            ShareInbox.clear()
            onDone()
        })

        if (finishedMsg.isNotEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(60.dp))
                Text("✅", fontSize = 40.sp)
                Spacer(Modifier.height(10.dp))
                Text(finishedMsg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onDone) { Text("完成") }
            }
        } else if (loading) {
            CenterHint("加载中…", loading = true)
        } else if (pending.isEmpty() && shareText == null) {
            CenterHint("没有待导入的分享内容\n从微信或文件管理器「分享到知识库」试试")
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        "收到 ${pending.size} 个文件" + (shareText?.let { " + 1 段文本" } ?: ""),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                items(pending, key = { it.file.absolutePath }) { f ->
                    KbCard(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📄", fontSize = 18.sp)
                            Spacer(Modifier.size(10.dp))
                            Column {
                                Text(f.file.name.substringAfter('_'), fontSize = 14.sp, maxLines = 1)
                                Text(
                                    formatKb(f.file.length()),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            KbCard(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable { showPicker = true },
            ) {
                Row(Modifier.padding(14.dp)) {
                    Text("导入到空间", fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        spaces.getOrNull(chosen)?.optString("name").orEmpty().ifBlank { "选择空间" },
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { importAll(inbox = true) },
                    enabled = !importing,
                    modifier = Modifier.weight(1f)
                ) { Text(if (importing) "导入中…" else "收进待整理箱") }
                Button(
                    onClick = { importAll(inbox = false) },
                    enabled = !importing && chosen >= 0,
                    modifier = Modifier.weight(1f)
                ) { Text(if (importing) "$doneCount/${pending.size}" else "导 入") }
            }
            if (error.isNotEmpty()) {
                Text(
                    error,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
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
                            s.optString("name").ifBlank { "未命名空间" } + "（${s.optString("type")}）",
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { chosen = i; showPicker = false }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            }
        )
    }
}

private fun formatKb(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
