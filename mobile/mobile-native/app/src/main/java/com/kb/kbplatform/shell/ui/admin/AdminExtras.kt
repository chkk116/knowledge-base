/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.kb.kbplatform.shell.TokenStore
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.KbButton
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.KbChip
import com.kb.kbplatform.shell.ui.common.KbEmpty
import com.kb.kbplatform.shell.ui.common.KbLoading
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.formatDateTime
import com.kb.kbplatform.shell.ui.misc.L2
import com.kb.kbplatform.shell.ui.theme.KbBlue
import com.kb.kbplatform.shell.ui.theme.KbGreen
import com.kb.kbplatform.shell.ui.theme.KbRed
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import com.kb.kbplatform.shell.ui.theme.KbWarn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 管理端扩展屏（原生）：运营看板 / 审计与登录日志 / 举报处理 / 违禁词 / 回收站 / 分类标签。
 *
 * 与桌面端 AdminInsightPanel、AdminReportPanel、AdminWordPanel、AdminRecyclePanel、
 * AdminTaxonomyPanel 对齐；表单类重操作（批量导入等）仍留给 Web 后台。
 */

// ============ 运营看板 / 审计 / 登录日志 ============

@Composable
fun AdminInsightScreen(onBack: () -> Unit) {
    val isAdmin = TokenStore.info.value?.optString("role") == "ADMIN"
    val canStats = isAdmin || TokenStore.hasPerm("STATS_VIEW")
    // 没统计权限时不能默认落在看板页（数据仍会被请求并展示）
    var tab by remember { mutableIntStateOf(if (canStats) 0 else if (isAdmin || TokenStore.hasPerm("AUDIT_VIEW")) 1 else 2) }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "运营看板", onBack = onBack)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (canStats) KbChip("数据看板", selected = tab == 0, onClick = { tab = 0 })
            if (isAdmin || TokenStore.hasPerm("AUDIT_VIEW")) {
                KbChip("审计日志", selected = tab == 1, onClick = { tab = 1 })
            }
            if (isAdmin || TokenStore.hasPerm("LOGIN_LOG_VIEW")) {
                KbChip("登录日志", selected = tab == 2, onClick = { tab = 2 })
            }
        }
        Spacer(Modifier.height(10.dp))
        when (tab) {
            0 -> if (canStats) InsightDashboardTab() else KbEmpty("🔒", "没有看板权限")
            1 -> if (isAdmin || TokenStore.hasPerm("AUDIT_VIEW")) InsightAuditTab() else KbEmpty("🔒", "没有审计权限")
            else -> if (isAdmin || TokenStore.hasPerm("LOGIN_LOG_VIEW")) InsightLoginTab() else KbEmpty("🔒", "没有日志权限")
        }
    }
}

@Composable
private fun InsightDashboardTab() {
    var stats by remember { mutableStateOf<JSONObject?>(null) }
    var state by remember { mutableStateOf(L2.Loading) }
    LaunchedEffect(Unit) {
        try {
            stats = KbApi.adminStats()
            state = L2.Ok
        } catch (e: Exception) {
            state = L2.Empty
        }
    }
    when (state) {
        L2.Loading -> KbLoading()
        L2.Empty -> KbEmpty("📊", "看板数据加载失败")
        L2.Ok -> {
            val s = stats ?: JSONObject()
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { StatGroup("内容", listOf(
                    "文章总数" to s.optInt("docTotal"),
                    "已发布" to s.optInt("docPublished"),
                    "待审核" to s.optInt("docPending"),
                    "草稿" to s.optInt("docDraft"),
                    "已封禁" to s.optInt("docBlocked"),
                    "总浏览" to s.optInt("viewsTotal"),
                    "今日新增" to s.optInt("docToday"),
                    "昨日新增" to s.optInt("docYesterday"),
                )) }
                item { StatGroup("用户", listOf(
                    "用户总数" to s.optInt("userTotal"),
                    "管理员" to s.optInt("userAdmin"),
                    "已停用" to s.optInt("userBlocked"),
                    "今日注册" to s.optInt("userToday"),
                    "昨日注册" to s.optInt("userYesterday"),
                )) }
                item { StatGroup("互动", listOf(
                    "评论" to s.optInt("commentTotal"),
                    "点赞" to s.optInt("likeTotal"),
                    "收藏" to s.optInt("favTotal"),
                    "通知" to s.optInt("notiTotal"),
                    "今日评论" to s.optInt("commentToday"),
                    "昨日评论" to s.optInt("commentYesterday"),
                )) }
                item {
                    TrendCard(
                        title = "近 7 天趋势",
                        labels = s.optJSONArray("trendLabels"),
                        series = listOf(
                            "发文" to s.optJSONArray("docTrend7"),
                            "注册" to s.optJSONArray("userTrend7"),
                            "评论" to s.optJSONArray("commentTrend7"),
                        )
                    )
                }
                val topDocs = s.optJSONArray("topDocs")?.objects().orEmpty()
                if (topDocs.isNotEmpty()) {
                    item {
                        KbCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("热门文章", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(8.dp))
                                topDocs.forEachIndexed { i, d ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Text("${i + 1}.", fontSize = 12.sp, color = KbTextDim, modifier = Modifier.width(20.dp))
                                        Text(
                                            d.optString("title").ifBlank { "未命名" },
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text("${d.optInt("views")} 阅", fontSize = 11.sp, color = KbTextDim)
                                    }
                                }
                            }
                        }
                    }
                }
                val acts = s.optJSONArray("activities")?.objects().orEmpty()
                if (acts.isNotEmpty()) {
                    item {
                        KbCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("最近动态", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(8.dp))
                                acts.forEach { a ->
                                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                        Text(
                                            (if (a.optString("type") == "comment") "评论 · " else "发文 · ") +
                                                a.optString("text"),
                                            fontSize = 12.sp, maxLines = 2
                                        )
                                        Text(
                                            listOf(a.optString("author"), a.optString("time"))
                                                .filter { it.isNotBlank() }.joinToString(" · "),
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

@Composable
private fun StatGroup(title: String, cells: List<Pair<String, Int>>) {
    KbCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            cells.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { (label, value) ->
                        Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
                            Text("$value", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = KbBlue)
                            Text(label, fontSize = 11.sp, color = KbTextDim)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TrendCard(title: String, labels: JSONArray?, series: List<Pair<String, JSONArray?>>) {
    val colors = listOf(KbBlue, KbGreen, KbWarn)
    val all = series.mapNotNull { it.second }.flatMap { arr -> (0 until arr.length()).map { arr.optInt(it) } }
    val max = (all.maxOrNull() ?: 0).coerceAtLeast(1)
    KbCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                series.forEachIndexed { i, (name, arr) ->
                    val values = (0 until (arr?.length() ?: 0)).map { i -> arr?.optInt(i) ?: 0 }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(
                            Modifier.height(56.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            values.forEach { v ->
                                Box(
                                    Modifier
                                        .width(5.dp)
                                        .height((6 + 44 * v / max).dp)
                                        .background(colors[i % colors.size].copy(alpha = 0.85f), RoundedCornerShape(2.dp))
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(name, fontSize = 11.sp, color = KbTextDim)
                        Text(values.sum().toString(), fontSize = 11.sp, color = colors[i % colors.size])
                    }
                }
            }
            if (labels != null && labels.length() > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    (0 until labels.length()).joinToString("  ") { labels.optString(it) },
                    fontSize = 10.sp, color = KbTextDim
                )
            }
        }
    }
}

@Composable
private fun InsightAuditTab() {
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    LaunchedEffect(Unit) {
        try {
            items = KbApi.adminAudit().objects()
            state = if (items.isEmpty()) L2.Empty else L2.Ok
        } catch (e: Exception) {
            state = L2.Empty
        }
    }
    when (state) {
        L2.Loading -> KbLoading()
        L2.Empty -> KbEmpty("🧾", "暂无审计记录")
        L2.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(items, key = { i, it -> it.optLong("id", -1L).let { id -> if (id == -1L) "audit-$i" else id } }) { i, a ->
                KbCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            a.optString("action").ifBlank { "操作" },
                            fontSize = 13.sp, fontWeight = FontWeight.Medium
                        )
                        val sub = listOfNotNull(
                            a.optString("actorName").takeIf { it.isNotBlank() },
                            a.optString("targetType").takeIf { it.isNotBlank() },
                            a.optString("targetName").takeIf { it.isNotBlank() },
                            a.optString("ip").takeIf { it.isNotBlank() },
                            formatDateTime(a.optString("createdAt")).takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                        if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, color = KbTextDim)
                        val detail = a.optString("detail")
                        if (detail.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            Text(detail, fontSize = 11.sp, color = KbTextDim, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightLoginTab() {
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    LaunchedEffect(Unit) {
        try {
            items = KbApi.adminLoginLogs().objects()
            state = if (items.isEmpty()) L2.Empty else L2.Ok
        } catch (e: Exception) {
            state = L2.Empty
        }
    }
    when (state) {
        L2.Loading -> KbLoading()
        L2.Empty -> KbEmpty("🔐", "暂无登录记录")
        L2.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(items, key = { i, it -> it.optLong("id", -1L).let { id -> if (id == -1L) "login-$i" else id } }) { i, g ->
                val ok = g.optBoolean("success", true)
                KbCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.optString("username").ifBlank { "未知账号" }, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            val sub = listOfNotNull(
                                g.optString("ip").takeIf { it.isNotBlank() },
                                formatDateTime(g.optString("createdAt")).takeIf { it.isNotBlank() },
                            ).joinToString(" · ")
                            if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, color = KbTextDim)
                            val reason = g.optString("reason")
                            if (!ok && reason.isNotBlank()) Text(reason, fontSize = 11.sp, color = KbRed, maxLines = 1)
                        }
                        Text(
                            if (ok) "成功" else "失败",
                            fontSize = 11.sp,
                            color = if (ok) KbGreen else KbRed
                        )
                    }
                }
            }
        }
    }
}

// ============ 举报处理 ============

@Composable
fun AdminReportScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("PENDING") }
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var pending by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(L2.Loading) }
    var target by remember { mutableStateOf<JSONObject?>(null) }
    var note by remember { mutableStateOf("") }
    var takeDown by remember { mutableStateOf(false) }
    fun load() {
        state = L2.Loading
        scope.launch {
            try {
                val o = KbApi.adminReports(if (filter == "ALL") null else filter)
                items = o.optJSONArray("items")?.objects().orEmpty()
                pending = o.optInt("pending")
                state = if (items.isEmpty()) L2.Empty else L2.Ok
            } catch (e: Exception) {
                state = L2.Empty
            }
        }
    }
    LaunchedEffect(filter) { load() }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "举报处理", onBack = onBack)
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KbChip("待处理${if (pending > 0) " $pending" else ""}", selected = filter == "PENDING", onClick = {
                filter = "PENDING"
            })
            KbChip("已处理", selected = filter == "HANDLED", onClick = { filter = "HANDLED" })
            KbChip("已驳回", selected = filter == "REJECTED", onClick = { filter = "REJECTED" })
            KbChip("全部", selected = filter == "ALL", onClick = { filter = "ALL" })
        }
        Spacer(Modifier.height(10.dp))
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("🚩", "暂无举报")
            L2.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(items, key = { i, it -> it.optLong("id", -1L).let { id -> if (id == -1L) "report-$i" else id } }) { i, r ->
                    val isPending = r.optString("status") == "PENDING"
                    KbCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${targetCn(r.optString("targetType"))} #${r.optLong("targetId")}",
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    when (r.optString("status")) {
                                        "PENDING" -> "待处理"
                                        "HANDLED" -> "已处理"
                                        "REJECTED" -> "已驳回"
                                        else -> r.optString("status")
                                    },
                                    fontSize = 11.sp,
                                    color = when (r.optString("status")) {
                                        "PENDING" -> KbWarn
                                        "HANDLED" -> KbGreen
                                        else -> KbTextDim
                                    }
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(r.optString("reason").ifBlank { "未填写原因" }, fontSize = 12.sp)
                            val detail = r.optString("detail")
                            if (detail.isNotBlank()) {
                                Text(detail, fontSize = 11.sp, color = KbTextDim, maxLines = 2)
                            }
                            val t = formatDateTime(r.optString("createdAt"))
                            if (t.isNotBlank()) Text(t, fontSize = 11.sp, color = KbTextDim)
                            if (isPending) {
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    KbButton("处理", small = true, onClick = {
                                        target = r; note = ""; takeDown = false
                                    })
                                    KbButton("驳回", small = true, ghost = true, onClick = {
                                        scope.launch {
                                            runCatching { KbApi.adminHandleReport(r.optLong("id", 0), "REJECTED", "不予处理") }
                                            load()
                                        }
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    target?.let { r ->
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text("处理举报") },
            text = {
                Column {
                    Text("${targetCn(r.optString("targetType"))} #${r.optLong("targetId")}", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("处理备注") },
                        singleLine = false,
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (r.optString("targetType") == "POST") {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("同时下架该文章", fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Switch(checked = takeDown, onCheckedChange = { takeDown = it })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = runCatching {
                            KbApi.adminHandleReport(
                                r.optLong("id", 0), "HANDLED", note,
                                if (takeDown) "DELETE_CONTENT" else "NONE"
                            )
                        }.isSuccess
                        target = null
                        if (ok) load()
                    }
                }) { Text("标记已处理") }
            },
            dismissButton = { TextButton(onClick = { target = null }) { Text("取消") } }
        )
    }
}

private fun targetCn(t: String) = when (t) {
    "DOC", "POST" -> "文章"
    "COMMENT" -> "评论"
    "USER" -> "用户"
    "DM" -> "私信"
    "FEEDBACK" -> "反馈"
    else -> t.ifBlank { "对象" }
}

// ============ 违禁词库 ============

@Composable
fun AdminWordScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var libs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var curLib by remember { mutableStateOf<Long?>(null) }
    var words by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    var showAdd by remember { mutableStateOf(false) }
    var addText by remember { mutableStateOf("") }
    var delOne by remember { mutableStateOf<JSONObject?>(null) }
    var showNewLib by remember { mutableStateOf(false) }
    var newLibName by remember { mutableStateOf("") }

    // 显式传 libraryId 并在回调时丢弃过期响应，避免快速切词库时旧结果覆盖新列表
    fun loadWords(libraryId: Long?) {
        scope.launch {
            try {
                val loaded = KbApi.bannedWords(libraryId).objects()
                if (libraryId != curLib) return@launch
                words = loaded
                state = if (words.isEmpty()) L2.Empty else L2.Ok
            } catch (e: Exception) {
                if (libraryId == curLib) state = L2.Empty
            }
        }
    }
    fun loadLibs() {
        scope.launch {
            try {
                libs = KbApi.bannedLibraries().objects()
                if (curLib == null) curLib = libs.firstOrNull()?.optLong("id")?.takeIf { it > 0 }
                // 词表由下方 LaunchedEffect(curLib) 负责加载，避免首屏重复请求
            } catch (e: Exception) {
                state = L2.Empty
            }
        }
    }
    LaunchedEffect(Unit) { loadLibs() }
    LaunchedEffect(curLib) { if (libs.isNotEmpty()) loadWords(curLib) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "违禁词库", onBack = onBack)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            libs.forEach { l ->
                val id = l.optLong("id")
                KbChip(
                    l.optString("name").ifBlank { "词库" } + " " + l.optInt("wordCount"),
                    selected = curLib == id,
                    onClick = { curLib = id }
                )
            }
            KbChip("＋ 词库", gray = true, onClick = { showNewLib = true })
        }
        // 当前词库的启停开关（内置词库也允许停用，效果等同整库不参与校验）
        libs.firstOrNull { it.optLong("id") == curLib }?.let { l ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    l.optString("description").ifBlank { "启用后参与内容校验" },
                    fontSize = 11.sp, color = KbTextDim, modifier = Modifier.weight(1f), maxLines = 2
                )
                Text(
                    if (l.optBoolean("enabled", true)) "已启用" else "已停用",
                    fontSize = 11.sp,
                    color = if (l.optBoolean("enabled", true)) KbGreen else KbTextDim
                )
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = {
                    scope.launch {
                        runCatching {
                            KbApi.setBannedLibraryEnabled(l.optLong("id"), !l.optBoolean("enabled", true))
                        }
                        loadLibs()
                    }
                }) { Text(if (l.optBoolean("enabled", true)) "停用" else "启用", fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(6.dp))
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("🔤", "该词库还没有词条")
            L2.Ok -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(words, key = { i, it -> it.optLong("id", -1L).let { id -> if (id == -1L) "word-$i" else id } }) { i, w ->
                    KbCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(w.optString("word"), fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { delOne = w }) {
                                Text("删除", fontSize = 12.sp, color = KbRed)
                            }
                        }
                    }
                }
            }
        }
        if (curLib != null) {
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                KbButton(
                    "批量加词",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { addText = ""; showAdd = true }
                )
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加违禁词") },
            text = {
                Column {
                    Text("每行一个词，或用逗号/空格分隔，已存在的自动跳过", fontSize = 11.sp, color = KbTextDim)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addText,
                        onValueChange = { addText = it },
                        label = { Text("词条") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val list = addText.split('\n', ',', '，', ' ', '\t', '、')
                        .map { it.trim() }.filter { it.isNotBlank() }.distinct()
                    if (list.isEmpty()) return@TextButton
                    scope.launch {
                        runCatching { KbApi.addBannedWords(curLib ?: 0L, list) }
                        showAdd = false
                        loadLibs()
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } }
        )
    }
    if (showNewLib) {
        AlertDialog(
            onDismissRequest = { showNewLib = false },
            title = { Text("新建词库") },
            text = {
                OutlinedTextField(
                    value = newLibName,
                    onValueChange = { newLibName = it },
                    label = { Text("词库名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newLibName.isBlank()) return@TextButton
                    scope.launch {
                        runCatching { KbApi.createBannedLibrary(newLibName.trim(), "") }
                        newLibName = ""
                        showNewLib = false
                        loadLibs()
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewLib = false }) { Text("取消") } }
        )
    }
    delOne?.let { w ->
        AlertDialog(
            onDismissRequest = { delOne = null },
            title = { Text("删除词条") },
            text = { Text("确定删除「${w.optString("word")}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (runCatching { KbApi.deleteBannedWord(w.optLong("id", 0)) }.isSuccess) {
                            words = words.filter { it.optLong("id", 0) != w.optLong("id", 0) }
                            delOne = null
                            loadLibs()   // 词库计数跟着刷新
                        } else {
                            delOne = null
                        }
                    }
                }) { Text("删除", color = KbRed) }
            },
            dismissButton = { TextButton(onClick = { delOne = null }) { Text("取消") } }
        )
    }
}

// ============ 回收站 ============

@Composable
fun AdminRecycleScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    var purging by remember { mutableStateOf<JSONObject?>(null) }
    fun load() {
        state = L2.Loading
        scope.launch {
            try {
                items = KbApi.adminRecycleDocs().objects()
                state = if (items.isEmpty()) L2.Empty else L2.Ok
            } catch (e: Exception) {
                state = L2.Empty
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "文章回收站", onBack = onBack)
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("🗑️", "回收站是空的")
            L2.Ok -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(items, key = { i, it -> it.optLong("id", -1L).let { id -> if (id == -1L) "recycle-$i" else id } }) { i, d ->
                    KbCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(d.optString("title").ifBlank { "未命名" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            val sub = listOfNotNull(
                                d.optString("ownerName").takeIf { it.isNotBlank() },
                                d.optString("updatedAt").takeIf { it.isNotBlank() }?.let { "更新于 " + formatDateTime(it) },
                            ).joinToString(" · ")
                            if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, color = KbTextDim)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                KbButton("恢复", small = true, onClick = {
                                    scope.launch {
                                        runCatching { KbApi.adminRestoreDoc(d.optLong("id", 0)) }
                                        load()
                                    }
                                })
                                KbButton("彻底删除", small = true, ghost = true, onClick = { purging = d })
                            }
                        }
                    }
                }
            }
        }
    }
    purging?.let { d ->
        AlertDialog(
            onDismissRequest = { purging = null },
            title = { Text("彻底删除") },
            text = { Text("「${d.optString("title").ifBlank { "未命名" }}」将永久删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { KbApi.adminPurgeDoc(d.optLong("id", 0)) }
                        purging = null
                        load()
                    }
                }) { Text("永久删除", color = KbRed) }
            },
            dismissButton = { TextButton(onClick = { purging = null }) { Text("取消") } }
        )
    }
}

// ============ 分类与标签 ============

@Composable
fun AdminTaxonomyScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "分类与标签", onBack = onBack)
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KbChip("分类", selected = tab == 0, onClick = { tab = 0 })
            KbChip("标签", selected = tab == 1, onClick = { tab = 1 })
        }
        Spacer(Modifier.height(10.dp))
        if (tab == 0) CategoryTab() else TagTab()
    }
}

@Composable
private fun CategoryTab() {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Pair<JSONObject, Int>>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf<JSONObject?>(null) }
    var delOne by remember { mutableStateOf<JSONObject?>(null) }
    fun load() {
        state = L2.Loading
        scope.launch {
            try {
                val roots = KbApi.categories().objects()
                val flat = mutableListOf<Pair<JSONObject, Int>>()
                fun walk(list: List<JSONObject>, depth: Int) {
                    list.forEach { c ->
                        flat += c to depth
                        walk(c.optJSONArray("children")?.objects().orEmpty(), depth + 1)
                    }
                }
                walk(roots, 0)
                items = flat
                state = if (flat.isEmpty()) L2.Empty else L2.Ok
            } catch (e: Exception) {
                state = L2.Empty
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.fillMaxSize()) {
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("🗂️", "还没有分类")
            L2.Ok -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(items, key = { i, it -> it.first.optLong("id", -1L).let { id -> if (id == -1L) "cat-$i" else id } }) { i, (c, depth) ->
                    KbCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(start = (12 + depth * 16).dp, top = 10.dp, bottom = 10.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                (if (depth > 0) "└ " else "") + c.optString("name").ifBlank { "未命名" },
                                fontSize = 13.sp, modifier = Modifier.weight(1f)
                            )
                            if (depth == 0) {
                                TextButton(onClick = { parent = c; name = ""; showAdd = true }) {
                                    Text("加子类", fontSize = 11.sp, color = KbBlue)
                                }
                            }
                            TextButton(onClick = { delOne = c }) {
                                Text("删除", fontSize = 12.sp, color = KbRed)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            KbButton("新建顶级分类", modifier = Modifier.fillMaxWidth(), onClick = {
                parent = null; name = ""; showAdd = true
            })
        }
    }
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(if (parent == null) "新建分类" else "在「${parent?.optString("name")}」下新建") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分类名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isBlank()) return@TextButton
                    scope.launch {
                        runCatching { KbApi.createCategory(name.trim(), parent?.optLong("id")) }
                        showAdd = false
                        load()
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } }
        )
    }
    delOne?.let { c ->
        AlertDialog(
            onDismissRequest = { delOne = null },
            title = { Text("删除分类") },
            text = { Text("删除「${c.optString("name")}」？有子分类或文章仍在用时后端会拒绝。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { KbApi.deleteCategory(c.optLong("id", 0)) }
                        delOne = null
                        load()
                    }
                }) { Text("删除", color = KbRed) }
            },
            dismissButton = { TextButton(onClick = { delOne = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun TagTab() {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var state by remember { mutableStateOf(L2.Loading) }
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    fun load() {
        state = L2.Loading
        scope.launch {
            try {
                items = KbApi.tags().objects()
                state = if (items.isEmpty()) L2.Empty else L2.Ok
            } catch (e: Exception) {
                state = L2.Empty
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.fillMaxSize()) {
        when (state) {
            L2.Loading -> KbLoading()
            L2.Empty -> KbEmpty("🏷️", "还没有标签")
            L2.Ok -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(items, key = { i, it -> it.optLong("id", -1L).let { id -> if (id == -1L) "tag-$i" else id } }) { i, t ->
                    KbCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("#" + t.optString("name").ifBlank { "标签" }, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text("${t.optInt("count")} 篇", fontSize = 11.sp, color = KbTextDim)
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { KbApi.deleteTag(t.optLong("id", 0)) }
                                    load()
                                }
                            }) { Text("删除", fontSize = 12.sp, color = KbRed) }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            KbButton("新建标签", modifier = Modifier.fillMaxWidth(), onClick = { name = ""; showAdd = true })
        }
    }
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("新建标签") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("标签名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isBlank()) return@TextButton
                    scope.launch {
                        runCatching { KbApi.createTag(name.trim()) }
                        showAdd = false
                        load()
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } }
        )
    }
}