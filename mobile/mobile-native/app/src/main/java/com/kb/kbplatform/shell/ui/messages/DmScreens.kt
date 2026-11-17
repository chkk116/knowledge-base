/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.TokenStore
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.KbChip
import com.kb.kbplatform.shell.ui.common.KbEmpty
import com.kb.kbplatform.shell.ui.common.KbLoading
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.formatDateTime
import com.kb.kbplatform.shell.ui.misc.L2
import com.kb.kbplatform.shell.ui.theme.KbBlue
import com.kb.kbplatform.shell.ui.theme.KbRed
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 私信（对标 Web 端 /dm）：会话列表 + 单会话聊天。
 *
 * 后端口径（DmController）：
 *   会话按 peer 聚合（`lastMessage/lastAt/unread`），未互关时有条数限制（`quota.remaining`）；
 *   消息按 id 游标分页（`beforeId` 向上翻），撤回仅限自己 2 分钟内的消息。
 * 图片私信走 `/dm/attachment`（私有目录 + 需要登录校验），本屏先只做文本，图片入口留给 Web。
 */

// ============ 会话列表 ============

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DmListScreen(onBack: () -> Unit, openChat: (Long, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var archived by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    var refreshed by remember { mutableStateOf(0) }
    var delTarget by remember { mutableStateOf<JSONObject?>(null) }
    var msg by remember { mutableStateOf("") }

    fun load() {
        state = L2.Loading
        scope.launch {
            try {
                items = KbApi.dmConversations(archived).objects()
                state = if (items.isEmpty()) L2.Empty else L2.Ok
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                msg = e.message ?: "加载失败"
                state = L2.Empty
            }
        }
    }
    LaunchedEffect(archived, refreshed) { load() }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "私信", onBack = onBack, actions = {
            TextButton(onClick = { refreshed++ }) { Text("刷新", fontSize = 13.sp) }
        })
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KbChip("收件箱", selected = !archived, onClick = { archived = false })
            KbChip("归档", selected = archived, onClick = { archived = true })
        }
        if (msg.isNotEmpty()) {
            Text(msg, fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> if (msg.isNotEmpty()) KbEmpty("⚠️", msg) else KbEmpty("💬", if (archived) "归档里没有会话" else "还没有私信\n在用户主页点「私信」开始")
            L2.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(items, key = { i, it -> it.optLong("peerId", -1L).let { id -> if (id <= 0) "conv-$i" else "conv-$id" } }) { i, c ->
                    val unread = c.optInt("unread", 0)
                    KbCard(
                        Modifier.fillMaxWidth().combinedClickable(
                            onClick = { openChat(c.optLong("peerId", 0), c.optString("name")) },
                            onLongClick = { if (c.optLong("peerId", 0) > 0) delTarget = c },
                        )
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(38.dp)
                                    .background(KbBlue.copy(alpha = 0.14f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    c.optString("name").ifBlank { "?" }.take(1),
                                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KbBlue
                                )
                            }
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        c.optString("name").ifBlank { "已注销用户" },
                                        fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (unread > 0) {
                                        Spacer(Modifier.padding(horizontal = 4.dp))
                                        Box(
                                            Modifier.background(KbRed, CircleShape)
                                                .padding(horizontal = 6.dp, vertical = 1.dp)
                                        ) {
                                            Text("$unread", fontSize = 10.sp, color = Color.White)
                                        }
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Text(formatDateTime(c.optString("lastAt")), fontSize = 10.sp, color = KbTextDim)
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    (if (c.optBoolean("lastFromMe", false)) "我：" else "") +
                                        c.optString("lastMessage").replace("\n", " "),
                                    fontSize = 12.sp,
                                    color = if (unread > 0) MaterialTheme.colorScheme.onSurface else KbTextDim,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    delTarget?.let { c ->
        AlertDialog(
            onDismissRequest = { delTarget = null },
            title = { Text("删除会话") },
            text = { Text("将删除与「${c.optString("name")}」的会话（仅对你生效，对方不受影响）。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (runCatching { KbApi.dmDeleteConv(c.optLong("peerId", 0)) }.isSuccess) {
                            refreshed++
                        }
                        delTarget = null
                    }
                }) { Text("删除", color = KbRed) }
            },
            dismissButton = { TextButton(onClick = { delTarget = null }) { Text("取消") } }
        )
    }
}

// ============ 单会话聊天 ============

@Composable
fun DmChatScreen(peerId: Long, peerName: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val me = TokenStore.info.value?.optLong("id", 0L) ?: 0L
    var msgs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var state by remember { mutableStateOf(L2.Loading) }
    var quotaText by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var recallTarget by remember { mutableStateOf<JSONObject?>(null) }
    val listState = rememberLazyListState()

    fun load() {
        state = L2.Loading
        err = ""
        scope.launch {
            try {
                msgs = KbApi.dmHistory(peerId).objects()
                state = if (msgs.isEmpty()) L2.Empty else L2.Ok
                hasMore = msgs.isNotEmpty()
                runCatching { KbApi.dmMarkRead(peerId) }
                runCatching { KbApi.dmQuota(peerId) }.onSuccess { q ->
                    val limited = q.optBoolean("limited", false)
                    val remaining = q.optInt("remaining", -1)
                    quotaText = when {
                        !q.optBoolean("peerAllowDm", true) -> "对方关闭了私信接收"
                        limited && remaining >= 0 -> "未互关，还可发 $remaining 条"
                        else -> ""
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                err = e.message ?: "加载失败"
                state = L2.Empty
            }
        }
    }
    LaunchedEffect(peerId) { load() }
    // 新消息到来自动滚到底（列表是升序，末条即最新）
    // 只在「来了新消息」时滚到底；按 msgs.size 会让 loadMore 翻更早消息时也被拽回底部
    val newestId = msgs.lastOrNull()?.optLong("id", -1L) ?: -1L
    LaunchedEffect(newestId) {
        if (msgs.isNotEmpty()) runCatching { listState.animateScrollToItem(msgs.size - 1) }
    }

    fun loadMore() {
        if (loadingMore || !hasMore || msgs.isEmpty()) return
        val oldest = msgs.firstOrNull()?.optLong("id", 0) ?: return
        loadingMore = true
        scope.launch {
            runCatching { KbApi.dmHistory(peerId, beforeId = oldest).objects() }.onSuccess { older ->
                if (older.isEmpty()) hasMore = false
                else {
                    val known = msgs.map { it.optLong("id", 0) }.toSet()
                    msgs = older.filter { it.optLong("id", 0) !in known } + msgs
                }
            }
            loadingMore = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = peerName.ifBlank { "私信" }, onBack = onBack, actions = {
            TextButton(onClick = { load() }) { Text("刷新", fontSize = 13.sp) }
        })
        if (quotaText.isNotEmpty()) {
            Text(
                quotaText, fontSize = 11.sp, color = KbTextDim,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        if (err.isNotEmpty()) {
            Text(err, fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp))
        }

        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("💬", "还没有消息，打个招呼吧")
            L2.Ok -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    if (hasMore) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = { loadMore() }, enabled = !loadingMore) {
                                Text(if (loadingMore) "加载中…" else "加载更早的消息", fontSize = 12.sp)
                            }
                        }
                    }
                }
                // id 缺失或重复都不能让 LazyColumn 撞 key：一律拼上位置
                itemsIndexed(msgs, key = { i, it -> "msg-" + it.optLong("id", -1L) + "-" + i }) { i, m ->
                    val mine = m.optLong("fromId", 0) == me
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                    ) {
                        Box(
                            Modifier
                                .widthIn(max = 280.dp)
                                .background(
                                    if (mine) KbBlue else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable(enabled = mine) { recallTarget = m }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                m.optString("content"),
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                                color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatDateTime(m.optString("createdAt")), fontSize = 10.sp, color = KbTextDim)
                            if (mine) {
                                Spacer(Modifier.size(4.dp))
                                Text(if (m.optBoolean("read", false)) "已读" else "未读", fontSize = 10.sp, color = KbTextDim)
                            }
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("发消息…", fontSize = 14.sp) },
                maxLines = 3,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            TextButton(
                enabled = input.isNotBlank() && !sending,
                onClick = {
                    val text = input.trim()
                    if (text.isEmpty()) return@TextButton
                    sending = true
                    scope.launch {
                        runCatching { KbApi.dmSend(peerId, text) }
                            .onSuccess {
                                input = ""
                                err = ""
                                load()
                            }
                            .onFailure { err = it.message ?: "发送失败" }
                        sending = false
                    }
                },
            ) { Text(if (sending) "…" else "发送") }
        }
    }

    recallTarget?.let { m ->
        AlertDialog(
            onDismissRequest = { recallTarget = null },
            title = { Text("撤回消息") },
            text = { Text("仅能撤回自己 2 分钟内发送的消息，确定撤回？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { KbApi.dmRecall(m.optLong("id", 0)) }
                            .onFailure { err = it.message ?: "撤回失败" }
                        recallTarget = null
                        load()
                    }
                }) { Text("撤回", color = KbRed) }
            },
            dismissButton = { TextButton(onClick = { recallTarget = null }) { Text("取消") } }
        )
    }
}