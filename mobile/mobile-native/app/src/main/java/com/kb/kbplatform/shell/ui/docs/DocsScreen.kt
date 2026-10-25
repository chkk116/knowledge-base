/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.docs

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
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
import com.kb.kbplatform.shell.ui.common.formatDateTime
import org.json.JSONObject

private val SpaceTints = listOf(
    Color(0xFF409EFF), Color(0xFF22C55E), Color(0xFFF59E0B),
    Color(0xFF8B5CF6), Color(0xFF06B6D4), Color(0xFFEC4899)
)

/**
 * 文档库 Tab：两级结构（空间列表 → 文档列表），与 Capacitor 版 /docs 一致。
 * 文档点开进入 WebView 承载的 Tiptap 阅读页（协同编辑能力原样保留）。
 */
@Composable
fun DocsScreen(openWeb: (path: String, title: String) -> Unit) {
    var spaces by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedSpace by remember { mutableStateOf<JSONObject?>(null) }
    var docs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey, selectedSpace) {
        loading = true
        error = ""
        try {
            val sp = selectedSpace
            if (sp == null) {
                spaces = KbApi.myShares().objects()
            } else {
                docs = KbApi.shareDocs(sp.optLong("id", 0)).objects()
            }
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    val space = selectedSpace
    Column(Modifier.fillMaxSize()) {
        if (space == null) {
            TopBar(title = "文档库") {
                IconButton(onClick = { openWeb("/m/search", "搜索") }) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索")
                }
            }
        } else {
            TopBar(title = space.optString("name").ifBlank { "文档库" }, onBack = { selectedSpace = null })
        }

        if (loading) {
            CenterHint("加载中…", loading = true)
        } else if (error.isNotEmpty()) {
            CenterHint(error)
        } else if (space == null) {
            if (spaces.isEmpty()) {
                CenterHint("还没有空间，去 Web 端创建一个吧")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(spaces, key = { it.optLong("id", 0) }) { s ->
                        val id = s.optLong("id", 0)
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedSpace = s }
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(38.dp)
                                        .background(SpaceTints[(id % SpaceTints.size).toInt()].copy(alpha = 0.16f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        s.optString("name").ifBlank { "空" }.take(1),
                                        color = SpaceTints[(id % SpaceTints.size).toInt()],
                                        fontWeight = FontWeight.Bold, fontSize = 16.sp
                                    )
                                }
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.optString("name").ifBlank { "未命名空间" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        listOf(
                                            s.optString("type").ifBlank { "TEAM" },
                                            s.optLong("memberCount", 0).toString() + " 名成员"
                                        ).joinToString(" · "),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        } else {
            if (docs.isEmpty()) {
                CenterHint("这个空间还没有文档")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(docs, key = { it.optLong("docId", it.optLong("id", it.hashCode().toLong())) }) { d ->
                        val docId = if (d.has("docId")) d.optLong("docId") else d.optLong("id", 0)
                        val title = d.optString("title").ifBlank { "未命名文档" }
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    openWeb("/m/doc/${space.optLong("id", 0)}/$docId", title)
                                }
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    formatDateTime(d.optString("updatedAt")),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
