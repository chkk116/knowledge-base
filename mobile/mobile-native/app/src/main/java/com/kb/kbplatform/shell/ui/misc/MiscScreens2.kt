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
import androidx.compose.material3.AlertDialog
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
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.KbButton
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.KbEmpty
import com.kb.kbplatform.shell.ui.common.KbLoading
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.formatDateTime
import com.kb.kbplatform.shell.ui.theme.KbBlue
import com.kb.kbplatform.shell.ui.theme.KbGreen
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import com.kb.kbplatform.shell.ui.theme.KbWarn
import com.kb.kbplatform.shell.ui.theme.KbRed
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal enum class L2 { Loading, Ok, Empty }

private fun simpleList(raw: JSONArray?): List<JSONObject> {
    raw ?: return emptyList()
    return (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
}

// ============ 课程中心（对标 MobileCourses）============

@Composable
fun CoursesScreen(onBack: () -> Unit, openCourse: (Long, String) -> Unit) {
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    LaunchedEffect(Unit) {
        try {
            items = simpleList(KbApi.courses())
            state = if (items.isEmpty()) L2.Empty else L2.Ok
        } catch (e: Exception) { state = L2.Empty }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "课程中心", onBack = onBack)
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("📚", "暂无课程")
            L2.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.optLong("id", 0) }) { c ->
                    KbCard(Modifier.fillMaxWidth().clickable {
                        openCourse(c.optLong("id", 0), c.optString("title"))
                    }) {
                        Column(Modifier.padding(14.dp)) {
                            Text(c.optString("title").ifBlank { "未命名课程" },
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            if (c.optString("description").isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(c.optString("description"), fontSize = 12.sp,
                                    color = KbTextDim, maxLines = 2)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOf(
                                    "${c.optInt("chapterCount", c.optJSONArray("chapters")?.length() ?: 0)} 章节",
                                    formatDateTime(c.optString("createdAt"))
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

// ============ 课程详情 + 章节（对标 MobileCourseDetail/MobileChapter 的学习闭环）============

@Composable
fun CourseDetailScreen(courseId: Long, titleHint: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var chapters by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    var doneIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(courseId) {
        try {
            detail = KbApi.courseDetail(courseId)
            chapters = detail?.optJSONArray("chapters")?.objects() ?: emptyList()
            state = L2.Ok
        } catch (e: Exception) { state = L2.Empty }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = titleHint.ifBlank { "课程" }, onBack = onBack)
        if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = KbGreen, modifier = Modifier.padding(horizontal = 16.dp))
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("📚", "课程不存在或已下架")
            L2.Ok -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                detail?.optString("description")?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, fontSize = 13.sp, color = KbTextDim)
                    Spacer(Modifier.height(12.dp))
                }
                Text("章节（${chapters.size}）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                chapters.forEachIndexed { i, ch ->
                    val chId = ch.optLong("id", 0)
                    val done = doneIds.contains(chId) || ch.optBoolean("finished", false)
                    KbCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(26.dp).background(
                                        if (done) KbGreen.copy(alpha = 0.15f) else KbBlue.copy(alpha = 0.12f),
                                        CircleShape
                                    ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (done) "✓" else "${i + 1}",
                                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        color = if (done) KbGreen else KbBlue
                                    )
                                }
                                Spacer(Modifier.size(10.dp))
                                Text(
                                    ch.optString("title").ifBlank { "第 ${i + 1} 章" },
                                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (ch.optString("content").isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    android.text.Html.fromHtml(ch.optString("content"), android.text.Html.FROM_HTML_MODE_LEGACY)
                                        .toString(),
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 6
                                )
                            }
                            if (!done) {
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching { KbApi.finishChapter(courseId, chId) }
                                        doneIds = doneIds + chId
                                        msg = "已完成一节"
                                    }
                                }) { Text("标记完成", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============ 错题本（对标 MobilePracticeWrong）============

@Composable
fun WrongBookScreen(onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    LaunchedEffect(Unit) {
        try {
            items = simpleList(KbApi.wrongBook())
            state = if (items.isEmpty()) L2.Empty else L2.Ok
        } catch (e: Exception) { state = L2.Empty }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "错题本", onBack = onBack)
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("📕", "没有错题，继续保持！")
            L2.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.optLong("id", it.hashCode().toLong()) }) { q ->
                    KbCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(q.optString("content"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            val ca = q.optJSONArray("correctAnswer")?.objects()
                                ?.joinToString("　") { "${it.optString("label")}. ${it.optString("content")}" }
                            if (!ca.isNullOrEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text("正确：$ca", fontSize = 12.sp, color = KbGreen)
                            }
                            if (q.optString("analysis").isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text(q.optString("analysis"), fontSize = 12.sp, color = KbTextDim)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============ 徽章成就（对标 MobileBadges）============

@Composable
fun BadgesScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            items = simpleList(KbApi.badges())
            state = if (items.isEmpty()) L2.Empty else L2.Ok
        } catch (e: Exception) { state = L2.Empty }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "徽章成就", onBack = onBack)
        if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = KbGreen, modifier = Modifier.padding(horizontal = 16.dp))
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("🎖️", "暂无徽章")
            L2.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.optLong("id", 0) }) { b ->
                    val owned = b.optBoolean("owned", b.optBoolean("earned", false))
                    KbCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (owned) "🎖️" else "🔒", fontSize = 24.sp)
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.optString("name").ifBlank { "徽章" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                if (b.optString("description").isNotEmpty()) {
                                    Text(b.optString("description"), fontSize = 12.sp, color = KbTextDim, maxLines = 2)
                                }
                            }
                            if (owned && !b.optBoolean("worn", false)) {
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching { KbApi.wearBadge(b.optLong("id", 0)) }
                                        msg = "已佩戴「${b.optString("name")}」"
                                    }
                                }) { Text("佩戴", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============ 文件中心（对标 MobileFiles）============

@Composable
fun FilesScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    var tick by remember { mutableIntStateOf(0) }
    var uploading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(tick) {
        try {
            items = simpleList(KbApi.myFiles())
            state = if (items.isEmpty()) L2.Empty else L2.Ok
        } catch (e: Exception) {
            state = L2.Empty
            msg = e.message ?: "加载失败"
        }
    }

    // 统一上传入口：任意类型文件 → 复制到 cache → multipart 走 /api/files（与扫描/分享导入同一条链路）
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        msg = ""
        scope.launch {
            var tmp: java.io.File? = null
            try {
                val name = queryDisplayName(ctx, uri) ?: "upload_${System.currentTimeMillis()}"
                val f = java.io.File(ctx.cacheDir, name)
                tmp = f
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException("无法读取所选文件")
                val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                KbApi.uploadFile(f, mime)
                msg = "上传成功：$name"
                tick++
            } catch (e: Exception) {
                msg = e.message ?: "上传失败"
            } finally {
                tmp?.let { runCatching { it.delete() } }
                uploading = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "我的文件", onBack = onBack, actions = {
            TextButton(enabled = !uploading, onClick = { picker.launch("*/*") }) {
                Text(if (uploading) "上传中…" else "上传", fontSize = 13.sp)
            }
        })
        if (msg.isNotEmpty()) {
            Text(
                msg, fontSize = 11.5.sp, color = KbBlue,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("🗂️", "暂无文件\n点右上角「上传」，或把文件分享到 App")
            L2.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.optLong("id", it.hashCode().toLong()) }) { f ->
                    val url = f.optString("url")
                    KbCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    f.optString("name").ifBlank { url.substringAfterLast('/') },
                                    fontSize = 14.sp, maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                                if (url.isNotEmpty()) {
                                    TextButton(
                                        onClick = {
                                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                as android.content.ClipboardManager
                                            cm.setPrimaryClip(android.content.ClipData.newPlainText("文件链接", url))
                                            msg = "已复制文件链接"
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) { Text("复制链接", fontSize = 11.5.sp) }
                                }
                            }
                            Text(
                                formatDateTime(f.optString("createdAt")),
                                fontSize = 11.sp, color = KbTextDim
                            )
                            if (url.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    url,
                                    fontSize = 10.sp, color = KbBlue, maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 取文件选择器返回的显示名（拿不到就返回 null，由调用方兜底命名） */
private fun queryDisplayName(ctx: android.content.Context, uri: android.net.Uri): String? = runCatching {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
}.getOrNull()

// ============ 版本历史（对标 MobileVersions）============

@Composable
fun VersionsScreen(shareId: Long, docId: Long, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            items = simpleList(KbApi.docVersions(shareId, docId))
            state = if (items.isEmpty()) L2.Empty else L2.Ok
        } catch (e: Exception) { state = L2.Empty }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "历史版本", onBack = onBack)
        if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = KbGreen, modifier = Modifier.padding(horizontal = 16.dp))
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("🕘", "还没有保存过版本快照")
            L2.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.optLong("id", it.hashCode().toLong()) }) { v ->
                    KbCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    v.optString("title").ifBlank { "版本 ${v.optLong("id", 0)}" },
                                    fontSize = 14.sp, fontWeight = FontWeight.Medium
                                )
                                Text(formatDateTime(v.optString("createdAt")), fontSize = 11.sp, color = KbTextDim)
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { KbApi.restoreDocVersion(shareId, docId, v.optLong("id", 0)) }
                                    msg = "已恢复到该版本"
                                }
                            }) { Text("恢复", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}

// ============ 角色管理（对标 MobileAdminRoles）============

@Composable
fun AdminRolesScreen(onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    LaunchedEffect(Unit) {
        try {
            items = simpleList(KbApi.adminRoles())
            state = if (items.isEmpty()) L2.Empty else L2.Ok
        } catch (e: Exception) { state = L2.Empty }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "角色管理", onBack = onBack)
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("👤", "暂无自定义角色")
            L2.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.optLong("id", 0) }) { r ->
                    KbCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(r.optString("name").ifBlank { "角色" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (r.optString("description").isNotEmpty()) {
                                Text(r.optString("description"), fontSize = 12.sp, color = KbTextDim, maxLines = 2)
                            }
                            val perms = r.optString("permissions").split(',').filter { it.isNotBlank() }
                            if (perms.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${perms.size} 项权限：${perms.take(5).joinToString("、")}${if (perms.size > 5) "…" else ""}",
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

// ============ 内容管理（对标 MobileAdminDocs）============

@Composable
fun AdminDocsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    var keyword by remember { mutableStateOf("") }
    var delTarget by remember { mutableStateOf<JSONObject?>(null) }
    fun load() {
        state = L2.Loading
        scope.launch {
            try {
                items = KbApi.adminDocs(keyword = keyword).objects()
                state = if (items.isEmpty()) L2.Empty else L2.Ok
            } catch (e: Exception) { state = L2.Empty }
        }
    }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "帖子管理", onBack = onBack)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = { Text("搜索标题", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { load() }) { Text("搜索", fontSize = 13.sp) }
        }
        Spacer(Modifier.height(8.dp))
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("📄", if (keyword.isBlank()) "暂无内容" else "没有匹配的文章")
            L2.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.optLong("id", 0) }) { d ->
                    val status = d.optString("status")
                    val blocked = status == "BLOCKED"
                    KbCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    d.optString("title").ifBlank { "未命名" },
                                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f), maxLines = 2
                                )
                                Text(
                                    statusCn(status),
                                    fontSize = 11.sp,
                                    color = when (status) {
                                        "PUBLISHED" -> KbGreen
                                        "PENDING" -> KbWarn
                                        "BLOCKED" -> KbRed
                                        else -> KbTextDim
                                    }
                                )
                            }
                            Text(
                                listOfNotNull(
                                    d.optString("ownerName").takeIf { it.isNotEmpty() },
                                    formatDateTime(d.optString("createdAt")).takeIf { it.isNotEmpty() },
                                    "${d.optInt("views")} 阅"
                                ).joinToString(" · "),
                                fontSize = 11.sp, color = KbTextDim
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching { KbApi.adminSetDocBlocked(d.optLong("id", 0), !blocked) }
                                        load()
                                    }
                                }) { Text(if (blocked) "解封" else "封禁", fontSize = 12.sp) }
                                TextButton(onClick = { delTarget = d }) { Text("删除", fontSize = 12.sp, color = KbRed) }
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
            title = { Text("删除内容") },
            text = { Text("「${d.optString("title").ifBlank { "未命名" }}」将移入回收站，可在回收站恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { KbApi.adminDeleteDoc(d.optLong("id", 0)) }
                        items = items.filter { it.optLong("id", 0) != d.optLong("id", 0) }
                        delTarget = null
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

// ============ 发现文章（对标 MobileDiscover：文章列表）============

@Composable
fun DiscoverScreen(onBack: () -> Unit, openPost: (Long, String) -> Unit) {
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    LaunchedEffect(Unit) {
        try {
            items = simpleList(KbApi.articles())
            state = if (items.isEmpty()) L2.Empty else L2.Ok
        } catch (e: Exception) { state = L2.Empty }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "发现文章", onBack = onBack)
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("🧭", "暂无文章")
            L2.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.optLong("id", 0) }) { d ->
                    KbCard(Modifier.fillMaxWidth().clickable {
                        openPost(d.optLong("id", 0), d.optString("title"))
                    }) {
                        Column(Modifier.padding(14.dp)) {
                            Text(d.optString("title").ifBlank { "未命名" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOfNotNull(
                                    d.optString("ownerName").takeIf { it.isNotEmpty() },
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

// ============ 统计页（对标 MobileStats：概览 + 存储）============

@Composable
fun StatsScreen(onBack: () -> Unit) {
    var ov by remember { mutableStateOf<JSONObject?>(null) }
    var usedBytes by remember { mutableLongStateOf(0L) }
    var quotaBytes by remember { mutableLongStateOf(0L) }
    var state by remember { mutableStateOf(L2.Loading) }
    LaunchedEffect(Unit) {
        try {
            ov = KbApi.overview()
            runCatching {
                val s = KbApi.storage()
                usedBytes = s.optLong("used", s.optLong("usedBytes", 0))
                quotaBytes = s.optLong("quota", s.optLong("quotaBytes", 0))
            }
            state = L2.Ok
        } catch (e: Exception) { state = L2.Empty }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "我的数据", onBack = onBack)
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("📊", "暂无统计数据")
            L2.Ok -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                KbCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${ov?.optLong("totalDocs", 0) ?: 0}", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                            Text("文档总数", fontSize = 11.sp, color = KbTextDim)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${ov?.optLong("weekNew", 0) ?: 0}", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                            Text("本周新增", fontSize = 11.sp, color = KbTextDim)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${ov?.optLong("members", 0) ?: 0}", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                            Text("成员", fontSize = 11.sp, color = KbTextDim)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (quotaBytes > 0) {
                    KbCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("个人存储", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            val pct = (usedBytes * 100 / quotaBytes).toInt().coerceIn(0, 100)
                            Box(
                                Modifier.fillMaxWidth().height(8.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(fraction = pct / 100f).height(8.dp)
                                        .background(
                                            if (pct > 85) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(4.dp)
                                        )
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("已用 $pct%（${usedBytes / 1048576}MB / ${quotaBytes / 1048576}MB）",
                                fontSize = 11.sp, color = KbTextDim)
                        }
                    }
                }
            }
        }
    }
}
