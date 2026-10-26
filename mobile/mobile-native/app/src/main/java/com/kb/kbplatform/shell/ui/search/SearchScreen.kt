/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.search

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.CenterHint
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.formatDateTime
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val HIST_KEY = "kb_search_hist"
private const val HIST_MAX = 10

private data class DocHit(
    val shareId: Long,
    val docId: Long,
    val title: String,
    val snippet: String,
    val updatedAt: String
)

/**
 * 搜索页（原生）：与 Web 版 MobileSearch 同源逻辑——
 * 拉取「我的空间 → 各空间文档」，标题/正文包含即命中（大小写不敏感），
 * 摘要取命中处前后片段。点开进 WebView 文档阅读页。
 */
@Composable
fun SearchScreen(openWeb: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()

    var keyword by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DocHit>>(emptyList()) }
    var history by remember { mutableStateOf(listOf<String>()) }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        history = ctx.getSharedPreferences(HIST_KEY, Context.MODE_PRIVATE)
            .getString("list", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    }

    fun rememberKeyword(k: String) {
        history = (listOf(k) + history.filter { it != k }).take(HIST_MAX)
        ctx.getSharedPreferences(HIST_KEY, Context.MODE_PRIVATE)
            .edit().putString("list", history.joinToString("\n")).apply()
    }

    fun doSearch() {
        val q = keyword.trim()
        if (q.isEmpty() || searching) return
        searching = true
        searched = true
        error = ""
        rememberKeyword(q)
        scope.launch {
            try {
                val hits = mutableListOf<DocHit>()
                val shares = KbApi.myShares().objects()
                for (s in shares) {
                    val shareId = s.optLong("id", 0)
                    for (d in KbApi.shareDocs(shareId).objects()) {
                        val title = d.optString("title")
                        val content = plain(d.optString("content"))
                        val idx = indexOfHit(title, content, q)
                        if (idx >= 0) {
                            val docId = if (d.has("docId")) d.optLong("docId") else d.optLong("id", 0)
                            hits.add(
                                DocHit(
                                    shareId = shareId,
                                    docId = docId,
                                    title = title.ifBlank { "未命名文档" },
                                    snippet = snippet(content, idx, q),
                                    updatedAt = d.optString("updatedAt")
                                )
                            )
                        }
                    }
                }
                // 标题命中的排前面，其次按更新时间
                results = hits.sortedWith(
                    compareByDescending<DocHit> { it.title.contains(q, ignoreCase = true) }
                        .thenByDescending { it.updatedAt }
                )
            } catch (e: Exception) {
                error = e.message ?: "搜索失败"
            } finally {
                searching = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "搜索")
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            placeholder = { Text("搜索文档标题与正文") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (keyword.isNotEmpty()) {
                    Icon(Icons.Filled.Close, contentDescription = "清空",
                        Modifier.clickable { keyword = ""; searched = false; results = emptyList() })
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        when {
            searching -> CenterHint("搜索中…", loading = true)
            error.isNotEmpty() -> CenterHint(error)
            !searched -> {
                if (history.isNotEmpty()) {
                    LazyColumn(contentPadding = PaddingValues(16.dp)) {
                        item {
                            Text("搜索历史", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                        }
                        items(history) { h ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { keyword = h; doSearch() }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text("🕒", fontSize = 14.sp)
                                Spacer(Modifier.size(10.dp))
                                Text(h, fontSize = 14.sp)
                            }
                        }
                    }
                } else {
                    CenterHint("输入关键词搜索你的文档")
                }
            }
            results.isEmpty() -> CenterHint("没有找到相关文档")
            else -> {
                Text(
                    "共 ${results.size} 条结果",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results, key = { "${it.shareId}-${it.docId}" }) { r ->
                        KbCard(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openWeb("/m/doc/${r.shareId}/${r.docId}", r.title) }
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(r.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    r.snippet,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    formatDateTime(r.updatedAt),
                                    fontSize = 11.sp,
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

/** 命中位置：标题命中恒排第一优先（返回 -1 表示不命中） */
private fun indexOfHit(title: String, content: String, q: String): Int {
    val t = title.indexOf(q, ignoreCase = true)
    if (t >= 0) return 0
    return content.indexOf(q, ignoreCase = true)
}

private fun snippet(content: String, idx: Int, q: String): String {
    if (content.isEmpty()) return ""
    val start = (idx - 20).coerceAtLeast(0)
    val end = (idx + q.length + 60).coerceAtMost(content.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < content.length) "…" else ""
    return prefix + content.substring(start, end).replace('\n', ' ') + suffix
}

private fun plain(html: String): String =
    android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
