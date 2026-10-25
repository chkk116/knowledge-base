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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.CenterHint
import com.kb.kbplatform.shell.ui.common.TopBar
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * 首页：数据概览（/docs/overview）+ 公告（/announcements）+ 快捷入口。
 * 快捷入口走 WebView 承载页打开对应 /m/ 下的页面——竖切阶段这些页面继续由 Web 提供。
 */
@Composable
fun HomeScreen(openWeb: (path: String, title: String) -> Unit) {
    var overview by remember { mutableStateOf<JSONObject?>(null) }
    var announcements by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        error = ""
        try {
            coroutineScope {
                val ov = async { KbApi.overview() }
                val an = async { KbApi.announcements() }
                overview = ov.await()
                announcements = an.await().objects()
            }
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "知识库") {
            IconButton(onClick = { refreshKey++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }

        if (loading && overview == null) {
            CenterHint("加载中…", loading = true)
        } else if (error.isNotEmpty() && overview == null) {
            CenterHint(error)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { OverviewCard(overview) }
                item {
                    Text("快捷入口", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp))
                }
                item { ShortcutGrid(openWeb) }
                if (announcements.isNotEmpty()) {
                    item {
                        Text("公告", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    items(announcements, key = { it.optLong("id", it.hashCode().toLong()) }) { a ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(a.optString("title"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                if (a.optString("content").isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        a.optString("content"),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3
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

@Composable
private fun OverviewCard(ov: JSONObject?) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Stat(ov?.optLong("totalDocs", -1) ?: -1, "文档")
            Stat(ov?.optLong("weekNew", -1) ?: -1, "本周新增")
            Stat(ov?.optLong("members", -1) ?: -1, "成员")
            Stat(ov?.optLong("pendingReview", -1) ?: -1, "待审核")
        }
    }
}

@Composable
private fun Stat(num: Long, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (num >= 0) num.toString() else "—",
            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White
        )
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
    }
}

private data class Shortcut(val label: String, val path: String, val tint: Color)

@Composable
private fun ShortcutGrid(openWeb: (String, String) -> Unit) {
    val shortcuts = listOf(
        Shortcut("AI 问答", "/m/ai", Color(0xFF409EFF)),
        Shortcut("搜索", "/m/search", Color(0xFF22C55E)),
        Shortcut("复习卡片", "/m/cards", Color(0xFFF59E0B)),
        Shortcut("学习计划", "/m/plans", Color(0xFF8B5CF6)),
        Shortcut("待整理箱", "/m/inbox", Color(0xFF06B6D4)),
        Shortcut("全部功能", "/m/", Color(0xFF64748B))
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shortcuts.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { s ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { openWeb(s.path, s.label) }
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .background(s.tint.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .background(s.tint, CircleShape)
                                )
                            }
                            Spacer(Modifier.size(10.dp))
                            Text(s.label, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
