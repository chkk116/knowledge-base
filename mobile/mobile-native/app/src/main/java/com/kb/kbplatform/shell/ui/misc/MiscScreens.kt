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
import com.kb.kbplatform.shell.ui.theme.KbGreen
import com.kb.kbplatform.shell.ui.theme.KbRed
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 批量列表页集合（Wave A，逐一对标对应 MobileXxx.vue 的数据与操作）：
 * 待整理箱 / 本地收藏 / 我的考试 / 积分明细 / 排行榜 / 文档订阅 / 回收站 / 我的摘录 / 浏览历史。
 */

private enum class Load { Loading, Ok, Empty }

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
                state = Load.Empty
                msg = e.message ?: ""
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
                                        runCatching { spaces = KbApi.myShares().objects() }
                                        archiveTarget = d
                                    }
                                }) { Text("归档到空间", fontSize = 12.sp) }
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching { KbApi.deleteShareDoc(shareId, id) }
                                        items = items.filter { (if (it.has("docId")) it.optLong("docId") else it.optLong("id", 0)) != id }
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
                        runCatching {
                            KbApi.inboxArchive(id, spaces[target].optLong("id", 0), d.optString("title"))
                        }
                        archiveTarget = null
                        msg = "已归档"
                        load()
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
    var items by remember { mutableStateOf(Bookmarks.list(ctx)) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) { items = Bookmarks.list(ctx) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "我的收藏", onBack = onBack)
        if (items.isEmpty()) {
            KbEmpty("⭐", "还没有收藏\n在文档阅读页点 ☆ 收藏")
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { "${it.shareId}:${it.docId}" }) { b ->
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

// ============ 我的考试（对标 MobileExamMine）============

@Composable
fun ExamMineScreen(onBack: () -> Unit) {
    var records by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(Load.Loading) }
    LaunchedEffect(Unit) {
        try {
            records = KbApi.myExams().objects()
            state = if (records.isEmpty()) Load.Empty else Load.Ok
        } catch (e: Exception) { state = Load.Empty }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "我的考试", onBack = onBack)
        when (state) {
            Load.Loading -> KbLoading()
            Load.Empty -> KbEmpty("🏆", "还没有考试记录")
            Load.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(records, key = { it.optLong("id", 0) }) { r ->
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

    fun load(p: Int) {
        state = Load.Loading
        scope.launch {
            try {
                val r = KbApi.pointsLog(p)
                items = r.optJSONArray("content")?.objects() ?: emptyList()
                total = r.optInt("totalElements", items.size)
                page = p
                state = if (items.isEmpty()) Load.Empty else Load.Ok
            } catch (e: Exception) { state = Load.Empty }
        }
    }
    LaunchedEffect(Unit) { load(0) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "积分明细", onBack = onBack)
        when (state) {
            Load.Loading -> KbLoading()
            Load.Empty -> KbEmpty("📈", "暂无积分记录")
            Load.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.optLong("id", it.hashCode().toLong()) }) { l ->
                    val pts = l.optInt("points", 0)
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
    var state by remember { mutableStateOf(Load.Loading) }
    LaunchedEffect(Unit) {
        try {
            items = KbApi.ranking().objects()
            state = if (items.isEmpty()) Load.Empty else Load.Ok
        } catch (e: Exception) { state = Load.Empty }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "积分排行榜", onBack = onBack)
        when (state) {
            Load.Loading -> KbLoading()
            Load.Empty -> KbEmpty("🏅", "暂无排行数据")
            Load.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.optString("username", it.hashCode().toString()) + it.hashCode() }) { r ->
                    KbCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                listOf("🥇", "🥈", "🥉").getOrNull(items.indexOf(r)) ?: "${items.indexOf(r) + 1}",
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
fun SubscriptionsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(Load.Loading) }

    fun load() {
        state = Load.Loading
        scope.launch {
            try {
                items = KbApi.subscriptions().objects()
                state = if (items.isEmpty()) Load.Empty else Load.Ok
            } catch (e: Exception) { state = Load.Empty }
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "我的订阅", onBack = onBack)
        when (state) {
            Load.Loading -> KbLoading()
            Load.Empty -> KbEmpty("🔔", "还没有订阅的文档\n订阅后文档更新会通知你")
            Load.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.optString("id", it.hashCode().toString()) + it.hashCode() }) { s ->
                    val deleted = s.optBoolean("deleted", false)
                    KbCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.optString("title").ifBlank { "已删除文档" },
                                    fontSize = 14.sp,
                                    color = if (deleted) KbTextDim else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (!deleted) {
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching { KbApi.unsubscribe(s.optLong("id", s.optLong("docId", 0))) }
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

    fun load() {
        state = Load.Loading
        scope.launch {
            try {
                items = KbApi.recycleList().objects()
                state = if (items.isEmpty()) Load.Empty else Load.Ok
            } catch (e: Exception) { state = Load.Empty }
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "回收站", onBack = onBack)
        if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = KbGreen, modifier = Modifier.padding(horizontal = 16.dp))
        when (state) {
            Load.Loading -> KbLoading()
            Load.Empty -> KbEmpty("🗑️", "回收站是空的")
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
    LaunchedEffect(Unit) {
        try {
            items = KbApi.myAnnotations().objects()
            state = if (items.isEmpty()) Load.Empty else Load.Ok
        } catch (e: Exception) { state = Load.Empty }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "我的摘录", onBack = onBack)
        when (state) {
            Load.Loading -> KbLoading()
            Load.Empty -> KbEmpty("✂️", "还没有摘录\n在文档阅读页划词保存摘录")
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

    LaunchedEffect(Unit) {
        try {
            items = KbApi.allShareDocs().objects()
            state = Load.Ok
        } catch (e: Exception) { state = Load.Empty }
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

