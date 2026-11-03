/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.TokenStore
import com.kb.kbplatform.shell.data.ApiError
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.CenterHint
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.theme.KbRed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * AI 问答（原生版，2026-11-03 替换原「内嵌 /m/ai 网页」方案）。
 *
 * 决策变更：此前因原生复刻流式 Markdown / ReAct 成本高而内嵌 WebView；现在
 * - POST /ai/ask/stream（SSE）协议稳定，KbApi.aiAskStream 已覆盖
 * - MarkdownLite 覆盖 AI 回答 95% 的语法（段落/加粗/行内代码/代码块/标题/列表）
 * - 模型选择与 Web 同源（/ai/models：系统 + 用户私有），模式切换 kb/general
 * - 会话管理走 /ai/conversations 系列，历史记录完整可达
 *
 * 未覆盖（刻意取舍）：ReAct 工具调用轨迹、图片多模态、会话重命名/批量删除——
 * 这些继续用 Web 版；原生版聚焦「快进快出的随手问答」。
 */
private data class AiMsg(
    val role: String,          // user | assistant
    val content: String,
    val modelName: String = "",
    val streaming: Boolean = false
)

private data class AiModelOpt(val id: Long, val name: String, val scope: String)

private data class AiConv(val id: Long, val title: String, val updatedAt: String)

@Composable
fun AiChatScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val msgs = remember { mutableStateListOf<AiMsg>() }
    var convId by rememberSaveable { mutableStateOf<Long?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }   // 当前流式请求，支持「停止生成」
    var error by remember { mutableStateOf("") }
    var initialLoading by remember { mutableStateOf(true) }

    // 模型 / 模式
    var models by remember { mutableStateOf<List<AiModelOpt>>(emptyList()) }
    var modelsLoaded by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf<AiModelOpt?>(null) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf("kb") }   // kb=知识库检索 general=通用问答

    // 历史会话
    var convOpen by remember { mutableStateOf(false) }
    var convs by remember { mutableStateOf<List<AiConv>>(emptyList()) }
    var convsLoading by remember { mutableStateOf(false) }

    val loggedIn = TokenStore.token.collectAsState().value != null

    LaunchedEffect(loggedIn) {
        if (!loggedIn) { initialLoading = false; return@LaunchedEffect }
        try {
            val arr = KbApi.aiModels()
            models = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    AiModelOpt(
                        id = o.optLong("id"),
                        name = o.optString("name").ifBlank { o.optString("model") },
                        scope = o.optString("scope", "system")
                    )
                }
            }
        } catch (e: Exception) { /* 模型列表失败不阻塞聊天，默认模型兜底 */ }
        modelsLoaded = true
        initialLoading = false
    }

    // 流式输出时跟随滚到底部
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.content?.length) {
        if (msgs.isNotEmpty()) listState.scrollToItem(msgs.lastIndex)
    }

    fun loadConversations() {
        convsLoading = true
        scope.launch {
            try {
                convs = KbApi.aiConversations().objects().mapNotNull { o ->
                    val id = o.optLong("id")
                    if (id <= 0) null else AiConv(id, o.optString("title", "未命名"), o.optString("updatedAt"))
                }
            } catch (e: Exception) { /* 列表失败保持空 */ }
            convsLoading = false
        }
    }

    fun openConversation(id: Long) {
        convOpen = false
        sending = true
        scope.launch {
            try {
                val arr = KbApi.aiMessages(id)
                val loaded = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { o ->
                        AiMsg(
                            role = o.optString("role", "assistant").ifBlank { "assistant" },
                            content = o.optString("content")
                        )
                    }
                }
                msgs.clear()
                msgs.addAll(loaded)
                convId = id
                error = ""
            } catch (e: Exception) {
                error = e.message ?: "会话加载失败"
            } finally { sending = false }
        }
    }

    fun newChat() {
        msgs.clear()
        convId = null
        error = ""
        input = ""
    }

    fun send() {
        val q = input.trim()
        if (q.isEmpty() || sending) return
        if (!loggedIn) { error = "请先登录后再使用 AI 问答"; return }
        input = ""
        error = ""
        msgs.add(AiMsg("user", q))
        msgs.add(AiMsg("assistant", "", streaming = true))
        val idx = msgs.lastIndex
        sending = true
        val sysId = selectedModel?.takeIf { it.scope != "user" }?.id
        val userId = selectedModel?.takeIf { it.scope == "user" }?.id
        job = scope.launch {
            try {
                val res = KbApi.aiAskStream(
                    question = q, conversationId = convId, mode = mode,
                    modelId = sysId, userModelId = userId,
                    onDelta = { d ->
                        val cur = msgs[idx]
                        msgs[idx] = cur.copy(content = cur.content + d)
                    },
                    onReasoning = { /* 思考过程暂不展示，避免长文本挤压对话区 */ }
                )
                convId = res.optLong("conversationId").takeIf { it > 0L }
                val cur = msgs[idx]
                val finalText = cur.content.ifBlank {
                    res.optString("answer").ifBlank { "（暂无返回内容）" }
                }
                msgs[idx] = cur.copy(content = finalText, modelName = res.optString("modelName"), streaming = false)
            } catch (e: CancellationException) {
                // 用户主动停止：保留已生成的部分回答，而不是整条丢弃
                val cur = msgs.getOrNull(idx)
                if (cur != null) {
                    if (cur.content.isBlank()) msgs.removeAt(idx)
                    else msgs[idx] = cur.copy(streaming = false)
                }
                throw e
            } catch (e: Exception) {
                val msg = (e as? ApiError)?.message ?: e.message ?: "请求失败，请检查网络"
                val cur = msgs[idx]
                msgs[idx] = if (cur.content.isBlank()) {
                    cur.copy(content = "出错了：$msg", streaming = false)
                } else {
                    cur.copy(streaming = false)   // 已有部分内容：保留已生成部分
                }
            } finally { sending = false }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopBar(title = "AI 问答", onBack = onBack) {
            // 新建对话：放顶栏右侧显眼位（大厂惯例），不再藏在横向 chip 行里
            IconButton(onClick = { newChat() }, enabled = !sending) {
                Icon(Icons.Filled.Add, contentDescription = "新建对话", modifier = Modifier.size(22.dp))
            }
        }

        // 模型 / 模式 / 历史行（横向滚动，窄屏不溢出）
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                AssistChip(
                    onClick = { modelMenuOpen = true },
                    label = {
                        Text(
                            "模型：" + (selectedModel?.name ?: "默认模型") +
                                (selectedModel?.let { if (it.scope == "user") " · 我的" else "" } ?: "")
                        )
                    },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    enabled = !sending,
                )
                DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("默认模型") },
                        onClick = { selectedModel = null; modelMenuOpen = false },
                    )
                    models.forEach { m ->
                        DropdownMenuItem(
                            text = { Text("${m.name}（${if (m.scope == "user") "我的" else "系统"}）") },
                            onClick = { selectedModel = m; modelMenuOpen = false },
                        )
                    }
                }
            }
            AssistChip(
                onClick = { mode = if (mode == "kb") "general" else "kb" },
                label = { Text(if (mode == "kb") "知识库检索" else "通用问答") },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = MaterialTheme.colorScheme.primary,
                ),
                border = AssistChipDefaults.assistChipBorder(
                    enabled = true,
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                ),
                enabled = !sending,
            )
            AssistChip(
                onClick = { loadConversations(); convOpen = true },
                label = { Text("历史会话") },
                leadingIcon = { Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(16.dp)) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }

        // 消息区
        when {
            initialLoading -> CenterHint("加载中…", loading = true)
            !loggedIn -> CenterHint("请先登录后再使用 AI 问答")
            msgs.isEmpty() -> Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.AutoAwesome, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    modifier = Modifier.size(42.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("有什么可以帮你？", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text(
                    "基于知识库检索回答，可切换模型与模式",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(22.dp))
                // 推荐问题：点击直接发送（大厂 AI 界面的冷启动引导）
                listOf(
                    "知识库里的文档怎么检索最快？",
                    "帮我总结最近的文章要点",
                    "什么是间隔重复记忆法？",
                    "给我一份周报模板",
                ).forEach { q ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clickable(enabled = loggedIn && !sending) { input = q; send() },
                    ) {
                        Text(
                            q, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(msgs) { _, m ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 8 },
                    ) {
                        if (m.role == "user") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                ) {
                                    Text(
                                        m.content,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontSize = 14.5.sp,
                                        lineHeight = 22.sp,
                                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                                    )
                                }
                            }
                        } else {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                                        MarkdownLite(
                                            m.content + if (m.streaming) " ▍" else "",
                                        )
                                    }
                                }
                                if (m.modelName.isNotBlank() && !m.streaming) {
                                    Text(
                                        m.modelName,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 6.dp, top = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (error.isNotBlank()) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // 输入区
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("想问点什么…", fontSize = 14.sp) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
                minLines = 1,
                maxLines = 4,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.5.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )
            if (sending) {
                // 流式中：发送键变「停止」，点了取消本次生成（已生成的部分保留）
                Button(
                    onClick = { job?.cancel() },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.height(48.dp),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "停止生成", modifier = Modifier.size(18.dp))
                }
            } else {
                Button(
                    onClick = { send() },
                    enabled = input.isNotBlank() && loggedIn,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.height(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    // 历史会话弹层
    if (convOpen) {
        AlertDialog(
            onDismissRequest = { convOpen = false },
            title = { Text("历史会话", fontWeight = FontWeight.SemiBold) },
            text = {
                if (convsLoading) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
                    }
                } else if (convs.isEmpty()) {
                    Text("还没有会话记录", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.height(360.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        itemsIndexed(convs) { _, c ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { openConversation(c.id) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.title, fontSize = 14.sp, maxLines = 1, fontWeight = FontWeight.Medium)
                                    Text(
                                        c.updatedAt, fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        try {
                                            KbApi.aiDeleteConversation(c.id)
                                            convs = convs.filterNot { it.id == c.id }
                                            if (convId == c.id) newChat()
                                        } catch (e: Exception) { /* 删除失败保持原状 */ }
                                    }
                                }) {
                                    Icon(
                                        Icons.Filled.DeleteOutline, contentDescription = "删除",
                                        tint = KbRed, modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { convOpen = false }) { Text("关闭") }
            },
        )
    }
}
