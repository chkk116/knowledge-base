/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.home

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.TokenStore
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.ShareInbox
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.KbEmpty
import com.kb.kbplatform.shell.ui.common.KbLoading
import com.kb.kbplatform.shell.ui.common.formatDateTime
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

private data class Quick(val label: String, val emoji: String, val tint: Color, val route: String?)

/**
 * 首页 · 严格对标 MobileHome.vue：
 * 蓝色 hero（问候+姓名+头像 / 搜索条+AI 按钮）→ 分享/待复习提示卡 → 快捷宫格 →
 * 四联统计 → 公告条 → 最近文档横滑 → 空间列表。
 */
@Composable
fun HomeScreen(
    openWeb: (path: String, title: String) -> Unit,
    openRoute: (String) -> Unit
) {
    var overview by remember { mutableStateOf<JSONObject?>(null) }
    var announcement by remember { mutableStateOf<JSONObject?>(null) }
    var dueCards by remember { mutableIntStateOf(0) }
    var recent by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var spaces by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val sharePending by ShareInbox.pending.collectAsState()
    val draftCtx = androidx.compose.ui.platform.LocalContext.current
    var draftCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { draftCount = com.kb.kbplatform.shell.data.DraftStore.list(draftCtx).size }

    LaunchedEffect(Unit) {
        loading = true
        error = ""
        try {
            coroutineScope {
                val ov = async { runCatching { KbApi.overview() }.getOrNull() }
                val an = async { runCatching { KbApi.announcements().objects() }.getOrDefault(emptyList()) }
                val due = async { runCatching { KbApi.dueCards().objects().size }.getOrDefault(0) }
                val all = async { runCatching { KbApi.allShareDocs().objects() }.getOrDefault(emptyList()) }
                val sp = async { runCatching { KbApi.myShares().objects() }.getOrDefault(emptyList()) }
                overview = ov.await()
                announcement = an.await().firstOrNull()
                dueCards = due.await()
                recent = all.await().sortedByDescending { it.optString("updatedAt") }.take(10)
                spaces = sp.await()
            }
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Hero(onAvatar = { openRoute("profile") }, onSearch = { openRoute("search") }, onAi = { openRoute("ai") })

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loading) {
                item { KbLoading() }
            } else if (error.isNotEmpty()) {
                item { KbEmpty("⚠️", error) }
            } else {
                if (sharePending.isNotEmpty()) {
                    item {
                        NoticeCard(
                            "📥", Color(0xFF1677FF),
                            "收到 ${sharePending.size} 个分享文件", "点击导入到知识库"
                        ) { openRoute("share-import") }
                    }
                }
                if (draftCount > 0) {
                    item {
                        NoticeCard(
                            "📤", Color(0xFFEA580C),
                            "$draftCount 篇草稿待同步", "联网后会自动上传到「我的文档」",
                            onClick = { openRoute("drafts") }
                        )
                    }
                }
                if (dueCards > 0) {
                    item {
                        NoticeCard(
                            "🎴", Color(0xFFF59E0B),
                            "$dueCards 张卡片待复习", "花两分钟过一遍，别让记过的忘掉"
                        ) { openRoute("cards") }
                    }
                }
                item {
                    val quicks = listOf(
                        Quick("拍照建文档", "📷", Color(0xFF1677FF), "capture"),
                        Quick("速记", "✏️", Color(0xFF06B6D4), "quick"),
                        Quick("网页剪藏", "🔗", Color(0xFF409EFF), "clip"),
                        Quick("复习卡片", "🎴", Color(0xFFF59E0B), "cards"),
                        Quick("模拟练习", "📝", Color(0xFF22C55E), "practice"),
                        Quick("考试中心", "🏆", Color(0xFF10B981), "exams"),
                        Quick("AI 问答", "🪄", Color(0xFF8B5CF6), "ai"),
                        Quick("搜索", "🔍", Color(0xFF64748B), "search")
                    )
                    KbCard {
                        Column(Modifier.padding(10.dp)) {
                            quicks.chunked(4).forEach { row ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                    row.forEach { q ->
                                        Column(
                                            Modifier
                                                .weight(1f)
                                                .clickable { openRoute(q.route!!) },
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(44.dp)
                                                    .background(q.tint.copy(alpha = 0.13f), RoundedCornerShape(13.dp)),
                                                contentAlignment = Alignment.Center
                                            ) { Text(q.emoji, fontSize = 20.sp) }
                                            Spacer(Modifier.height(5.dp))
                                            Text(q.label, fontSize = 11.sp, maxLines = 1)
                                        }
                                    }
                                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
                item {
                    KbCard {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Stat(overview?.optLong("totalDocs", -1) ?: -1, "文档")
                            Stat(overview?.optLong("weekNew", -1) ?: -1, "本周新增")
                            Stat(-1, "连续天数")
                            Stat(-1, "本周阅读")
                        }
                    }
                }
                announcement?.let { a ->
                    item {
                        KbCard(Modifier.clickable { openWeb("/m/", "公告") }) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("📣", fontSize = 17.sp)
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        a.optString("title").ifBlank { "公告" },
                                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                                    )
                                    Text(a.optString("content"), fontSize = 12.sp, color = KbTextDim, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                if (recent.isNotEmpty()) {
                    item {
                        Text("最近文档", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(recent, key = { it.optLong("id", it.hashCode().toLong()) }) { d ->
                                val docId = if (d.has("docId")) d.optLong("docId") else d.optLong("id", 0)
                                KbCard(
                                    Modifier
                                        .size(width = 150.dp, height = 96.dp)
                                        .clickable {
                                            openRoute(
                                                "reader/${d.optLong("shareId", 0)}/$docId?title=" +
                                                    android.net.Uri.encode(d.optString("title"))
                                            )
                                        }
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            d.optString("spaceName").ifBlank { "空间" },
                                            fontSize = 10.sp, color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            d.optString("title").ifBlank { "未命名文档" },
                                            fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            formatDateTime(d.optString("updatedAt")),
                                            fontSize = 10.sp, color = KbTextDim
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (spaces.isNotEmpty()) {
                    item {
                        Text("我的空间", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        KbCard {
                            spaces.take(5).forEach { s ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { openRoute("docs") }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier
                                            .size(34.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            s.optString("name").ifBlank { "空" }.take(1),
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold, fontSize = 14.sp
                                        )
                                    }
                                    Spacer(Modifier.size(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(s.optString("name").ifBlank { "未命名空间" }, fontSize = 14.sp)
                                        Text(
                                            s.optString("type") + " · " + s.optLong("memberCount", 0) + " 名成员",
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
    }
}

private fun greet(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..8 -> "早上好"
        in 9..11 -> "上午好"
        in 12..13 -> "中午好"
        in 14..17 -> "下午好"
        else -> "晚上好"
    }
}

@Composable
private fun Hero(onAvatar: () -> Unit, onSearch: () -> Unit, onAi: () -> Unit) {
    val me = TokenStore.info.value
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF3B8AFF), Color(0xFF1677FF))),
                RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
            )
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greet(), fontSize = 13.sp, color = Color.White.copy(alpha = 0.82f))
                Text(
                    me?.optString("displayName").orEmpty().ifBlank { "…"},
                    fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, letterSpacing = (-0.3).sp
                )
            }
            Box(
                Modifier
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
                    .clickable(onClick = onAvatar),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    (me?.optString("displayName") ?: "").ifBlank { "?" }.take(1),
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .clickable(onClick = onSearch)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Search, null,
                    tint = Color.White.copy(alpha = 0.88f), modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text("搜索文档", fontSize = 14.sp, color = Color.White.copy(alpha = 0.88f))
            }
            Box(
                Modifier
                    .size(42.dp)
                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .clickable(onClick = onAi),
                contentAlignment = Alignment.Center
            ) {
                Text("🪄", fontSize = 17.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun NoticeCard(emoji: String, tint: Color, title: String, desc: String, onClick: () -> Unit) {
    KbCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 20.sp)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tint)
                Text(desc, fontSize = 12.sp, color = KbTextDim)
            }
        }
    }
}

@Composable
private fun Stat(num: Long, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (num >= 0) num.toString() else "—",
            fontSize = 19.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
