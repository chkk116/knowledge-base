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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.Bookmarks
import com.kb.kbplatform.shell.data.HistoryStore
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.LocalOcr
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.CenterHint
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.KbEmpty
import com.kb.kbplatform.shell.ui.common.KbLoading
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.formatDateTime
import com.kb.kbplatform.shell.ui.theme.KbBlue
import com.kb.kbplatform.shell.ui.theme.KbGreen
import com.kb.kbplatform.shell.ui.theme.KbRed
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 批量列表页集合（Wave A，逐一对标对应 MobileXxx.vue 的数据与操作）：
 * 待整理箱 / 本地收藏 / 我的考试 / 积分明细 / 排行榜 / 文档订阅 / 回收站 / 我的摘录 / 浏览历史。
 */

private enum class Load { Loading, Ok, Empty, Error }

// ============ 待整理箱（对标 MobileInbox）============

@Composable
fun InboxScreen(onBack: () -> Unit, openWeb: (String, String) -> Unit, openRoute: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(Load.Loading) }
    var archiveTarget by remember { mutableStateOf<JSONObject?>(null) }
    var spaces by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var msg by remember { mutableStateOf("") }

    fun load() {
        state = Load.Loading
        scope.launch {
            try {
                items = KbApi.inboxList().objects()
                state = if (items.isEmpty()) Load.Empty else Load.Ok
            } catch (e: Exception) {
                state = Load.Error
                msg = e.message ?: "加载失败"
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "待整理箱", onBack = onBack)
        if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = KbRed, modifier = Modifier.padding(horizontal = 16.dp))
        when (state) {
            Load.Loading -> KbLoading()
            Load.Empty -> KbEmpty("📥", "暂无待整理\n拍照/速记/分享的内容会先收进这里")
            Load.Error -> com.kb.kbplatform.shell.ui.common.KbError("待整理箱加载失败：$msg", onRetry = { load() })
            Load.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.optLong("id", it.hashCode().toLong()) }) { d ->
                    val id = if (d.has("docId")) d.optLong("docId") else d.optLong("id", 0)
                    val shareId = d.optLong("shareId", 0)
                    KbCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(d.optString("title").ifBlank { "未命名" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                formatDateTime(d.optString("createdAt")),
                                fontSize = 11.sp, color = KbTextDim
                            )
                            Row(Modifier.padding(top = 8.dp)) {
                                TextButton(onClick = {
                                    scope.launch {
                                        // 空间列表取不到就别弹归档框（否则确认时会用空列表下标，提示成功但什么都没发生）
                                        val loaded = runCatching { KbApi.myShares().objects() }.getOrNull()
                                        if (loaded.isNullOrEmpty()) {
                                            msg = "没有可归档的空间"
                                        } else {
                                            spaces = loaded
                                            archiveTarget = d
                                        }
                                    }
                                }) { Text("归档到空间", fontSize = 12.sp) }
                                TextButton(onClick = {
                                    scope.launch {
                                        if (runCatching { KbApi.deleteShareDoc(shareId, id) }.isSuccess) {
                                            items = items.filter { (if (it.has("docId")) it.optLong("docId") else it.optLong("id", 0)) != id }
                                        } else {
                                            msg = "删除失败，请重试"
                                        }
                                    }
                                }) { Text("删除", fontSize = 12.sp, color = KbRed) }
                                TextButton(onClick = { openRoute("reader/$shareId/$id?title=" + android.net.Uri.encode(d.optString("title"))) }) {
                                    Text("查看", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    archiveTarget?.let { d ->
        val id = if (d.has("docId")) d.optLong("docId") else d.optLong("id", 0)
        var target by remember { mutableIntStateOf(0) }
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            title = { Text("归档到空间") },
            text = {
                Column {
                    spaces.forEachIndexed { i, s ->
                        Text(
                            s.optString("name").ifBlank { "未命名空间" },
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { target = i }
                                .padding(vertical = 10.dp),
                            color = if (target == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val space = spaces.getOrNull(target)
                        if (space == null) {
                            msg = "归档失败：空间列表已失效，请重试"
                            archiveTarget = null
                            return@launch
                        }
                        val ok = runCatching { KbApi.inboxArchive(id, space.optLong("id", 0), d.optString("title")) }.isSuccess
                        archiveTarget = null
                        if (ok) { msg = "已归档"; load() } else { msg = "归档失败，请重试" }
                    }
                }) { Text("归档") }
            },
            dismissButton = { TextButton(onClick = { archiveTarget = null }) { Text("取消") } }
        )
    }
}

// ============ 本地收藏（对标 MobileFavorites：本地书签）============

@Composable
fun FavoritesScreen(onBack: () -> Unit, openWeb: (String, String) -> Unit, openRoute: (String) -> Unit = {}) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // 两类收藏语义不同，分区展示：云端文章收藏（跨设备同步）/ 本机文档书签（离线快捷）
    var bookmarks by remember { mutableStateOf(Bookmarks.list(ctx)) }
    var articles by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        bookmarks = Bookmarks.list(ctx)
        articles = runCatching { KbApi.myFavoriteDocs().objects() }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "我的收藏", onBack = onBack)
        if (bookmarks.isEmpty() && articles.isEmpty()) {
            KbEmpty("⭐", "还没有收藏\n在文章页点 ☆ 收藏，或在文档阅读页点 ☆")
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (articles.isNotEmpty()) {
                    item {
                        Text("文章收藏（云端 · 多端同步）", fontSize = 12.sp, color = KbTextDim)
                    }
                    itemsIndexed(articles, key = { i, it -> "doc:" + it.optLong("id", -1L) + ":" + i }) { i, d ->
                        val title = d.optString("title").ifBlank { "未命名" }
                        KbCard(Modifier.fillMaxWidth().clickable {
                            openRoute("post/${d.optLong("id", 0)}?title=" + android.net.Uri.encode(title))
                        }) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("📄", fontSize = 16.sp)
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(title, fontSize = 14.sp)
                                    val t = formatDateTime(d.optString("updatedAt"))
                                    if (t.isNotBlank()) Text("更新于 $t", fontSize = 11.sp, color = KbTextDim)
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        // 失败就不刷新，避免列表与服务端不一致；成功才重新拉取
                                        if (runCatching { KbApi.toggleFavorite(d.optLong("id", 0)) }.isSuccess) refresh++
                                        refresh++
                                    }
                                }) { Text("取消收藏", fontSize = 12.sp, color = KbRed) }
                            }
                        }
                    }
                }
                if (bookmarks.isNotEmpty()) {
                    item {
                        Text("文档书签（本机）", fontSize = 12.sp, color = KbTextDim)
                    }
                    items(bookmarks, key = { "bm:${it.shareId}:${it.docId}" }) { b ->
                        KbCard(Modifier.fillMaxWidth().clickable {
                            openRoute("reader/${b.shareId}/${b.docId}?title=" + android.net.Uri.encode(b.title))
                        }) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("⭐", fontSize = 16.sp)
                                Spacer(Modifier.size(10.dp))
                                Text(b.title.ifBlank { "未命名文档" }, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    Bookmarks.toggle(ctx, b.shareId, b.docId, b.title)
                                    refresh++
                                }) { Text("取消收藏", fontSize = 12.sp, color = KbRed) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============ 我的考试（对标 MobileExamMine）============

@Composable
fun ExamMineScreen(onBack: () -> Unit) {
    var records by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(Load.Loading) }
    var err by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        state = Load.Loading
        try {
            records = KbApi.myExams().objects()
            state = if (records.isEmpty()) Load.Empty else Load.Ok
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
            state = Load.Error
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "我的考试", onBack = onBack)
        when (state) {
            Load.Loading -> KbLoading()
            Load.Empty -> KbEmpty("🏆", "还没有考试记录")
            Load.Error -> com.kb.kbplatform.shell.ui.common.KbError("考试记录加载失败：$err", onRetry = { tick++ })
            Load.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(records, key = { i, it -> it.optLong("id", -1L).let { id -> if (id <= 0) "exam-$i" else id } }) { i, r ->
                    val passed = r.optBoolean("passed", false)
                    KbCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.optString("paperTitle").ifBlank { "考试" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "${r.optInt("questionCount", 0)} 题 · " + formatDateTime(
                                        r.optString("submittedAt").ifBlank { r.optString("startedAt") }
                                    ),
                                    fontSize = 11.sp, color = KbTextDim
                                )
                            }
                            Text(
                                "${r.optInt("score", 0)}分",
                                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                color = if (passed) KbGreen else KbRed
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============ 积分明细（对标 MobilePoints）============

@Composable
fun PointsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var page by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(Load.Loading) }
    var err by remember { mutableStateOf("") }

    fun load(p: Int) {
        state = Load.Loading
        scope.launch {
            try {
                val r = KbApi.pointsLog(p)
                items = r.optJSONArray("content")?.objects() ?: emptyList()
                total = r.optInt("total", items.size)
                page = p
                state = if (items.isEmpty()) Load.Empty else Load.Ok
            } catch (e: Exception) {
                err = e.message ?: "加载失败"
                state = Load.Error
            }
        }
    }
    LaunchedEffect(Unit) { load(0) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "积分明细", onBack = onBack)
        when (state) {
            Load.Loading -> KbLoading()
            Load.Empty -> KbEmpty("📈", "暂无积分记录")
            Load.Error -> com.kb.kbplatform.shell.ui.common.KbError("积分明细加载失败：$err", onRetry = { load(page) })
            Load.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.optLong("id", it.hashCode().toLong()) }) { l ->
                    val pts = l.optInt("delta", l.optInt("points", 0))
                    KbCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.optString("reason").ifBlank { l.optString("type") }, fontSize = 14.sp)
                                Text(formatDateTime(l.optString("createdAt")), fontSize = 11.sp, color = KbTextDim)
                            }
                            Text(
                                (if (pts >= 0) "+" else "") + "$pts",
                                fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                color = if (pts >= 0) KbGreen else KbRed
                            )
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        if (page > 0) TextButton(onClick = { load(page - 1) }) { Text("上一页") }
                        Text("共 $total 条", fontSize = 12.sp, color = KbTextDim, modifier = Modifier.padding(vertical = 12.dp))
                        if ((page + 1) * 20 < total) TextButton(onClick = { load(page + 1) }) { Text("下一页") }
                    }
                }
            }
        }
    }
}

// ============ 排行榜（对标 MobileRanking）============

@Composable
fun RankingScreen(onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var me by remember { mutableStateOf<JSONObject?>(null) }
    var totalPeople by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(Load.Loading) }
    var err by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        state = Load.Loading
        try {
            // 后端返回 {top:[…], total:人数, me:{rank,username,points}}，不是数组
            val r = KbApi.ranking()
            items = r.optJSONArray("top")?.objects() ?: emptyList()
            totalPeople = r.optInt("total", items.size)
            me = r.optJSONObject("me")
            state = if (items.isEmpty()) Load.Empty else Load.Ok
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
            state = Load.Error
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "积分排行榜", onBack = onBack)
        when (state) {
            Load.Loading -> KbLoading()
            Load.Empty -> KbEmpty("🏅", "暂无排行数据")
            Load.Error -> com.kb.kbplatform.shell.ui.common.KbError("排行榜加载失败：$err", onRetry = { tick++ })
            Load.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("共 $totalPeople 人参与", fontSize = 12.sp, color = KbTextDim, modifier = Modifier.weight(1f))
                        me?.let { m ->
                            val rk = m.optInt("rank", 0)
                            Text(
                                if (rk > 0) "我：第 $rk 名 · ${m.optInt("points", 0)} 分" else "我：暂未上榜",
                                fontSize = 12.sp, color = KbBlue
                            )
                        }
                    }
                }
                // itemsIndexed 取代 items.indexOf(r)：后者是 O(n²)，榜单一长就卡
                itemsIndexed(items, key = { i, it -> it.optString("username", "u$i") + "#" + i }) { i, r ->
                    KbCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                listOf("🥇", "🥈", "🥉").getOrNull(i) ?: "${r.optInt("rank", i + 1)}",
                                fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(r.optString("displayName").ifBlank { r.optString("username") }, fontSize = 14.sp)
                                Text(r.optString("username"), fontSize = 11.sp, color = KbTextDim)
                            }
                            Text("${r.optInt("points", 0)} 分", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

// ============ 文档订阅（对标 MobileSubscriptions）============

@Composable
fun SubscriptionsScreen(onBack: () -> Unit, openPost: (Long, String) -> Unit = { _, _ -> }) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(Load.Loading) }
    var err by remember { mutableStateOf("") }

    fun load() {
        state = Load.Loading
        scope.launch {
            try {
                items = KbApi.subscriptions().objects()
                state = if (items.isEmpty()) Load.Empty else Load.Ok
            } catch (e: Exception) {
                err = e.message ?: "加载失败"
                state = Load.Error
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "我的订阅", onBack = onBack)
        when (state) {
            Load.Loading -> KbLoading()
            Load.Empty -> KbEmpty("🔔", "还没有订阅的文档\n订阅后文档更新会通知你")
            Load.Error -> com.kb.kbplatform.shell.ui.common.KbError("订阅列表加载失败：$err", onRetry = { load() })
            Load.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 后端字段：{docId,title,category,deleted,subscribedAt}（没有 id，key 用 docId）
                items(items, key = { it.optLong("docId", it.hashCode().toLong()) }) { s ->
                    val docId = s.optLong("docId", 0)
                    val deleted = s.optBoolean("deleted", false)
                    val title = s.optString("title").ifBlank { "已删除文档" }
                    KbCard(
                        Modifier.fillMaxWidth().clickable(enabled = !deleted) { openPost(docId, title) }
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    title,
                                    fontSize = 14.sp,
                                    color = if (deleted) KbTextDim else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "订阅于 " + formatDateTime(s.optString("subscribedAt")),
                                    fontSize = 11.sp, color = KbTextDim
                                )
                            }
                            if (!deleted) {
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching { KbApi.unsubscribe(docId) }
                                        load()
                                    }
                                }) { Text("退订", fontSize = 12.sp, color = KbRed) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============ 回收站（对标 MobileRecycle）============

@Composable
fun RecycleScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(Load.Loading) }
    var purgeTarget by remember { mutableStateOf<JSONObject?>(null) }
    var msg by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }

    fun load() {
        state = Load.Loading
        scope.launch {
            try {
                items = KbApi.recycleList().objects()
                state = if (items.isEmpty()) Load.Empty else Load.Ok
            } catch (e: Exception) {
                err = e.message ?: "加载失败"
                state = Load.Error
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "回收站", onBack = onBack)
        if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = KbGreen, modifier = Modifier.padding(horizontal = 16.dp))
        when (state) {
            Load.Loading -> KbLoading()
            Load.Empty -> KbEmpty("🗑️", "回收站是空的")
            Load.Error -> com.kb.kbplatform.shell.ui.common.KbError("回收站加载失败：$err", onRetry = { load() })
            Load.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.optLong("id", 0) }) { d ->
                    val id = d.optLong("id", 0)
                    KbCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(d.optString("title").ifBlank { "未命名" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(formatDateTime(d.optString("updatedAt")), fontSize = 11.sp, color = KbTextDim)
                            Row {
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching { KbApi.recycleRestore(id) }
                                        items = items.filter { it.optLong("id", 0) != id }
                                        msg = "已恢复"
                                    }
                                }) { Text("恢复", fontSize = 12.sp) }
                                TextButton(onClick = { purgeTarget = d }) {
                                    Text("彻底删除", fontSize = 12.sp, color = KbRed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    purgeTarget?.let { d ->
        AlertDialog(
            onDismissRequest = { purgeTarget = null },
            title = { Text("彻底删除") },
            text = { Text("「${d.optString("title").ifBlank { "未命名" }}」将被物理删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { KbApi.recyclePurge(d.optLong("id", 0)) }
                        items = items.filter { it.optLong("id", 0) != d.optLong("id", 0) }
                        purgeTarget = null
                    }
                }) { Text("删除", color = KbRed) }
            },
            dismissButton = { TextButton(onClick = { purgeTarget = null }) { Text("取消") } }
        )
    }
}

// ============ 我的摘录（对标 MobileNotes）============

@Composable
fun NotesScreen(onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(Load.Loading) }
    var err by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        state = Load.Loading
        try {
            items = KbApi.myAnnotations().objects()
            state = if (items.isEmpty()) Load.Empty else Load.Ok
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
            state = Load.Error
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "我的摘录", onBack = onBack)
        when (state) {
            Load.Loading -> KbLoading()
            Load.Empty -> KbEmpty("✂️", "还没有摘录\n在文档阅读页划词保存摘录")
            Load.Error -> com.kb.kbplatform.shell.ui.common.KbError("摘录加载失败：$err", onRetry = { tick++ })
            Load.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.optString("id", it.hashCode().toString()) + it.hashCode() }) { n ->
                    KbCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                n.optString("text").ifBlank { n.optString("content") },
                                fontSize = 14.sp, maxLines = 3
                            )
                            if (n.optString("note").isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "笔记：${n.optString("note")}",
                                    fontSize = 12.sp, color = KbTextDim, maxLines = 2
                                )
                            }
                            Text(
                                listOf(
                                    n.optString("docTitle").ifBlank { "文档" },
                                    formatDateTime(n.optString("createdAt"))
                                ).joinToString(" · "),
                                fontSize = 11.sp, color = KbTextDim
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============ 全部文档（对标 MobileAllDocs：跨空间聚合 + 标题过滤）============

@Composable
fun AllDocsScreen(onBack: () -> Unit, openWeb: (String, String) -> Unit, openRoute: (String) -> Unit = {}) {
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var keyword by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(Load.Loading) }
    var err by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        state = Load.Loading
        try {
            items = KbApi.allShareDocs().objects()
            state = if (items.isEmpty()) Load.Empty else Load.Ok
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
            state = Load.Error
        }
    }

    val filtered = if (keyword.isBlank()) items
    else items.filter { it.optString("title").contains(keyword, ignoreCase = true) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "全部文档", onBack = onBack)
        androidx.compose.material3.OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            placeholder = { Text("按标题筛选") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
        when (state) {
            Load.Loading -> KbLoading()
            Load.Empty -> KbEmpty("📄", "还没有文档")
            Load.Error -> com.kb.kbplatform.shell.ui.common.KbError("全部文档加载失败：$err", onRetry = { tick++ })
            Load.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.optLong("id", it.hashCode().toLong()) }) { d ->
                    val docId = if (d.has("docId")) d.optLong("docId") else d.optLong("id", 0)
                    KbCard(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                openRoute("reader/${d.optLong("shareId", 0)}/$docId?title=" + android.net.Uri.encode(d.optString("title")))
                            }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(d.optString("title").ifBlank { "未命名文档" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                listOf(
                                    d.optString("spaceName").ifBlank { "空间" },
                                    formatDateTime(d.optString("updatedAt"))
                                ).joinToString(" · "),
                                fontSize = 11.sp, color = KbTextDim
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============ 浏览历史（对标 MobileHistory：本地记录）============

@Composable
fun HistoryScreen(onBack: () -> Unit, openWeb: (String, String) -> Unit, openRoute: (String) -> Unit = {}) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var items by remember { mutableStateOf(HistoryStore.list(ctx)) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) { items = HistoryStore.list(ctx) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "浏览历史", onBack = onBack) {
            TextButton(onClick = { HistoryStore.clear(ctx); refresh++ }) { Text("清空", fontSize = 13.sp) }
        }
        if (items.isEmpty()) {
            KbEmpty("🕘", "暂无浏览记录")
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { "${it.shareId}:${it.docId}:${it.at}" }) { h ->
                    KbCard(Modifier.fillMaxWidth().clickable {
                        openRoute("reader/${h.shareId}/${h.docId}?title=" + android.net.Uri.encode(h.title))
                    }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(h.title.ifBlank { "未命名文档" }, fontSize = 14.sp)
                                Text(
                                    formatDateTime(
                                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
                                            .format(java.util.Date(h.at))
                                    ),
                                    fontSize = 11.sp, color = KbTextDim
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

