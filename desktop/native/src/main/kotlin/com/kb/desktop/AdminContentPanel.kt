package com.kb.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 管理模块（2026-11-16）：看板 / 审核 / 用户 / 部门 / 公告 / 审计 / 登录日志
//   权限全部由后端判定（ADMIN 或细粒度码），403 文案原样透出；
//   只做日常运维能闭环的轻操作，重配置（外观/水印/SSO/AI 模型…）仍回 Web 端。
//   列表均取第一页 50 条（不做翻页），避免客户端变成半个后台。




// ---------------- 公告 ----------------

@Composable
internal fun AnnouncementsTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<AdminAnnouncement>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var pinned by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick) {
        loading = true
        err = null
        try {
            items = api.adminAnnouncements(session.token)
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("公告", "${items.size} 条", onRefresh = { tick++ })
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
            AdminButton(if (creating) "收起新建" else "新建公告", enabled = true) { creating = !creating }
        }
        if (creating) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("公告标题", fontSize = 12.sp, color = KbTextSub) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KbPrimary, unfocusedBorderColor = KbBorder),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    placeholder = { Text("公告内容（纯文本 / 简单 HTML）", fontSize = 12.sp, color = KbTextSub) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KbPrimary, unfocusedBorderColor = KbBorder),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (pinned) "☑ 置顶" else "☐ 置顶",
                        fontSize = 11.sp,
                        color = if (pinned) KbPrimary else KbTextSub,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, if (pinned) KbPrimary.copy(alpha = 0.5f) else KbBorder, RoundedCornerShape(6.dp))
                            .clickable { pinned = !pinned }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    AdminButton(if (busy) "发布中…" else "发布", enabled = !busy) {
                        val t = title.trim()
                        if (t.isEmpty()) { toast = "请填写标题"; return@AdminButton }
                        scope.launch {
                            busy = true
                            try {
                                api.createAnnouncement(session.token, t, body.trim(), pinned)
                                title = ""; body = ""; pinned = false; creating = false
                                toast = "已发布"
                                tick++
                            } catch (e: Exception) {
                                toast = e.message ?: "发布失败"
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = KbBorder)
        Box(Modifier.weight(1f)) {
            when {
                loading -> AdminHint("加载中…")
                err != null -> AdminError(err ?: "", { tick++ })
                items.isEmpty() -> AdminHint("还没有公告")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(items, key = { it.id }) { a ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (a.pinned) {
                                        AdminChip("置顶", Color(0xFFFEF3C7), Color(0xFFB45309))
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(
                                        a.title.ifBlank { "（无标题）" },
                                        fontSize = 13.sp,
                                        color = if (a.enabled) KbTextMain else Color(0xFF9CA3AF),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!a.enabled) {
                                        Spacer(Modifier.width(6.dp))
                                        AdminChip("已停用", Color(0xFFF3F4F6), KbTextSub)
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    a.content.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim(),
                                    fontSize = 10.5.sp,
                                    color = KbTextSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AdminButton(if (a.pinned) "取消置顶" else "置顶", enabled = !busy) {
                                    scope.launch {
                                        try { api.toggleAnnouncementPin(session.token, a.id); tick++ } catch (e: Exception) { toast = e.message ?: "失败" }
                                    }
                                }
                                AdminButton(if (a.enabled) "停用" else "启用", danger = a.enabled, enabled = !busy) {
                                    scope.launch {
                                        try { api.toggleAnnouncementEnabled(session.token, a.id); tick++ } catch (e: Exception) { toast = e.message ?: "失败" }
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

// ---------------- 违禁词 ----------------

@Composable
internal fun BannedWordsTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var libs by remember { mutableStateOf<List<BannedLibItem>>(emptyList()) }
    var libId by remember { mutableStateOf<Long?>(null) }
    var words by remember { mutableStateOf<List<BannedWordItem>>(emptyList()) }
    var q by remember { mutableStateOf("") }
    var newLib by remember { mutableStateOf("") }
    var newWords by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<Long?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick) {
        loading = true
        err = null
        try {
            libs = api.bannedLibraries(session.token)
            if (libId == null || libs.none { it.id == libId }) libId = libs.firstOrNull()?.id
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(libId, tick) {
        val id = libId ?: run { words = emptyList(); return@LaunchedEffect }
        words = runCatching { api.bannedWords(session.token, id) }.getOrDefault(emptyList())
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    val shown = remember(words, q) {
        val kw = q.trim()
        if (kw.isEmpty()) words else words.filter { it.word.contains(kw, ignoreCase = true) }
    }

    Row(Modifier.fillMaxSize()) {
        // 左：词库
        Column(Modifier.width(196.dp).fillMaxHeight().background(AdminRailBg)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("词库", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { tick++ }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("刷新", fontSize = 11.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    if (newLib.isEmpty()) Text("新词库名称", fontSize = 11.5.sp, color = KbTextSub, modifier = Modifier.padding(start = 9.dp))
                    OutlinedTextField(
                        value = newLib,
                        onValueChange = { newLib = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KbPrimary, unfocusedBorderColor = KbBorder),
                    )
                }
                Spacer(Modifier.width(6.dp))
                AdminButton("新建", enabled = !busy) {
                    val n = newLib.trim()
                    if (n.isEmpty()) { toast = "请填写词库名称"; return@AdminButton }
                    scope.launch {
                        busy = true
                        try {
                            api.createBannedLibrary(session.token, n, "")
                            newLib = ""
                            toast = "已新建词库"
                            tick++
                        } catch (e: Exception) {
                            toast = e.message ?: "新建失败"
                        } finally { busy = false }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = KbBorder)
            Box(Modifier.weight(1f)) {
                when {
                    loading -> AdminHint("加载中…")
                    err != null -> AdminError(err ?: "", { tick++ })
                    libs.isEmpty() -> AdminHint("还没有词库")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(libs, key = { it.id }) { l ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { libId = l.id }
                                    .background(if (l.id == libId) KbSidebarSelected else Color.Transparent)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        l.name.ifBlank { "未命名" },
                                        fontSize = 12.5.sp,
                                        fontWeight = if (l.id == libId) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (l.enabled) KbTextMain else Color(0xFF9CA3AF),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (l.builtin) {
                                        Spacer(Modifier.width(6.dp))
                                        AdminChip("内置", Color(0xFFF1F5F9), KbTextSub)
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (l.enabled) "已启用" else "已停用（选择加载时不生效）",
                                    fontSize = 10.sp,
                                    color = if (l.enabled) Color(0xFF047857) else KbTextSub,
                                )
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(KbBorder))

        // 右：词条
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val lib = libs.firstOrNull { it.id == libId }
            AdminHeader(
                "词条",
                "${shown.size} / ${words.size}" + (lib?.let { " · ${it.name}" } ?: ""),
                onRefresh = { tick++ },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchBox(q, { q = it }, Modifier.weight(1f), "搜索词条")
                Spacer(Modifier.width(6.dp))
                AdminButton(if (lib?.enabled == true) "停用此库" else "启用此库", enabled = !busy && lib != null) {
                    val l = lib ?: return@AdminButton
                    scope.launch {
                        busy = true
                        try {
                            api.setBannedLibraryEnabled(session.token, l.id, !l.enabled)
                            toast = if (!l.enabled) "已启用 ${l.name}" else "已停用 ${l.name}"
                            tick++
                        } catch (e: Exception) {
                            toast = e.message ?: "操作失败"
                        } finally { busy = false }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newWords,
                    onValueChange = { newWords = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("新增词条（多个用逗号/换行分隔）", fontSize = 11.5.sp, color = KbTextSub) },
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KbPrimary, unfocusedBorderColor = KbBorder),
                )
                Spacer(Modifier.width(6.dp))
                AdminButton("添加", enabled = !busy && libId != null) {
                    val id = libId ?: return@AdminButton
                    val list = newWords.split(Regex("[,，、;；\\n\\r]+")).map { it.trim() }.filter { it.isNotEmpty() }
                    if (list.isEmpty()) { toast = "请输入词条"; return@AdminButton }
                    scope.launch {
                        busy = true
                        try {
                            api.addBannedWords(session.token, id, list)
                            newWords = ""
                            toast = "已添加 ${list.size} 个词条"
                            tick++
                        } catch (e: Exception) {
                            toast = e.message ?: "添加失败"
                        } finally { busy = false }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = KbBorder)
            Box(Modifier.weight(1f)) {
                when {
                    libId == null -> AdminHint("先在左侧选择词库")
                    shown.isEmpty() -> AdminHint(if (q.isNotBlank()) "没有匹配的词条" else "这个词库还没有词条")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.id }) { w ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(w.word, fontSize = 12.5.sp, color = KbTextMain, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (confirming == w.id) {
                                    AdminButton("确认删除", danger = true, enabled = !busy) {
                                        scope.launch {
                                            busy = true
                                            try {
                                                api.deleteBannedWord(session.token, w.id)
                                                confirming = null
                                                toast = "已删除"
                                                tick++
                                            } catch (e: Exception) {
                                                toast = e.message ?: "删除失败"
                                            } finally { busy = false }
                                        }
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    AdminButton("取消", enabled = !busy) { confirming = null }
                                } else {
                                    AdminButton("删除", danger = true, enabled = !busy) { confirming = w.id }
                                }
                            }
                            HorizontalDivider(color = Color(0xFFF1F5F9))
                        }
                    }
                }
            }
        }
    }
    AdminToast(toast)
}

// ---------------- 孤儿附件 ----------------

@Composable
internal fun OrphanFilesTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<OrphanFileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var confirming by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick) {
        loading = true
        err = null
        try { files = api.orphanFiles(session.token) } catch (e: Exception) { err = e.message ?: "加载失败" } finally { loading = false }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("孤儿附件", "${files.size} 个（最多 100）", onRefresh = { tick++ })
        Text(
            "只列出正文与头像都未引用的上传文件；删除不可恢复，逐个二次确认。",
            fontSize = 10.5.sp,
            color = Color(0xFFB45309),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
        HorizontalDivider(color = KbBorder)
        Box(Modifier.weight(1f)) {
            when {
                loading -> AdminHint("加载中…")
                err != null -> AdminError(err ?: "", { tick++ })
                files.isEmpty() -> AdminHint("没有孤儿附件")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(files, key = { it.url }) { f ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    f.url.substringAfterLast('/'),
                                    fontSize = 12.5.sp,
                                    color = KbTextMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    listOf(f.url, fmtAdminSize(f.size)).filter { it.isNotBlank() }.joinToString(" · "),
                                    fontSize = 10.sp,
                                    color = KbTextSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (confirming == f.url) {
                                AdminButton("确认删除", danger = true, enabled = !busy) {
                                    scope.launch {
                                        busy = true
                                        try {
                                            api.deleteOrphanFile(session.token, f.url)
                                            files = files.filterNot { it.url == f.url }
                                            confirming = null
                                            toast = "已删除"
                                        } catch (e: Exception) {
                                            toast = e.message ?: "删除失败"
                                        } finally { busy = false }
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                AdminButton("取消", enabled = !busy) { confirming = null }
                            } else {
                                AdminButton("删除", danger = true, enabled = !busy) { confirming = f.url }
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

private fun fmtAdminSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return ""
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
