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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.SseClient
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.formatDateTime
import com.kb.kbplatform.shell.ui.theme.KbBlue
import com.kb.kbplatform.shell.ui.theme.KbRed
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 消息 Tab：通知列表（GET /notifications，倒序）。
 * SSE 到达自动刷新（SseClient.events）；进页用 /notifications/unread 校准角标。
 *
 * 手势（2026-10-28 加）：
 *   - 左滑（EndToStart）→ 标记已读
 *   - 右滑（StartToEnd）→ 删除
 * 两个方向都**不真正移除条目**（confirmValueChange 返回 false，卡片回弹），
 * 状态由我们自己改：删除立即从列表移除，已读只降透明度 —— 避免手势误触把消息滑没了。
 *
 * 视觉：未读左侧蓝点 + 正常不透明度；已读整条降到 60%，一眼能分出哪些看过。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(openRoute: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var dmUnread by remember { mutableIntStateOf(0) }

    suspend fun load() {
        loading = true
        error = ""
        try {
            items = KbApi.notifications().objects()
            SseClient.setUnread(KbApi.unreadCount())
            dmUnread = runCatching { KbApi.dmUnread() }.getOrDefault(0)
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

    // 标已读：乐观更新（先改 UI 再发请求），失败也不回滚——下次刷新会以服务端为准
    fun markRead(id: Long) {
        val wasUnread = items.any { it.optLong("id", 0) == id && !it.optBoolean("read", true) }
        if (!wasUnread) return
        items = items.map {
            if (it.optLong("id", 0) == id) JSONObject(it.toString()).put("read", true) else it
        }
        SseClient.decrementUnread()
        scope.launch { runCatching { KbApi.markNotificationRead(id) } }
    }

    // 删除：先从列表移除（手感即时），失败就重载把那条找回来
    fun deleteOne(id: Long) {
        val wasUnread = items.any { it.optLong("id", 0) == id && !it.optBoolean("read", true) }
        items = items.filter { it.optLong("id", 0) != id }
        if (wasUnread) SseClient.decrementUnread()
        scope.launch {
            val r = runCatching { KbApi.deleteNotification(id) }
            if (r.isFailure) refreshKey++
        }
    }

    Column(Modifier.fillMaxSize()) {
        com.kb.kbplatform.shell.ui.common.KbPageHeader(title = "消息") {
            IconButton(
                enabled = items.any { !it.optBoolean("read", true) },
                onClick = {
                    items = items.map { JSONObject(it.toString()).put("read", true) }
                    SseClient.setUnread(0)
                    scope.launch {
                        val r = runCatching { KbApi.markAllNotificationsRead() }
                        if (r.isFailure) refreshKey++
                    }
                }
            ) {
                Icon(Icons.Filled.Done, contentDescription = "全部已读")
            }
            IconButton(onClick = { refreshKey++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }
        // 私信入口：通知流之外单独一条，带未读角标（性能上就是一次 /dm/unread）
        KbCard(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clickable { openRoute("dm") }
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("💬", fontSize = 18.sp)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("私信", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (dmUnread > 0) "$dmUnread 条未读" else "与作者的私密对话",
                        fontSize = 12.sp, color = KbTextDim
                    )
                }
                if (dmUnread > 0) {
                    Box(
                        Modifier.background(KbRed, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp)
                    ) { Text("$dmUnread", fontSize = 10.sp, color = Color.White) }
                }
            }
        }

        // 邮箱入口：收件箱 / 草稿箱 / 写邮件都在原生页里（后端 /api/mail）
        KbCard(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp)
                .clickable { openRoute("mail") }
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("📮", fontSize = 18.sp)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("邮箱", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("收件箱 / 草稿箱 / 写邮件", fontSize = 12.sp, color = KbTextDim)
                }
            }
        }

        if (loading && items.isEmpty()) {
            // 骨架屏：消息列表版式先出来（原来转圈，切 Tab 时一片空白）
            com.kb.kbplatform.shell.ui.common.KbSkeletonList(count = 6)
        } else if (error.isNotEmpty() && items.isEmpty()) {
            com.kb.kbplatform.shell.ui.common.KbEmpty("⚠️", error)
        } else if (items.isEmpty()) {
            com.kb.kbplatform.shell.ui.common.KbEmpty("🔔", "暂无消息，有人 @ 你时会出现在这里")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items, key = { it.optLong("id", it.hashCode().toLong()) }) { n ->
                    val id = n.optLong("id", 0)
                    val read = n.optBoolean("read", true)

                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { v ->
                            when (v) {
                                SwipeToDismissBoxValue.StartToEnd -> deleteOne(id)   // 右滑删除
                                SwipeToDismissBoxValue.EndToStart -> markRead(id)    // 左滑标已读
                                else -> Unit
                            }
                            false   // 不让卡片真的滑走，状态由数据决定
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        modifier = Modifier.fillMaxWidth(),
                        enableDismissFromStartToEnd = true,
                        enableDismissFromEndToStart = !read,
                        backgroundContent = {
                            // ⚠️ SwipeToDismissBox 的 backgroundContent 是常驻合成的（不是滑了才画）。
                            // 已读条目整卡 alpha(0.6)，静止时背后那行「标已读/删除」会透过半透明卡片
                            // 与正文叠加 —— 表现为「通知的文字覆盖标记按钮」。静止态（Settled）不画背景。
                            if (dismissState.dismissDirection != SwipeToDismissBoxValue.Settled) {
                                val del = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                                Row(
                                    Modifier
                                        .fillMaxSize()
                                        .background(
                                            if (del) MaterialTheme.colorScheme.error else KbBlue,
                                            RoundedCornerShape(14.dp)
                                        )
                                        .padding(horizontal = 18.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = if (del) Arrangement.Start else Arrangement.End
                                ) {
                                    Icon(
                                        if (del) Icons.Filled.Delete else Icons.Filled.MarkEmailRead,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.size(6.dp))
                                    Text(
                                        if (del) "删除" else "标已读",
                                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    ) {
                        KbCard(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (read) 0.6f else 1f)
                                .clickable(enabled = !read) { markRead(id) }
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
}
