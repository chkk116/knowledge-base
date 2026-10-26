/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.draft

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.kb.kbplatform.shell.data.DraftStore
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.ui.common.KbButton
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.KbEmpty
import com.kb.kbplatform.shell.ui.common.KbLoading
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.formatDateTime
import com.kb.kbplatform.shell.ui.theme.KbRed
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 离线草稿列表（对标 MobileHome 的待同步卡 → 草稿队列） */
@Composable
fun DraftsScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(DraftStore.list(ctx)) }
    var syncing by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<DraftStore.Draft?>(null) }

    fun refresh() { items = DraftStore.list(ctx) }

    fun syncAll() {
        if (syncing) return
        syncing = true
        scope.launch {
            var ok = 0
            var fail = 0
            for (d in DraftStore.list(ctx)) {
                if (DraftStore.upload(ctx, d)) {
                    DraftStore.remove(ctx, d.localId)
                    ok++
                } else fail++
            }
            syncing = false
            msg = "同步完成：成功 $ok${if (fail > 0) "，失败 $fail（稍后重试）" else ""}"
            refresh()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "离线草稿", onBack = onBack) {
            TextButton(onClick = { syncAll() }, enabled = items.isNotEmpty() && !syncing) {
                Text(if (syncing) "同步中…" else "全部同步", fontSize = 13.sp)
            }
        }
        if (msg.isNotEmpty()) {
            Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (items.isEmpty()) {
            KbEmpty("📭", "没有待同步的草稿\n断网时保存的内容会自动进入这里")
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.localId }) { d ->
                    KbCard(Modifier.fillMaxWidth().clickable { detail = d }) {
                        Column(Modifier.padding(14.dp)) {
                            Text(d.title.ifBlank { "未命名草稿" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOf(
                                    formatDateTime(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(Date(d.createdAt))),
                                    if (d.tries > 0) "重试 ${d.tries} 次" else "待上传",
                                    d.lastError.takeIf { it.isNotEmpty() } ?: "联网后上传"
                                ).joinToString(" · "),
                                fontSize = 11.sp, color = if (d.lastError.isNotEmpty()) KbRed else KbRed.copy(alpha = 0.001f).let { KbRed }.let { MaterialTheme.colorScheme.onSurfaceVariant }
                            )
                        }
                    }
                }
            }
        }
    }

    detail?.let { d ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(d.title.ifBlank { "未命名草稿" }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        d.content,
                        fontSize = 13.sp,
                        maxLines = 12
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "创建于 " + formatDateTime(
                            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(Date(d.createdAt))
                        ) + if (d.lastError.isNotEmpty()) " · 上次错误：${d.lastError}" else "",
                        fontSize = 11.sp, color = KbRed
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (DraftStore.upload(ctx, d)) {
                            DraftStore.remove(ctx, d.localId)
                            msg = "已上传"
                            refresh()
                            detail = null
                        } else {
                            msg = "上传失败，请检查网络后重试"
                            detail = null
                            refresh()
                        }
                    }
                }) { Text("立即上传") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        DraftStore.remove(ctx, d.localId)
                        refresh()
                        detail = null
                    }) { Text("删除", color = KbRed) }
                    TextButton(onClick = { detail = null }) { Text("关闭") }
                }
            }
        )
    }
}
