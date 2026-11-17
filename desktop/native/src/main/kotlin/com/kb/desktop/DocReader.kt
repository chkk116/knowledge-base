package com.kb.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Files

// 原生文章阅读页（2026-11-13 取代 WebView 阅读壳）：对齐 Web 端 PostDetail 的三栏布局
//   左：点赞 / 收藏 / 评论 / 返回顶部（悬浮操作栏）
//   中：面包屑 → 标题 → 元信息（分类/标签/作者/时间/浏览）→ 工具条 → 原生渲染正文 → 评论区
//   右：目录（h1~h6 + 编号段落，点击定位）
// 正文渲染见 DocHtml.kt；编辑仍走 Web 端 SPA（能力全，不重写编辑器）。

@Composable
fun ArticleView(
    api: ApiClient,
    token: String,
    server: String,
    username: String,
    doc: DocDetail,
    onOpenDoc: (Long) -> Unit,
    onEdit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val blocks = remember(doc.id, doc.content) { DocHtml.parse(doc.content) }
    val toc = remember(blocks) { DocHtml.tocOf(blocks) }
    var tocOpen by remember(doc.id) { mutableStateOf(true) }

    var crumbs by remember(doc.id) { mutableStateOf(emptyList<CrumbItem>()) }
    var liked by remember(doc.id) { mutableStateOf(false) }
    var favorited by remember(doc.id) { mutableStateOf(false) }
    var likeCount by remember(doc.id) { mutableStateOf(doc.likes) }
    var comments by remember(doc.id) { mutableStateOf(emptyList<CommentItem>()) }
    var commentLikes by remember(doc.id) { mutableStateOf(emptySet<Long>()) }
    var commentText by remember(doc.id) { mutableStateOf("") }
    var posting by remember(doc.id) { mutableStateOf(false) }
    var commentErr by remember(doc.id) { mutableStateOf<String?>(null) }
    // 回复目标：顶层评论 (id, 显示名)。与 Web 端口径一致——回复统一挂到顶层，最多两层
    var replyTo by remember(doc.id) { mutableStateOf<Pair<Long, String>?>(null) }
    val commentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // 编辑权限：管理员或作者本人（/api/auth/me）；拉取失败时保留按钮，由后端 updateOwn 兜底
    var canEdit by remember(doc.id) { mutableStateOf(true) }
    var pendingDel by remember(doc.id) { mutableStateOf<Long?>(null) }
    var toast by remember(doc.id) { mutableStateOf<String?>(null) }
    // 举报评论：只能举报别人的（自己的走删除），原因必填
    var reportTarget by remember(doc.id) { mutableStateOf<CommentItem?>(null) }
    var reportReason by remember(doc.id) { mutableStateOf("垃圾广告") }
    var reportDetail by remember(doc.id) { mutableStateOf("") }
    // 举报整篇文章（左侧操作栏 ⚑）
    var reportDoc by remember(doc.id) { mutableStateOf(false) }
    // 2026-11-16 补齐 Web 端能力：文档订阅 / 相关阅读 / 作者卡片 / Word 导出
    var subscribed by remember(doc.id) { mutableStateOf(false) }
    var subCount by remember(doc.id) { mutableStateOf(0L) }
    var related by remember(doc.id) { mutableStateOf(emptyList<DocSummary>()) }
    var author by remember(doc.id) { mutableStateOf<AuthorCard?>(null) }
    var following by remember(doc.id) { mutableStateOf(false) }
    var exporting by remember(doc.id) { mutableStateOf(false) }

    // 打开文章：面包屑 + 评论 + 我的赞/收藏标记（一次拉齐，失败静默降级为未点赞态）
    LaunchedEffect(doc.id) {
        crumbs = runCatching { api.docBreadcrumb(token, doc.id) }.getOrDefault(emptyList())
        comments = runCatching { api.docComments(token, doc.id) }.getOrDefault(emptyList())
        likeCount = doc.likes
        runCatching { liked = doc.id in api.myLikeIds(token) }
        runCatching { favorited = doc.id in api.myFavoriteIds(token) }
        runCatching { commentLikes = api.myCommentLikes(token) }
        runCatching {
            val (on, n) = api.docSubscription(token, doc.id)
            subscribed = on
            subCount = n
        }
        // 相关阅读与作者卡片都是可选信息，失败就留空，不影响正文/评论
        related = runCatching { api.docRelated(token, doc.id, 4) }.getOrDefault(emptyList())
        doc.ownerId?.let { oid ->
            author = runCatching { api.userCard(token, oid) }.getOrNull()
            following = author?.isFollowing ?: false
        }
        runCatching {
            val (myId, role) = api.me(token)
            canEdit = role.equals("ADMIN", ignoreCase = true) || (doc.ownerId != null && doc.ownerId == myId)
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2600)
            toast = null
        }
    }

    // 顶层评论按时间正序，回复紧跟其父（Web 端 commentTree 同序）
    val commentRows = remember(comments) {
        val tops = comments.filter { it.parentId == null }.sortedBy { it.createdAt }
        val out = ArrayList<CommentItem>(comments.size)
        for (t in tops) {
            out.add(t)
            out.addAll(comments.filter { it.parentId == t.id }.sortedBy { it.createdAt })
        }
        // 脏数据兜底：父评论不在列表里的孤儿回复也带上
        out.addAll(comments.filter { c -> c.parentId != null && comments.none { it.id == c.parentId } })
        out
    }
    val parentNames = remember(comments) {
        comments.associate { c ->
            val name = c.displayName?.takeIf { it.isNotBlank() } ?: c.username
            c.id to name
        }
    }

    fun toggleLike() {
        scope.launch {
            val was = liked
            val wasCount = likeCount
            liked = !was
            likeCount = if (was) (likeCount - 1).coerceAtLeast(0) else likeCount + 1
            try {
                val r = api.toggleLike(token, doc.id)
                liked = r.first
                likeCount = r.second
            } catch (e: Exception) {
                liked = was
                likeCount = wasCount
                toast = e.message ?: "点赞失败"
            }
        }
    }

    fun toggleFav() {
        scope.launch {
            val was = favorited
            favorited = !was
            try {
                favorited = api.toggleFavorite(token, doc.id)
                toast = if (favorited) "已收藏" else "已取消收藏"
            } catch (e: Exception) {
                favorited = was
                toast = e.message ?: "收藏失败"
            }
        }
    }

    fun toggleSubscribe() {
        scope.launch {
            val was = subscribed
            val wasCount = subCount
            subscribed = !was
            subCount = if (was) (subCount - 1).coerceAtLeast(0) else subCount + 1
            try {
                val (on, n) = api.setSubscribed(token, doc.id, !was)
                subscribed = on
                subCount = n
                toast = if (on) "已订阅，文档更新时会通知你" else "已取消订阅"
            } catch (e: Exception) {
                subscribed = was
                subCount = wasCount
                toast = e.message ?: "订阅失败"
            }
        }
    }

    /** Word 导出：后端返回 HTML 封装的 .doc，落盘到临时目录后交给系统默认程序（Word/WPS）打开 */
    fun exportWord() {
        if (exporting) return
        scope.launch {
            exporting = true
            try {
                val html = api.exportWord(token, doc.id)
                val safe = doc.title.ifBlank { "文档" }
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .take(60)
                val file = Files.createTempDirectory("kb-export").resolve("$safe.doc")
                Files.writeString(file, html, Charsets.UTF_8)
                // 打开失败不吞掉结果：至少告诉用户文件落在哪
                val opened = runCatching { java.awt.Desktop.getDesktop().open(file.toFile()) }.isSuccess
                toast = if (opened) "已导出 $safe.doc" else "已导出到 ${file.toAbsolutePath()}"
            } catch (e: Exception) {
                toast = e.message ?: "导出失败"
            } finally {
                exporting = false
            }
        }
    }

    /** 作者卡片上的关注/取关（乐观更新，失败回滚） */
    fun toggleFollow() {
        val a = author ?: return
        scope.launch {
            val was = following
            following = !was
            try {
                following = api.setFollow(token, a.id, !was)
                toast = if (following) "已关注 ${a.displayName}" else "已取消关注"
            } catch (e: Exception) {
                following = was
                toast = e.message ?: "操作失败"
            }
        }
    }

    fun postComment() {
        val text = commentText.trim()
        if (text.isEmpty() || posting) return
        val target = replyTo
        scope.launch {
            posting = true
            commentErr = null
            try {
                // 评论正文与 Web 端一致走 HTML（纯文本包一层 <p>，表情图片由用户输入/后端净化）
                val c = api.addDocComment(token, doc.id, text, target?.first)
                comments = comments + c
                commentText = ""
                replyTo = null
                toast = if (target != null) "回复成功" else "评论成功"
                // 顶层评论滚到末尾；回复滚到父评论附近（含其下所有子回复）
                val idx = if (target == null) {
                    commentHeaderIndex(blocks.size) + 1 + commentRows.size
                } else {
                    val parent = commentRows.indexOfFirst { it.id == target.first }
                    if (parent >= 0) commentHeaderIndex(blocks.size) + 1 + parent
                    else commentHeaderIndex(blocks.size)
                }
                listState.animateScrollToItem(idx)
            } catch (e: Exception) {
                commentErr = e.message ?: "评论失败"
            } finally {
                posting = false
            }
        }
    }

    /** 点「回复」：归一化到顶层评论、滚到输入框并聚焦（Web 端 startReply 同口径） */
    fun startReply(c: CommentItem) {
        val topId = c.parentId ?: c.id
        val name = parentNames[topId] ?: c.username
        replyTo = topId to name
        scope.launch {
            listState.animateScrollToItem(commentHeaderIndex(blocks.size))
            runCatching { commentFocus.requestFocus() }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                // 左：悬浮操作栏（Web 端 CSDN 风）
                ActionRail(
                    liked = liked,
                    likeCount = likeCount,
                    favorited = favorited,
                    commentCount = commentRows.size,
                    subscribed = subscribed,
                    subCount = subCount,
                    exporting = exporting,
                    showTop = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 120,
                    onLike = ::toggleLike,
                    onFav = ::toggleFav,
                    onSubscribe = ::toggleSubscribe,
                    onExport = ::exportWord,
                    onComment = {
                        scope.launch { listState.animateScrollToItem(commentHeaderIndex(blocks.size)) }
                    },
                    onReport = { reportDoc = true },
                    onTop = { scope.launch { listState.animateScrollToItem(0) } },
                )
                Spacer(Modifier.width(4.dp))

                // 中：正文滚动列
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 24.dp, top = 14.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // 头部（面包屑/标题/元信息/工具条）收进单个 item：块索引与 LazyColumn 索引保持 +1 恒定
                    item {
                        ArticleHeader(
                            doc = doc,
                            crumbs = crumbs,
                            canEdit = canEdit,
                            author = author,
                            following = following,
                            onFollow = ::toggleFollow,
                            onOpenDoc = onOpenDoc,
                            onEdit = onEdit,
                        )
                    }
                    itemsIndexed(blocks) { _, b ->
                        DocBlockView(b, server, Modifier.fillMaxWidth())
                    }
                    if (blocks.isEmpty()) {
                        item { Text("正文为空", fontSize = 13.sp, color = DocSub, modifier = Modifier.padding(top = 20.dp)) }
                    }

                    // ---- 评论区 ----
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 26.dp)) {
                            // 相关阅读：放在评论头上方，保持「头部 item + 正文块 + 评论」的固定索引不变
                            if (related.isNotEmpty()) {
                                RelatedDocs(related, onOpenDoc)
                                Spacer(Modifier.height(20.dp))
                            }
                            androidx.compose.material3.HorizontalDivider(color = KbBorder)
                            Spacer(Modifier.height(14.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "评论（${commentRows.size}）",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = KbTextMain,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            // 回复提示条：显示目标并支持取消（Web 端 reply-hint 同口径）
                            val rt = replyTo
                            if (rt != null) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFF1F5FF))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("回复 @${rt.second}", fontSize = 11.5.sp, color = KbPrimary)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "× 取消",
                                        fontSize = 11.5.sp,
                                        color = KbTextSub,
                                        modifier = Modifier.clickable { replyTo = null }.padding(horizontal = 4.dp),
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                            OutlinedTextField(
                                value = commentText,
                                onValueChange = { commentText = it },
                                modifier = Modifier.fillMaxWidth().focusRequester(commentFocus),
                                placeholder = {
                                    Text(
                                        if (rt != null) "回复 @${rt.second}…" else "写下你的评论…",
                                        fontSize = 12.5.sp,
                                        color = KbTextSub,
                                    )
                                },
                                minLines = 2,
                                maxLines = 5,
                                shape = RoundedCornerShape(10.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { postComment() }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = KbPrimary,
                                    unfocusedBorderColor = KbBorder,
                                ),
                            )
                            if (commentErr != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(commentErr ?: "", fontSize = 11.sp, color = ReaderErrRed)
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Button(
                                    onClick = { postComment() },
                                    enabled = commentText.isNotBlank() && !posting,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = KbPrimary,
                                        disabledContainerColor = Color(0xFFC7CBDA),
                                    ),
                                ) { Text(if (posting) "发送中…" else "发表评论", fontSize = 12.sp) }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    if (commentRows.isEmpty()) {
                        item {
                            Text(
                                "还没有评论，快来抢沙发",
                                fontSize = 12.5.sp,
                                color = KbTextSub,
                                modifier = Modifier.padding(vertical = 14.dp),
                            )
                        }
                    }
                    itemsIndexed(commentRows, key = { _, c -> c.id }) { _, c ->
                        CommentRow(
                            c = c,
                            server = server,
                            isReply = c.parentId != null,
                            parentName = c.parentId?.let { parentNames[it] },
                            liked = c.id in commentLikes,
                            mine = c.username.isNotBlank() && c.username == username,
                            confirming = pendingDel == c.id,
                            onReply = { startReply(c) },
                            onReport = {
                                reportTarget = c
                                reportReason = "垃圾广告"
                                reportDetail = ""
                            },
                            onLike = {
                                scope.launch {
                                    try {
                                        val r = api.toggleCommentLike(token, c.id)
                                        commentLikes = if (r.first) commentLikes + c.id else commentLikes - c.id
                                        comments = comments.map { x -> if (x.id == c.id) x.copy(likes = r.second) else x }
                                    } catch (e: Exception) {
                                        toast = e.message ?: "操作失败"
                                    }
                                }
                            },
                            onDelete = {
                                if (pendingDel == c.id) {
                                    pendingDel = null
                                    scope.launch {
                                        try {
                                            api.deleteComment(token, c.id)
                                            comments = comments.filterNot { it.id == c.id || it.parentId == c.id }
                                        } catch (e: Exception) {
                                            toast = e.message ?: "删除失败"
                                        }
                                    }
                                } else {
                                    pendingDel = c.id
                                }
                            },
                        )
                    }
                    item { Spacer(Modifier.height(30.dp)) }
                }

                // 右：目录（窄窗口收起，给正文让位）
                if (wide >= 1080.dp && toc.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    TocPane(
                        toc = toc,
                        open = tocOpen,
                        onToggle = { tocOpen = !tocOpen },
                        onPick = { idx ->
                            scope.launch { listState.animateScrollToItem(1 + idx) }
                        },
                        modifier = Modifier.width(216.dp).padding(top = 14.dp),
                    )
                }
            }

            // 轻提示（评论成功 / 操作失败）：顶部悬浮条
            val t = toast
            if (t != null) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 18.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF323232))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(t, fontSize = 12.sp, color = Color.White, maxLines = 2)
                }
            }
        }
    }

    // 举报他人评论（原因必填；后端 10 分钟内同一对象只收录一次）
    reportTarget?.let { rc ->
        val reasons = listOf("垃圾广告", "辱骂攻击", "违法违规", "侵权内容", "虚假信息", "其它")
        AlertDialog(
            onDismissRequest = { reportTarget = null },
            title = { Text("举报评论", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "「${rc.content.take(24)}」",
                        fontSize = 12.sp, color = KbTextSub, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (r in reasons) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { reportReason = r }
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (reportReason == r) "● " else "○ ",
                                fontSize = 12.sp,
                                color = if (reportReason == r) KbPrimary else KbTextSub,
                            )
                            Text(r, fontSize = 12.5.sp, color = KbTextMain)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = reportDetail,
                        onValueChange = { reportDetail = it },
                        label = { Text("补充说明（选填）", fontSize = 11.5.sp) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = rc.id
                    reportTarget = null
                    scope.launch {
                        runCatching { api.reportContent(token, "COMMENT", id, reportReason, reportDetail) }
                            .onSuccess { toast = "举报已提交，管理员会尽快处理" }
                            .onFailure { toast = it.message ?: "举报失败" }
                    }
                }) { Text("提交举报", fontSize = 12.5.sp) }
            },
            dismissButton = { TextButton(onClick = { reportTarget = null }) { Text("取消", fontSize = 12.5.sp) } },
        )
    }

    // 举报整篇文章（targetType=POST）
    if (reportDoc) {
        val reasons = listOf("垃圾广告", "辱骂攻击", "违法违规", "侵权内容", "虚假信息", "其它")
        AlertDialog(
            onDismissRequest = { reportDoc = false },
            title = { Text("举报文章", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "「${doc.title.take(30)}」",
                        fontSize = 12.sp, color = KbTextSub, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (r in reasons) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { reportReason = r }
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (reportReason == r) "● " else "○ ",
                                fontSize = 12.sp,
                                color = if (reportReason == r) KbPrimary else KbTextSub,
                            )
                            Text(r, fontSize = 12.5.sp, color = KbTextMain)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = reportDetail,
                        onValueChange = { reportDetail = it },
                        label = { Text("补充说明（选填）", fontSize = 11.5.sp) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    reportDoc = false
                    scope.launch {
                        runCatching { api.reportContent(token, "POST", doc.id, reportReason, reportDetail) }
                            .onSuccess { toast = "举报已提交，管理员会尽快处理" }
                            .onFailure { toast = it.message ?: "举报失败" }
                    }
                }) { Text("提交举报", fontSize = 12.5.sp) }
            },
            dismissButton = { TextButton(onClick = { reportDoc = false }) { Text("取消", fontSize = 12.5.sp) } },
        )
    }
}

private val ReaderErrRed = Color(0xFFDC2626)

/** 评论区头部在 LazyColumn 里的稳定索引：0=文章头部 item，之后是 blocks（或空正文占位） */
private fun commentHeaderIndex(blockCount: Int): Int = 1 + maxOf(blockCount, 1)

// ---------- 左侧操作栏 ----------

@Composable
private fun ActionRail(
    liked: Boolean,
    likeCount: Int,
    favorited: Boolean,
    commentCount: Int,
    subscribed: Boolean,
    subCount: Long,
    exporting: Boolean,
    showTop: Boolean,
    onLike: () -> Unit,
    onFav: () -> Unit,
    onSubscribe: () -> Unit,
    onExport: () -> Unit,
    onComment: () -> Unit,
    onReport: () -> Unit,
    onTop: () -> Unit,
) {
    Column(
        Modifier
            .width(66.dp)
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RailBtn(
            glyph = "👍",
            label = if (liked) "已赞" else "点赞",
            count = likeCount,
            active = liked,
            activeColor = Color(0xFFEF4444),
            onClick = onLike,
        )
        RailBtn(
            glyph = "★",
            label = if (favorited) "已藏" else "收藏",
            count = null,
            active = favorited,
            activeColor = Color(0xFFF59E0B),
            onClick = onFav,
        )
        RailBtn(glyph = "💬", label = "评论", count = commentCount, active = false, activeColor = KbPrimary, onClick = onComment)
        RailBtn(
            glyph = if (subscribed) "🔔" else "🔕",
            label = if (subscribed) "已订阅" else "订阅",
            count = subCount.toInt(),
            active = subscribed,
            activeColor = KbPrimary,
            onClick = onSubscribe,
        )
        RailBtn(
            glyph = "⇩",
            label = if (exporting) "导出中" else "导出",
            count = null,
            active = false,
            activeColor = Color(0xFF0EA5E9),
            onClick = onExport,
        )
        // 举报这篇文章（POST /api/reports，targetType=POST；管理员可在举报处理里下架）
        RailBtn(glyph = "⚑", label = "举报", count = null, active = false, activeColor = KbTextSub, onClick = onReport)
        if (showTop) {
            RailBtn(glyph = "↑", label = "顶部", count = null, active = false, activeColor = KbTextSub, onClick = onTop)
        }
    }
}

@Composable
private fun RailBtn(
    glyph: String,
    label: String,
    count: Int?,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(58.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) activeColor.copy(alpha = 0.07f) else Color.White)
            .border(
                1.dp,
                if (active) activeColor.copy(alpha = 0.45f) else KbBorder,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, fontSize = 17.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = if (active) activeColor else Color(0xFF475569),
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (count != null) {
            Text(
                count.toString(),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) activeColor else KbTextMain,
            )
        }
    }
}

// ---------- 文章头部 ----------

@Composable
private fun ArticleHeader(
    doc: DocDetail,
    crumbs: List<CrumbItem>,
    canEdit: Boolean,
    author: AuthorCard?,
    following: Boolean,
    onFollow: () -> Unit,
    onOpenDoc: (Long) -> Unit,
    onEdit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (crumbs.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                crumbs.forEachIndexed { i, c ->
                    val isCur = c.id == doc.id
                    Text(
                        c.title.ifBlank { "未命名" } + if (i < crumbs.lastIndex) "  /" else "",
                        fontSize = 12.5.sp,
                        color = if (isCur) KbTextMain else DocLink,
                        fontWeight = if (isCur) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .clickable(enabled = !isCur) { onOpenDoc(c.id) }
                            .padding(horizontal = 3.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Text(
            doc.title.ifBlank { "（无标题）" },
            fontSize = 24.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            color = DocHeadInk,
        )
        Spacer(Modifier.height(10.dp))

        // 元信息分两行：分类/标签数量不定，单独成行，避免把作者/时间/浏览挤到窗口外
        Column(Modifier.fillMaxWidth()) {
            val cat = doc.category
            val tags = splitTags(doc.tags)
            if (!cat.isNullOrBlank() || tags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!cat.isNullOrBlank()) TagChip(cat, Color(0xFFEEF2FF), KbPrimary)
                    for (t in tags) TagChip(t, Color(0xFFF3F4F6), Color(0xFF4B5563))
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val owner = doc.ownerName
                if (!owner.isNullOrBlank()) {
                    Text(owner, fontSize = 12.sp, color = DocSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "更新于 ${fmtDocTime(doc.updatedAt)}",
                    fontSize = 12.sp,
                    color = DocSub,
                    maxLines = 1,
                )
                Text("👁 ${doc.views}", fontSize = 12.sp, color = Color(0xFF94A3B8))
                Spacer(Modifier.weight(1f))
                val st = doc.status
                if (st != null && st != "PUBLISHED") {
                    val (label, c) = when (st) {
                        "PENDING_REVIEW" -> "待审核" to Color(0xFFF59E0B)
                        "DRAFT" -> "草稿" to Color(0xFF64748B)
                        else -> "已下架" to Color(0xFFEF4444)
                    }
                    TagChip(label, c.copy(alpha = 0.12f), c)
                }
            }
        }

        // 作者卡片（可选：/api/users/{id}/card 取不到就不显示）：头像首字 + 昵称/徽章 + 数据 + 关注
        val a = author
        if (a != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF8FAFC))
                    .border(1.dp, KbBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(KbIconGradient(KbTileBlue.first, KbTileBlue.second)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        a.displayName.ifBlank { "?" }.take(1),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            a.displayName.ifBlank { "匿名作者" },
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = KbTextMain,
                        )
                        val badge = a.displayBadge
                        if (!badge.isNullOrBlank()) {
                            Spacer(Modifier.width(6.dp))
                            TagChip(badge, Color(0xFFFEF3C7), Color(0xFFB45309))
                        }
                        if (a.codingAge > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text("${a.codingAge} 年", fontSize = 10.5.sp, color = KbTextSub)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOf(
                            "原创 ${a.original}",
                            "获赞 ${a.likes}",
                            "粉丝 ${a.fans}",
                            "积分 ${a.points}",
                        ).joinToString(" · "),
                        fontSize = 10.5.sp,
                        color = KbTextSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (following) "已关注" else "+ 关注",
                    fontSize = 11.5.sp,
                    color = if (following) KbTextSub else KbPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, if (following) KbBorder else KbPrimary.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .clickable(onClick = onFollow)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        // 工具条：编辑（仅管理员/作者，权限来自 /api/auth/me；Web 端 PostDetail 同口径）
        if (canEdit) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniAction("✎ 编辑", onEdit)
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        androidx.compose.material3.HorizontalDivider(color = KbBorder)
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun TagChip(text: String, bg: Color, fg: Color) {
    Text(
        text,
        fontSize = 11.sp,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 140.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun MiniAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = KbTextMain,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF5F6F8))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// ---------- 相关阅读 ----------

@Composable
private fun RelatedDocs(items: List<DocSummary>, onOpenDoc: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("相关阅读", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
        Spacer(Modifier.height(4.dp))
        items.forEach { d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenDoc(d.id) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("·", fontSize = 12.sp, color = KbPrimary)
                Spacer(Modifier.width(6.dp))
                Text(
                    d.title.ifBlank { "（无标题）" },
                    fontSize = 12.sp,
                    color = DocLink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (d.views > 0) {
                    Text("${d.views} 浏览", fontSize = 10.5.sp, color = KbTextSub)
                }
            }
        }
    }
}

// ---------- 目录 ----------

@Composable
private fun TocPane(
    toc: List<TocEntry>,
    open: Boolean,
    onToggle: () -> Unit,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("目录", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
            Spacer(Modifier.weight(1f))
            Text(if (open) "▾" else "▸", fontSize = 11.sp, color = KbTextSub)
        }
        androidx.compose.material3.HorizontalDivider(color = KbBorder)
        if (open) {
            LazyColumn(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                itemsIndexed(toc, key = { i, e -> "$i-${e.text}" }) { _, e ->
                    Text(
                        e.text,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = KbTextSub,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp * (e.level - 1), top = 5.dp, bottom = 5.dp, end = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onPick(e.blockIndex) }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }

}

// ---------- 评论行 ----------

@Composable
private fun CommentRow(
    c: CommentItem,
    server: String,
    isReply: Boolean,
    parentName: String?,
    liked: Boolean,
    mine: Boolean,
    confirming: Boolean,
    onReply: () -> Unit,
    onLike: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (isReply) 34.dp else 0.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 头像：昵称首字（Web 端有真头像，桌面端取首字足够辨识）
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(if (isReply) Color(0xFFE5E7EB) else KbSidebarSelected),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (c.displayName?.ifBlank { null } ?: c.username).take(1).ifBlank { "?" },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isReply) KbTextSub else KbPrimary,
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    c.displayName?.takeIf { it.isNotBlank() } ?: c.username.ifBlank { "匿名" },
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = KbTextMain,
                )
                if (isReply && !parentName.isNullOrBlank()) {
                    Text("回复 @$parentName", fontSize = 11.sp, color = DocLink)
                }
                Text(fmtDocTime(c.createdAt), fontSize = 11.sp, color = KbTextSub)
                Spacer(Modifier.weight(1f))
                // 评论正文是净化过的 HTML（可能带表情图片/粗体/链接），复用正文渲染器
                // 操作：回复 / 赞 / 删除（自己的）
                Text(
                    "回复",
                    fontSize = 11.5.sp,
                    color = KbTextSub,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onReply).padding(horizontal = 5.dp, vertical = 2.dp),
                )
                Text(
                    (if (liked) "❤ " else "🤍 ") + c.likes,
                    fontSize = 11.5.sp,
                    color = if (liked) Color(0xFFEF4444) else KbTextSub,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onLike).padding(horizontal = 5.dp, vertical = 2.dp),
                )
                if (mine) {
                    Text(
                        if (confirming) "确认删除?" else "删除",
                        fontSize = 11.5.sp,
                        color = if (confirming) ReaderErrRed else KbTextSub,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onDelete).padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                } else {
                    // 别人的评论给「举报」（POST /api/reports targetType=COMMENT）
                    Text(
                        "举报",
                        fontSize = 11.5.sp,
                        color = KbTextSub,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onReport).padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            val parts = remember(c.content) { DocHtml.parse(c.content) }
            if (parts.isEmpty()) {
                Text(c.content, fontSize = 12.5.sp, lineHeight = 19.sp, color = Color(0xFF374151))
            } else {
                Column {
                    for (b in parts) DocBlockView(b, server = server, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

// ---------- 小工具 ----------

/** 标签拆分：后端 tags 字段为逗号/空格分隔的自由文本 */
private fun splitTags(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(Regex("[,，、;；\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.take(6)
}

/** "2026-11-13T12:34:56" → "2026-11-13 12:34"（缺省显示 —） */
private fun fmtDocTime(raw: String?): String {
    if (raw.isNullOrBlank()) return "—"
    return raw.replace('T', ' ').take(16)
}
