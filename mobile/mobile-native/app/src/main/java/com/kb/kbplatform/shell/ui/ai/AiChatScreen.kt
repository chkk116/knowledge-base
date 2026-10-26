/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List as HistoryIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.formatDateTime
import com.kb.kbplatform.shell.ui.theme.KbBlue
import com.kb.kbplatform.shell.ui.theme.KbBlueSoft
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class ChatMsg(
    val role: String,
    val text: String,
    val sources: List<JSONObject> = emptyList(),
    val meta: String = ""
)

private val MODE_CN = mapOf("kb" to "知识库", "general" to "通用")

/**
 * AI 问答（原生）· UI 对标套壳 MobileAiChat：
 * 顶部 返回/标题/历史/新建；模式切换（知识库/通用）+ 模型选择抽屉；
 * 气泡消息流（Markdown 轻渲染）+ 引用来源 + 模型元信息；历史会话底部抽屉。
 * 后端 /ai/ask 为一次性完整回答（非流式）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var models by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var modelIdx by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf("kb") }
    var conversations by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var convTitle by remember { mutableStateOf("") }
    var conversationId by remember { mutableStateOf<Long?>(null) }
    var messages by remember { mutableStateOf<List<ChatMsg>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<JSONObject?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            models = KbApi.aiModels().objects()
            modelIdx = models.indexOfFirst { it.optBoolean("isDefault", false) }
                .let { if (it >= 0) it else 0 }
        }
        runCatching { conversations = KbApi.aiConversations().objects() }
    }

    fun loadConversation(c: JSONObject) {
        conversationId = c.optLong("id", 0)
        convTitle = c.optString("title")
        showHistory = false
        scope.launch {
            try {
                messages = KbApi.aiMessages(conversationId!!).objects().map { m ->
                    ChatMsg(
                        role = m.optString("role"),
                        text = m.optString("content"),
                        sources = m.optJSONArray("sources")?.objects() ?: emptyList()
                    )
                }
            } catch (e: Exception) {
                messages = emptyList()
            }
        }
    }

    fun newConversation() {
        conversationId = null
        convTitle = ""
        messages = emptyList()
        showHistory = false
    }

    fun ask() {
        val q = input.trim()
        if (q.isEmpty() || asking) return
        input = ""
        if (conversationId == null && convTitle.isEmpty()) convTitle = q.take(20)
        messages = messages + ChatMsg("user", q)
        asking = true
        scope.launch {
            try {
                val modelId = models.getOrNull(modelIdx)?.optLong("id", 0)?.takeIf { it > 0 }
                val r = KbApi.aiAsk(q, conversationId, modelId, mode)
                conversationId = r.optLong("conversationId", 0).takeIf { it > 0 }
                val srcs = r.optJSONArray("sources")?.objects() ?: emptyList()
                val meta = listOfNotNull(
                    r.optString("modelName").takeIf { it.isNotEmpty() },
                    r.optLong("latencyMs", -1).takeIf { it >= 0 }?.let { "${it}ms" }
                ).joinToString(" · ")
                messages = messages + ChatMsg("assistant", r.optString("answer").ifBlank { "（空回答）" }, srcs, meta)
                runCatching { conversations = KbApi.aiConversations().objects() }
            } catch (e: Exception) {
                messages = messages + ChatMsg("assistant", "⚠️ ${e.message ?: "请求失败"}")
            } finally {
                asking = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // —— 顶栏（对标 .m-header.solid）——
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text("AI 问答", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    convTitle.ifBlank { "基于知识库检索作答" },
                    fontSize = 11.sp, color = KbTextDim, maxLines = 1
                )
            }
            IconButton(onClick = { showHistory = true }) {
                Icon(Icons.Filled.HistoryIcon, contentDescription = "历史会话",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { newConversation() }) {
                Icon(Icons.Filled.Add, contentDescription = "新会话")
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.6.dp)
                .background(MaterialTheme.colorScheme.outline)
        )

        // —— 模式 + 模型选择行 ——
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("kb", "general").forEach { m ->
                val selected = mode == m
                Text(
                    MODE_CN[m] ?: m,
                    fontSize = 12.sp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .background(
                            if (selected) KbBlueSoft else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(14.dp)
                        )
                        .clickable { mode = m }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            if (models.isNotEmpty()) {
                Text(
                    models.getOrNull(modelIdx)?.optString("name").orEmpty().ifBlank { "选择模型" },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(KbBlueSoft, RoundedCornerShape(14.dp))
                        .clickable { showModels = true }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }

        // —— 消息流 ——
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty() && !asking) {
                item {
                    Column(Modifier.padding(top = 48.dp, start = 8.dp)) {
                        Text("向知识库提问", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "· 新人入职流程是什么？\n· 报销制度要点\n· 帮我总结最近的培训内容",
                            fontSize = 13.sp, color = KbTextDim, lineHeight = 22.sp
                        )
                    }
                }
            }
            items(messages.size) { i ->
                MessageBubble(messages[i])
            }
            if (asking) {
                item {
                    Text("思考中…", fontSize = 13.sp, color = KbTextDim,
                        modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        LaunchedEffect(messages.size, asking) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem((messages.size - 1 + if (asking) 1 else 0).coerceAtLeast(0))
            }
        }

        // —— 输入条 ——
        Row(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("输入问题…", fontSize = 14.sp) },
                maxLines = 4,
                enabled = !asking,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.size(8.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .background(
                        if (input.isNotBlank() && !asking) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(50)
                    )
                    .clickable(enabled = input.isNotBlank() && !asking) { ask() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = if (input.isNotBlank() && !asking) androidx.compose.ui.graphics.Color.White
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // —— 历史会话抽屉 ——
    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    "历史会话",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                if (conversations.isEmpty()) {
                    Text("还没有会话记录", fontSize = 13.sp, color = KbTextDim,
                        modifier = Modifier.padding(16.dp))
                }
                conversations.forEach { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { loadConversation(c) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.optString("title").ifBlank { "未命名会话" },
                                fontSize = 14.sp,
                                maxLines = 1,
                                fontWeight = if (c.optLong("id", 0) == conversationId) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                formatDateTime(c.optString("updatedAt")),
                                fontSize = 11.sp, color = KbTextDim
                            )
                        }
                        TextButton(onClick = { deleteTarget = c }) { Text("删除", fontSize = 12.sp) }
                    }
                }
            }
        }
    }

    // —— 模型选择抽屉 ——
    if (showModels) {
        ModalBottomSheet(onDismissRequest = { showModels = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text("选择模型", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                models.forEachIndexed { i, m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { modelIdx = i; showModels = false }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.optString("name"), fontSize = 14.sp,
                                fontWeight = if (i == modelIdx) FontWeight.SemiBold else FontWeight.Normal)
                            Text(m.optString("provider"), fontSize = 11.sp, color = KbTextDim)
                        }
                        if (m.optBoolean("isDefault", false)) {
                            Text("默认", fontSize = 11.sp, color = KbBlue)
                        }
                    }
                }
            }
        }
    }

    // —— 删除会话确认 ——
    deleteTarget?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话") },
            text = { Text("确定删除「${c.optString("title").ifBlank { "未命名" }}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { KbApi.aiDeleteConversation(c.optLong("id", 0)) }
                        conversations = conversations.filter { it.optLong("id", 0) != c.optLong("id", 0) }
                        if (c.optLong("id", 0) == conversationId) newConversation()
                    }
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun MessageBubble(m: ChatMsg) {
    val isUser = m.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(
                        topStart = if (isUser) 14.dp else 4.dp,
                        topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = if (isUser) 4.dp else 14.dp
                    )
                )
                .padding(12.dp)
        ) {
            if (isUser) {
                Text(m.text, fontSize = 14.sp, color = androidx.compose.ui.graphics.Color.White)
            } else {
                MarkdownText(m.text)
            }
            if (m.sources.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("引用来源", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
                m.sources.take(5).forEach { s ->
                    Text(
                        "· ${s.optString("title").ifBlank { "文档" }}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (m.meta.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(m.meta, fontSize = 10.sp, color = KbTextDim)
            }
        }
    }
}

/**
 * 轻量 Markdown → 可读文本渲染（Html.fromHtml）。
 * 支持标题/粗体/斜体/行内代码/代码块/列表/链接色；覆盖 AI 回答的绝大多数形态，
 * 不引入 WebView（每气泡一个 WebView 太重）。
 */
@Composable
fun MarkdownText(src: String) {
    var s = src.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = Regex("```[a-zA-Z0-9_-]*\\n?([\\s\\S]*?)```").replace(s) { m ->
        "<pre><font face=\"monospace\">" + m.groupValues[1].trim().replace("\n", "<br>") + "</font></pre>"
    }
    s = Regex("`([^`\\n]+)`").replace(s) { "<font face=\"monospace\">${it.groupValues[1]}</font>" }
    s = Regex("\\*\\*([^*\\n]+)\\*\\*").replace(s) { "<b>${it.groupValues[1]}</b>" }
    s = Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)").replace(s) { "<i>${it.groupValues[1]}</i>" }
    s = Regex("^#{1,4}\\s+(.*)$", RegexOption.MULTILINE).replace(s) { "<b>${it.groupValues[1]}</b><br>" }
    s = Regex("^\\s*[-*]\\s+(.*)$", RegexOption.MULTILINE).replace(s) { "· ${it.groupValues[1]}<br>" }
    s = Regex("\\[([^\\]]+)]\\(([^)]+)\\)").replace(s) {
        "<font color=\"#1677FF\">${it.groupValues[1]}</font>"
    }
    s = s.replace("\n", "<br>")
    val spanned = androidx.core.text.HtmlCompat.fromHtml(
        s, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
    )
    Text(
        androidx.compose.ui.text.buildAnnotatedString { append(spanned) },
        fontSize = 14.sp, lineHeight = 22.sp
    )
}
