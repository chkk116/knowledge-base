package com.kb.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 管理：帖子回收站（2026-11-16）
//   GET /api/admin/docs/recycle（POST_MANAGE）→ 恢复 POST /api/admin/docs/{id}/restore。
//   桌面端只做「恢复」；彻底删除与批量操作留在 Web（不可逆，且回收站本就是为了防误删）。

@Composable
internal fun RecycleTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<AdminDocItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick) {
        loading = true
        err = null
        try { rows = api.recycleDocs(session.token) } catch (e: Exception) { err = e.message ?: "加载失败" } finally { loading = false }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("回收站", "${rows.size} 篇", onRefresh = { tick++ })
        Text(
            "恢复会按原状态放回列表；彻底删除请在 Web 端操作（不可逆）。",
            fontSize = 10.5.sp,
            color = Color(0xFFB45309),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
        HorizontalDivider(color = KbBorder)
        Box(Modifier.weight(1f)) {
            when {
                loading -> AdminHint("加载中…")
                err != null -> AdminError(err ?: "", { tick++ })
                rows.isEmpty() -> AdminHint("回收站是空的")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { d ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    d.title.ifBlank { "（无标题）" },
                                    fontSize = 13.sp,
                                    color = KbTextMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    listOfNotNull(
                                        d.category?.takeIf { it.isNotBlank() },
                                        d.ownerName.takeIf { it.isNotBlank() },
                                        d.status.takeIf { it.isNotBlank() },
                                        fmtAdminTime(d.updatedAt).takeIf { it.isNotBlank() },
                                    ).joinToString(" · "),
                                    fontSize = 10.5.sp,
                                    color = KbTextSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            AdminButton("恢复", enabled = !busy) {
                                scope.launch {
                                    busy = true
                                    try {
                                        api.restoreDoc(session.token, d.id)
                                        rows = rows.filterNot { it.id == d.id }
                                        toast = "已恢复「${d.title.ifBlank { "无标题" }}」"
                                    } catch (e: Exception) {
                                        toast = e.message ?: "恢复失败"
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                    }
                }
            }
        }
    }
    AdminToast(toast)
}