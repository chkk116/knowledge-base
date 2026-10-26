/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.kb.kbplatform.shell.data.SseClient
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.CenterHint
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.formatDateTime
import com.kb.kbplatform.shell.ui.theme.KbBlue
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 消息 Tab：通知列表（GET /notifications，倒序）。
 * SSE 到达自动刷新（SseClient.events）；进页用 /notifications/unread 校准角标；
 * 点一条标记已读（POST /{id}/read，乐观更新 + 角标减一）。
 */
@Composable
fun MessagesScreen() {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }

    suspend fun load() {
        loading = true
        error = ""
        try {
            items = KbApi.notifications().objects()
            SseClient.setUnread(KbApi.unreadCount())
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    // SSE 新通知：刷列表（refreshKey 触发重载）
    LaunchedEffect(Unit) {
        SseClient.events.collect { refreshKey++ }
    }
    LaunchedEffect(refreshKey) {
        if (refreshKey > 0) load()
    }

    Column(Modifier.fillMaxSize()) {
        com.kb.kbplatform.shell.ui.common.KbPageHeader(title = "消息") {
            IconButton(onClick = { refreshKey++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }
        if (loading && items.isEmpty()) {
            com.kb.kbplatform.shell.ui.common.KbLoading()
        } else if (error.isNotEmpty() && items.isEmpty()) {
            com.kb.kbplatform.shell.ui.common.KbEmpty("⚠️", error)
        } else if (items.isEmpty()) {
            com.kb.kbplatform.shell.ui.common.KbEmpty("🔔", "暂无消息，有人 @ 你时会出现在这里")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.optLong("id", it.hashCode().toLong()) }) { n ->
                    val id = n.optLong("id", 0)
                    val read = n.optBoolean("read", true)
                    KbCard(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !read) {
                                // 乐观更新：先置已读再发请求，失败不打断（下次刷新恢复）
                                items = items.map {
                                    if (it.optLong("id", 0) == id) JSONObject(it.toString()).put("read", true) else it
                                }
                                SseClient.decrementUnread()
                                scope.launch { runCatching { KbApi.markNotificationRead(id) } }
                            }
                    ) {
                        Row(Modifier.padding(14.dp)) {
                            if (!read) {
                                Box(
                                    Modifier
                                        .padding(top = 6.dp)
                                        .size(8.dp)
                                        .background(KbBlue, CircleShape)
                                )
                                Spacer(Modifier.size(10.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    n.optString("actor").ifBlank { "系统" },
                                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                                )
                                if (n.optString("summary").isNotEmpty()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        n.optString("summary"),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    formatDateTime(n.optString("createdAt")),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
