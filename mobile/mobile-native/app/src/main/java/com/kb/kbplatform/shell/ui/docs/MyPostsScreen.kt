/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.docs

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
import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.KbCard

import com.kb.kbplatform.shell.ui.common.KbEmpty
import com.kb.kbplatform.shell.ui.common.KbLoading
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.formatDateTime
import com.kb.kbplatform.shell.ui.misc.L2
import com.kb.kbplatform.shell.ui.theme.KbGreen
import com.kb.kbplatform.shell.ui.theme.KbRed
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import com.kb.kbplatform.shell.ui.theme.KbWarn
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 我的帖子（对标 Web 端 /my/posts）：作者视角的文章列表 + 草稿提交审核 / 待审撤回 / 删除。
 *
 * 后端口径：
 *   `GET /api/docs/mine`（含 DRAFT/PENDING/PUBLISHED/BLOCKED）
 *   `POST /api/docs/{id}/submit`（草稿 → 待审核；普通用户不能自发布）
 *   `POST /api/docs/{id}/withdraw`（待审核 → 草稿，审核前可拉回）
 *   `DELETE /api/docs/{id}`（作者删自己的文章，与后台 admin 删除不是同一路径）
 */
@Composable
fun MyPostsScreen(onBack: () -> Unit, openPost: (Long, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    var tick by remember { mutableIntStateOf(0) }
    var busyId by remember { mutableStateOf(0L) }
    var msg by remember { mutableStateOf("") }
    var delTarget by remember { mutableStateOf<JSONObject?>(null) }

    fun load() {
        state = L2.Loading
        msg = ""
        scope.launch {
            try {
                items = KbApi.myDocs().objects()
                state = if (items.isEmpty()) L2.Empty else L2.Ok
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                msg = e.message ?: "加载失败"
                // 刷新失败时保留已有列表，不要用空态盖掉用户已经看到的文章
                state = if (items.isEmpty()) L2.Empty else L2.Ok
            }
        }
    }
    LaunchedEffect(tick) { load() }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "我的帖子", onBack = onBack, actions = {
            TextButton(onClick = { tick++ }) { Text("刷新", fontSize = 13.sp) }
        })
        if (msg.isNotEmpty()) {
            Text(
                msg, fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("📝", "还没有写过文章\n在 Web 端编辑器里可以写")
            L2.Ok -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items, key = { i, it -> it.optLong("id", -1L).let { id -> if (id == -1L) "post-$i" else id } }) { i, d ->
                    val id = d.optLong("id", 0)
                    val status = d.optString("status")
                    val busy = busyId == id
                    KbCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    d.optString("title").ifBlank { "未命名" },
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                )
                                Text(
                                    statusCn(status),
                                    fontSize = 11.sp,
                                    color = when (status) {
                                        "PUBLISHED" -> KbGreen
                                        "PENDING" -> KbWarn
                                        "BLOCKED" -> KbRed
                                        else -> KbTextDim
                                    },
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOfNotNull(
                                    formatDateTime(d.optString("updatedAt")).takeIf { it.isNotBlank() }
                                        ?.let { "更新于 $it" },
                                    "${d.optInt("views")} 阅",
                                    d.optString("rejectReason").takeIf { it.isNotBlank() }?.let { "驳回：$it" },
                                ).joinToString(" · "),
                                fontSize = 11.sp,
                                color = KbTextDim,
                                maxLines = 2,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (status == "PUBLISHED" || status == "BLOCKED") {
                                    TextButton(
                                        onClick = { openPost(id, d.optString("title")) },
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) { Text("查看", fontSize = 12.sp) }
                                }
                                if (status == "DRAFT") {
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            busyId = id
                                            scope.launch {
                                                try {
                                                    KbApi.submitDocReview(id)
                                                    tick++
                                                } catch (e: kotlinx.coroutines.CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    msg = e.message ?: "提交失败"
                                                } finally {
                                                    if (busyId == id) busyId = 0
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) { Text(if (busy) "提交中…" else "提交审核", fontSize = 12.sp) }
                                }
                                if (status == "PENDING") {
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            busyId = id
                                            scope.launch {
                                                try {
                                                    KbApi.withdrawDocReview(id)
                                                    tick++
                                                } catch (e: kotlinx.coroutines.CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    msg = e.message ?: "撤回失败"
                                                } finally {
                                                    if (busyId == id) busyId = 0
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) { Text(if (busy) "撤回中…" else "撤回审核", fontSize = 12.sp) }
                                }
                                TextButton(
                                    onClick = { delTarget = d },
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) { Text("删除", fontSize = 12.sp, color = KbRed) }
                            }
                        }
                    }
                }
            }
        }
    }

    delTarget?.let { d ->
        AlertDialog(
            onDismissRequest = { delTarget = null },
            title = { Text("删除文章") },
            text = {
                Text(
                    "确定删除「${d.optString("title").ifBlank { "未命名" }}」吗？" +
                        if (d.optString("status") == "PUBLISHED") "已发布的文章删除后读者将无法访问。" else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = d.optLong("id", 0)
                    if (id <= 0) { delTarget = null; return@TextButton }
                    busyId = id
                    delTarget = null
                    scope.launch {
                        try {
                            KbApi.deleteMyDoc(id)
                            tick++
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            msg = e.message ?: "删除失败"
                        } finally {
                            if (busyId == id) busyId = 0
                        }
                    }
                }) { Text("删除", color = KbRed) }
            },
            dismissButton = { TextButton(onClick = { delTarget = null }) { Text("取消") } }
        )
    }
}

private fun statusCn(s: String) = when (s) {
    "PUBLISHED" -> "已发布"
    "PENDING" -> "待审核"
    "DRAFT" -> "草稿"
    "BLOCKED" -> "已封禁"
    "DELETED" -> "已删除"
    else -> s.ifBlank { "—" }
}