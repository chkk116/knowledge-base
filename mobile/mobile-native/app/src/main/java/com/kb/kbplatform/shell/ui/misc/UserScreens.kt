/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.misc

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.KbButton
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.KbEmpty
import com.kb.kbplatform.shell.ui.common.KbLoading
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.formatDateTime
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 用户主页（对标 MobileUserProfile）：名片 + 关注 + 用户文章 */
@Composable
fun UserProfileScreen(userId: Long, onBack: () -> Unit, openPost: (Long, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var card by remember { mutableStateOf<JSONObject?>(null) }
    var articles by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var following by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(L2.Loading) }

    LaunchedEffect(userId) {
        try {
            card = KbApi.userCard(userId)
            following = card?.optBoolean("following", false) ?: false
            articles = runCatching { KbApi.userArticles(userId).objects() }.getOrDefault(emptyList())
            state = L2.Ok
        } catch (e: Exception) { state = L2.Empty }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "用户主页", onBack = onBack)
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("👤", "用户不存在")
            L2.Ok -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Color(0xFF3B8AFF), Color(0xFF1677FF))
                            ),
                            RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                        )
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .background(Color.White.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            (card?.optString("displayName") ?: "").ifBlank { "?" }.take(1),
                            color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        card?.optString("displayName").orEmpty().ifBlank { "用户" },
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                    card?.optString("bio")?.takeIf { it.isNotEmpty() }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching { KbApi.followUser(userId, !following) }
                            following = !following
                        }
                    }) {
                        Text(if (following) "取消关注" else "+ 关注", color = Color.White)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("TA 的文章（${articles.size}）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
                if (articles.isEmpty()) {
                    Text("暂无文章", fontSize = 13.sp, color = KbTextDim,
                        modifier = Modifier.padding(horizontal = 16.dp))
                }
                articles.forEach { d ->
                    KbCard(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { openPost(d.optLong("id", 0), d.optString("title")) }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(d.optString("title").ifBlank { "未命名" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(formatDateTime(d.optString("updatedAt")), fontSize = 11.sp, color = KbTextDim)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** 帖子/文章页（对标 MobilePostView）：正文 + 评论区（列表 + 发表） */
@Composable
fun PostViewScreen(postId: Long, titleHint: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var article by remember { mutableStateOf<JSONObject?>(null) }
    var comments by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var posting by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(L2.Loading) }
    var authorId by remember { mutableLongStateOf(0L) }
    var showAuthor by remember { mutableStateOf(false) }

    fun loadComments() {
        scope.launch {
            runCatching { comments = KbApi.articleComments(postId).objects() }
        }
    }

    LaunchedEffect(postId) {
        try {
            article = KbApi.articleDetail(postId)
            authorId = article?.optLong("ownerId", article?.optLong("userId", 0L) ?: 0L) ?: 0L
            loadComments()
            state = L2.Ok
        } catch (e: Exception) { state = L2.Empty }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = titleHint.ifBlank { "文章" }, onBack = onBack)
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("📄", "文章不存在")
            L2.Ok -> Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text(
                        article?.optString("title").orEmpty().ifBlank { "未命名" },
                        fontSize = 20.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "作者：${article?.optString("ownerName").orEmpty().ifBlank { "—"}}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(enabled = authorId > 0) { showAuthor = true }
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            formatDateTime(article?.optString("updatedAt")),
                            fontSize = 11.sp, color = KbTextDim
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        android.text.Html.fromHtml(
                            article?.optString("content").orEmpty(),
                            android.text.Html.FROM_HTML_MODE_LEGACY
                        ).toString(),
                        fontSize = 14.sp,
                        lineHeight = 23.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
                    )
                    Spacer(Modifier.height(24.dp))
                    Text("评论（${comments.size}）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    if (comments.isEmpty()) {
                        Text("还没有评论", fontSize = 12.sp, color = KbTextDim)
                    }
                    comments.forEach { c ->
                        KbCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Row {
                                    Text(
                                        c.optString("username").ifBlank { "匿名" },
                                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(formatDateTime(c.optString("createdAt")), fontSize = 10.sp, color = KbTextDim)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(c.optString("content"), fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("写下你的评论…", fontSize = 14.sp) },
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.size(8.dp))
                    TextButton(
                        onClick = {
                            val t = input.trim()
                            if (t.isEmpty() || posting) return@TextButton
                            posting = true
                            scope.launch {
                                runCatching { KbApi.postComment(postId, t) }
                                input = ""
                                posting = false
                                loadComments()
                            }
                        },
                        enabled = input.isNotBlank() && !posting
                    ) { Text(if (posting) "…" else "发送") }
                }
            }
        }
    }

    if (showAuthor && authorId > 0) {
        UserProfileInline(authorId, onDismiss = { showAuthor = false }, openPost = { id, t ->
            showAuthor = false
        })
    }
}

@Composable
private fun UserProfileInline(userId: Long, onDismiss: () -> Unit, openPost: (Long, String) -> Unit) {
    // 简化：弹层里直接复用用户主页内容（避免嵌套导航），关闭即回帖子
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用户主页") },
        text = {
            Column {
                Text("在完整版中查看该用户的文章与动态。", fontSize = 13.sp, color = KbTextDim)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}
