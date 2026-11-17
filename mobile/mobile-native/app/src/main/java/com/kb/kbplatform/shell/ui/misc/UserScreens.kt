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
import androidx.compose.runtime.mutableIntStateOf
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
import com.kb.kbplatform.shell.TokenStore
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.KbButton
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.KbChip
import com.kb.kbplatform.shell.ui.common.KbReportDialog
import com.kb.kbplatform.shell.ui.common.KbEmpty
import com.kb.kbplatform.shell.ui.common.KbLoading
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.formatDateTime
import com.kb.kbplatform.shell.ui.theme.KbRed
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 用户主页（对标 MobileUserProfile）：名片 + 关注 + 用户文章 */
@Composable
fun UserProfileScreen(
    userId: Long,
    onBack: () -> Unit,
    openPost: (Long, String) -> Unit,
    openChat: (Long, String) -> Unit = { _, _ -> }
) {
    val scope = rememberCoroutineScope()
    var card by remember { mutableStateOf<JSONObject?>(null) }
    var articles by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var following by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(L2.Loading) }
    // 举报用户（targetType=USER，后端按 authorId 处置）
    var reportUser by remember { mutableStateOf(false) }
    var profileMsg by remember { mutableStateOf("") }

    LaunchedEffect(userId) {
        try {
            card = KbApi.userCard(userId)
            following = card?.optBoolean("following", false) ?: false
            articles = runCatching { KbApi.userArticles(userId).objects() }.getOrDefault(emptyList())
            state = L2.Ok
        } catch (e: Exception) { state = L2.Empty }
    }

    if (reportUser) {
        KbReportDialog(
            targetType = "USER",
            targetId = userId,
            preview = card?.optString("displayName").orEmpty()
                .ifBlank { card?.optString("username").orEmpty() },
            onDismiss = { reportUser = false },
            onResult = { profileMsg = it },
        )
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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching { KbApi.followUser(userId, !following) }
                                following = !following
                            }
                        }) {
                            Text(if (following) "取消关注" else "+ 关注", color = Color.White)
                        }
                        // 私信：自己的主页不出这个按钮（后端也拒绝给自己发）
                        val meId = TokenStore.info.value?.optLong("id", 0L) ?: 0L
                        if (userId != meId) {
                            OutlinedButton(onClick = {
                                openChat(userId, card?.optString("displayName").orEmpty()
                                    .ifBlank { card?.optString("username").orEmpty() })
                            }) {
                                Text("私信", color = Color.White)
                            }
                            OutlinedButton(onClick = { reportUser = true }) {
                                Text("举报", color = Color.White)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (profileMsg.isNotEmpty()) {
                    Text(
                        profileMsg,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                }
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

/** 帖子/文章页（对标 MobilePostView）：正文 + 互动（点赞/收藏/订阅）+ 评论区（列表 + 发表 + 评论点赞） */
@Composable
fun PostViewScreen(
    postId: Long,
    titleHint: String,
    onBack: () -> Unit,
    openRoute: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var article by remember { mutableStateOf<JSONObject?>(null) }
    var comments by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var likedCommentIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var input by remember { mutableStateOf("") }
    var posting by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(L2.Loading) }
    var authorId by remember { mutableLongStateOf(0L) }
    // 互动状态：点赞 / 收藏 / 订阅（后端只允许对已发布内容互动，403 文案直接透出）
    var liked by remember { mutableStateOf(false) }
    var likeCount by remember { mutableIntStateOf(0) }
    var faved by remember { mutableStateOf(false) }
    var subscribed by remember { mutableStateOf(false) }
    var subCount by remember { mutableLongStateOf(0L) }
    var msg by remember { mutableStateOf("") }
    var related by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var replyTo by remember { mutableStateOf<JSONObject?>(null) }
    // 评论管理：自己的可删、别人的可举报
    val meId = TokenStore.info.value?.optLong("id", 0L) ?: 0L
    var delComment by remember { mutableStateOf<JSONObject?>(null) }
    var reportTarget by remember { mutableStateOf<JSONObject?>(null) }
    var reportReason by remember { mutableStateOf("垃圾广告") }
    var reportDetail by remember { mutableStateOf("") }
    // 举报整篇文章（顶栏入口）
    var reportPost by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    fun loadComments() {
        scope.launch {
            runCatching { comments = KbApi.articleComments(postId).objects() }
            likedCommentIds = runCatching { KbApi.myCommentLikeIds() }.getOrDefault(emptySet())
        }
    }

    LaunchedEffect(postId) {
        try {
            val a = KbApi.articleDetail(postId)
            article = a
            likeCount = a.optInt("likes")
            authorId = a.optLong("ownerId", a.optLong("userId", 0L))
            loadComments()
            state = L2.Ok
            liked = postId in runCatching { KbApi.myLikeIds() }.getOrDefault(emptySet())
            runCatching { KbApi.docSubscription(postId) }.onSuccess {
                subscribed = it.first; subCount = it.second
            }
            // 相关阅读是可选信息，失败留空不影响正文与评论
            related = runCatching { KbApi.relatedDocs(postId, 5).objects() }.getOrDefault(emptyList())
        } catch (e: Exception) { state = L2.Empty }
    }

    // 删除自己的评论
    delComment?.let { dc ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { delComment = null },
            title = { Text("删除评论") },
            text = { Text("删除后不可恢复，确定删除这条评论吗？") },
            confirmButton = {
                TextButton(onClick = {
                    val id = dc.optLong("id", 0)
                    delComment = null
                    scope.launch {
                        runCatching { KbApi.deleteComment(id) }
                            .onFailure { msg = it.message ?: "删除失败" }
                        loadComments()
                    }
                }) { Text("删除", color = KbRed) }
            },
            dismissButton = { TextButton(onClick = { delComment = null }) { Text("取消") } }
        )
    }

    // 举报他人评论（原因必填；后端 10 分钟内同一对象只收一次）
    reportTarget?.let { rc ->
        val reasons = listOf("垃圾广告", "辱骂攻击", "违法违规", "侵权内容", "虚假信息", "其它")
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { reportTarget = null },
            title = { Text("举报评论") },
            text = {
                Column {
                    Text("「${rc.optString("content").take(24)}」", fontSize = 12.sp, color = KbTextDim, maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    reasons.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { r ->
                                KbChip(r, selected = reportReason == r, onClick = { reportReason = r })
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    OutlinedTextField(
                        value = reportDetail,
                        onValueChange = { reportDetail = it },
                        label = { Text("补充说明（选填）") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = rc.optLong("id", 0)
                    reportTarget = null
                    scope.launch {
                        runCatching { KbApi.reportContent("COMMENT", id, reportReason, reportDetail) }
                            .onSuccess { msg = "举报已提交，管理员会尽快处理" }
                            .onFailure { msg = it.message ?: "举报失败" }
                    }
                }) { Text("提交举报") }
            },
            dismissButton = { TextButton(onClick = { reportTarget = null }) { Text("取消") } }
        )
    }

    // 举报整篇文章（后端按 targetType=POST 处理，处置动作可下架）
    if (reportPost) {
        KbReportDialog(
            targetType = "POST",
            targetId = postId,
            preview = article?.optString("title").orEmpty(),
            onDismiss = { reportPost = false },
            onResult = { msg = it },
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = titleHint.ifBlank { "文章" }, onBack = onBack, actions = {
            TextButton(onClick = {
                val url = com.kb.kbplatform.shell.AppConfig.effectiveBaseUrl.trimEnd('/') + "/m/post/$postId"
                runCatching {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, "${titleHint.ifBlank { "文章" }}\n$url")
                    }
                    ctx.startActivity(android.content.Intent.createChooser(send, "分享文章"))
                }
            }) { Text("分享", fontSize = 13.sp) }
            TextButton(onClick = { reportPost = true }) { Text("举报", fontSize = 13.sp) }
        })
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
                            modifier = Modifier.clickable(enabled = authorId > 0) {
                                openRoute("user/$authorId")
                            }
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            formatDateTime(article?.optString("updatedAt")),
                            fontSize = 11.sp, color = KbTextDim
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // 互动栏：点一下立即变状态，失败回滚并透出后端文案
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KbChip(
                            text = (if (liked) "♥ " else "♡ ") + likeCount,
                            selected = liked,
                            onClick = {
                                scope.launch {
                                    val was = liked
                                    liked = !liked
                                    runCatching { KbApi.toggleLike(postId) }
                                        .onSuccess { (on, n) -> liked = on; likeCount = n }
                                        .onFailure { liked = was; msg = it.message ?: "点赞失败" }
                                }
                            }
                        )
                        KbChip(
                            text = if (faved) "★ 已收藏" else "☆ 收藏",
                            selected = faved,
                            onClick = {
                                scope.launch {
                                    val was = faved
                                    faved = !faved
                                    runCatching { KbApi.toggleFavorite(postId) }
                                        .onSuccess { faved = it }
                                        .onFailure { faved = was; msg = it.message ?: "收藏失败" }
                                }
                            }
                        )
                        KbChip(
                            text = (if (subscribed) "已订阅 " else "订阅 ") + subCount,
                            selected = subscribed,
                            onClick = {
                                scope.launch {
                                    runCatching { KbApi.setDocSubscribed(postId, !subscribed) }
                                        .onSuccess { subscribed = it.first; subCount = it.second }
                                        .onFailure { msg = it.message ?: "订阅失败" }
                                }
                            }
                        )
                    }
                    if (msg.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(msg, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
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
                    Spacer(Modifier.height(20.dp))
                    // 相关阅读：同分类 + 标签重叠的文章（公开接口，失败就整块不出现）
                    if (related.isNotEmpty()) {
                        Text("相关阅读", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        related.forEach { r ->
                            val rid = r.optLong("id", 0)
                            KbCard(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable(enabled = rid > 0) {
                                        openRoute(
                                            "post/$rid?title=" +
                                                android.net.Uri.encode(r.optString("title"))
                                        )
                                    }
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        r.optString("title").ifBlank { "未命名" },
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                    )
                                    val views = r.optInt("views", 0)
                                    Text(
                                        listOfNotNull(
                                            r.optString("category").takeIf { it.isNotBlank() },
                                            r.optString("ownerName").takeIf { it.isNotBlank() },
                                            if (views > 0) "$views 阅" else null,
                                        ).joinToString(" · "),
                                        fontSize = 11.sp, color = KbTextDim,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                    Text("评论（${comments.size}）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    if (comments.isEmpty()) {
                        Text("还没有评论", fontSize = 12.sp, color = KbTextDim)
                    }
                    // 楼中楼：按 parentId 分组，顶层评论下挂一层子回复（后端支持 parentId）
                    val byParent = comments.groupBy { it.optLong("parentId", 0) }
                    byParent[0L].orEmpty().forEach { root ->
                        val rootId = root.optLong("id", 0)
                        CommentCard(
                            c = root,
                            liked = rootId in likedCommentIds,
                            mine = root.optLong("userId", 0) == meId,
                            onLikedChange = { on ->
                                likedCommentIds = if (on) likedCommentIds + rootId else likedCommentIds - rootId
                            },
                            onReply = { replyTo = root },
                            onDelete = { delComment = root },
                            onReport = { reportTarget = root; reportReason = "垃圾广告"; reportDetail = "" },
                            onError = { e -> msg = e }
                        )
                        byParent[rootId].orEmpty().forEach { child ->
                            val cid = child.optLong("id", 0)
                            CommentCard(
                                c = child,
                                liked = cid in likedCommentIds,
                                indent = true,
                                mine = child.optLong("userId", 0) == meId,
                                onLikedChange = { on ->
                                    likedCommentIds = if (on) likedCommentIds + cid else likedCommentIds - cid
                                },
                                onReply = { replyTo = root },
                                onDelete = { delComment = child },
                                onReport = { reportTarget = child; reportReason = "垃圾广告"; reportDetail = "" },
                                onError = { e -> msg = e }
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // 楼中楼：正在回复谁 —— 点「回复」后这里出现可取消的提示条
                    replyTo?.let { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "回复 @${r.optString("username").ifBlank { "匿名" }}：${r.optString("content").take(20)}",
                                fontSize = 11.sp,
                                color = KbTextDim,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { replyTo = null },
                                contentPadding = PaddingValues(horizontal = 6.dp)
                            ) { Text("取消", fontSize = 11.sp, color = KbTextDim) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = {
                                Text(
                                    if (replyTo != null) "回复 @${replyTo?.optString("username").orEmpty()}…"
                                    else "写下你的评论…",
                                    fontSize = 14.sp
                                )
                            },
                            maxLines = 2,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.size(8.dp))
                        TextButton(
                            onClick = {
                                val t = input.trim()
                                if (t.isEmpty() || posting) return@TextButton
                                posting = true
                                val parent = replyTo?.optLong("id")
                                scope.launch {
                                    runCatching { KbApi.postComment(postId, t, parent) }
                                    input = ""
                                    replyTo = null
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
    }
}

/**
 * 单条评论：内容 + 点赞。
 * 独立 composable + `remember(id)`：列表增删时各自的点赞数不会串（避免在 forEach 里直接 remember）。
 */
@Composable
private fun CommentCard(
    c: JSONObject,
    liked: Boolean,
    indent: Boolean = false,
    mine: Boolean = false,
    onLikedChange: (Boolean) -> Unit,
    onReply: () -> Unit = {},
    onDelete: () -> Unit = {},
    onReport: () -> Unit = {},
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val cid = c.optLong("id", 0)
    var likes by remember(cid) { mutableIntStateOf(c.optInt("likes")) }
    KbCard(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = if (indent) 20.dp else 0.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (indent) {
                    Text("↳ ", fontSize = 12.sp, color = KbTextDim)
                }
                Text(
                    c.optString("username").ifBlank { "匿名" },
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(formatDateTime(c.optString("createdAt")), fontSize = 10.sp, color = KbTextDim)
            }
            Spacer(Modifier.height(4.dp))
            Text(c.optString("content"), fontSize = 13.sp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onReply, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("回复", fontSize = 12.sp, color = KbTextDim)
                }
                // 自己的评论给「删除」，别人的给「举报」——后台走不同端点（DELETE vs POST /api/reports）
                if (mine) {
                    TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("删除", fontSize = 12.sp, color = KbRed)
                    }
                } else {
                    TextButton(onClick = onReport, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("举报", fontSize = 12.sp, color = KbTextDim)
                    }
                }
                TextButton(onClick = {
                    scope.launch {
                        runCatching { KbApi.toggleCommentLike(cid) }
                            .onSuccess { (on, n) -> onLikedChange(on); likes = n }
                            .onFailure { onError(it.message ?: "点赞失败") }
                    }
                }) {
                    Text(
                        (if (liked) "♥ " else "♡ ") + likes,
                        fontSize = 12.sp,
                        color = if (liked) MaterialTheme.colorScheme.primary else KbTextDim
                    )
                }
            }
        }
    }
}
