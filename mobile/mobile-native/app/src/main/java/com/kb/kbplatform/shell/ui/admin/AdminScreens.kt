/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button

import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.kbItemEnter
import com.kb.kbplatform.shell.ui.common.kbPress
import com.kb.kbplatform.shell.ui.common.CenterHint
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.formatDateTime
import com.kb.kbplatform.shell.ui.theme.KbBlue
import com.kb.kbplatform.shell.ui.theme.KbGreen
import com.kb.kbplatform.shell.ui.theme.KbRed
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import com.kb.kbplatform.shell.ui.theme.KbWarn
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 后台管理（原生）：入口按权限码显隐，与 Web 移动壳 /manage/ 路径族对齐。
 * 用户管理(USER_MANAGE) / AI 模型管理(AI_MANAGE) / 文章审核(DOC_REVIEW) /
 * 公告管理(ANNOUNCEMENT_MANAGE)。模型的新建编辑等重表单仍在 Web 端（入口置底）。
 */

// ============ 管理首页 ============

@Composable
fun AdminHomeScreen(
    onOpen: (String) -> Unit,
    onOpenWeb: (String, String) -> Unit,
    onBack: () -> Unit
) {
    // 权限码与后端各 Controller 的 requirePerm 一一对应；ADMIN 角色额外放行仅管理员可见的模块。
    // 冷启动时 TokenStore.info 还是空的（进程内存态），这里补拉一次 me() 再渲染入口，
    // 否则重启 App 直接进管理页会看到"一个模块都没有"。
    val info by TokenStore.info.collectAsState()
    LaunchedEffect(Unit) {
        runCatching { KbApi.me() }.onSuccess { TokenStore.setInfo(it) }
    }
    val isAdmin = info?.optString("role") == "ADMIN"
    fun perm(p: String) = TokenStore.hasPerm(p)
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "后台管理", onBack = onBack)
        var enterIdx = 0
        LazyColumn(
            // 宽屏（平板/折叠屏）居中限宽，避免每张卡被拉成一条长带
            modifier = Modifier.widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "按权限显示可用模块，完整后台（表单类）在「全部功能」的 Web 页面中",
                    fontSize = 12.sp, color = KbTextDim
                )
            }
            if (perm("STATS_VIEW") || isAdmin) {
                item {
                    AdminEntry(index = enterIdx++, "运营看板", "数据统计 / 审计 / 登录日志", KbBlue,
                        onClick = { onOpen("admin-insight") })
                }
            }
            if (perm("DOC_REVIEW")) {
                item {
                    AdminEntry(index = enterIdx++, "文章审核", "待审文章通过与驳回", KbGreen, onClick = { onOpen("admin/review") })
                }
            }
            if (perm("POST_MANAGE")) {
                item {
                    AdminEntry(index = enterIdx++, "帖子管理", "全站文章封禁 / 删除", Color(0xFF8B5CF6),
                        onClick = { onOpen("admin-docs") })
                }
            }
            if (isAdmin) {
                item {
                    AdminEntry(index = enterIdx++, "举报处理", "用户举报核实与处置", KbRed, onClick = { onOpen("admin-reports") })
                }
            }
            if (isAdmin) {
                item {
                    AdminEntry(index = enterIdx++, "空间申请", "团队空间创建 / 扩容审批", Color(0xFF0EA5E9),
                        onClick = { onOpen("admin-spaces") })
                }
            }
            if (perm("BANNED_WORDS_MANAGE")) {
                item {
                    AdminEntry(index = enterIdx++, "违禁词库", "词库启停 / 批量加词", KbWarn, onClick = { onOpen("admin-words") })
                }
            }
            if (isAdmin || perm("POST_MANAGE")) {
                item {
                    AdminEntry(index = enterIdx++, "文章回收站", "恢复 / 彻底删除", Color(0xFF64748B),
                        onClick = { onOpen("admin-recycle") })
                }
            }
            if (perm("TAXONOMY_MANAGE")) {
                item {
                    AdminEntry(index = enterIdx++, "分类与标签", "分类树 / 标签维护", Color(0xFF0EA5E9),
                        onClick = { onOpen("admin-taxonomy") })
                }
            }
            if (perm("USER_MANAGE")) {
                item {
                    AdminEntry(index = enterIdx++, "用户管理", "账号 / 角色 / 启停", KbBlue, onClick = { onOpen("admin/users") })
                }
            }
            if (perm("AI_MANAGE")) {
                item {
                    AdminEntry(index = enterIdx++, "AI 模型管理", "启停 / 默认模型 / 连通测试", Color(0xFF8B5CF6),
                        onClick = { onOpen("admin/ai") })
                }
            }
            if (perm("ANNOUNCEMENT_MANAGE")) {
                item {
                    AdminEntry(index = enterIdx++, "公告管理", "发布 / 置顶 / 启停", KbWarn, onClick = { onOpen("admin/announce") })
                }
            }
            if (perm("USER_MANAGE")) {
                item {
                    AdminEntry(index = enterIdx++, "角色管理", "角色与权限分配", Color(0xFF0EA5E9), onClick = { onOpen("admin-roles") })
                }
            }
            item {
                AdminEntry(index = enterIdx++, "完整后台（Web）", "内容管理 / 检索测试 / 数据看板", Color(0xFF64748B),
                    onClick = { onOpenWeb("/m/manage/users", "后台管理") })
            }
        }
    }
}

@Composable
private fun AdminEntry(index: Int = 0, title: String, sub: String, tint: Color, onClick: () -> Unit) {
    KbCard(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .kbItemEnter(index)
            .kbPress(onClick = onClick)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .background(tint.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(12.dp).background(tint, CircleShape))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(sub, fontSize = 12.sp, color = KbTextDim)
            }
        }
    }
}

// ============ 用户管理 ============

@Composable
fun AdminUsersScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var keyword by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var roleTarget by remember { mutableStateOf<JSONObject?>(null) }
    var disableTarget by remember { mutableStateOf<JSONObject?>(null) }
    var actionMsg by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var resetTarget by remember { mutableStateOf<JSONObject?>(null) }
    var resetResult by remember { mutableStateOf("") }

    fun load(p: Int) {
        loading = true
        error = ""
        scope.launch {
            try {
                val r = KbApi.adminUsers(p, 20, keyword)
                users = r.optJSONArray("content")?.objects() ?: emptyList()
                total = r.optInt("totalElements", users.size)
                page = p
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load(0) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "用户管理", onBack = onBack) {
            TextButton(onClick = { showCreate = true }) { Text("新建", fontSize = 13.sp) }
        }
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            placeholder = { Text("搜索用户名 / 昵称") },
            singleLine = true,
            trailingIcon = {
                TextButton(onClick = { load(0) }) { Text("搜索") }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
        if (actionMsg.isNotEmpty()) {
            Text(
                actionMsg,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        if (loading) {
            CenterHint("加载中…", loading = true)
        } else if (error.isNotEmpty()) {
            CenterHint(error)
        } else {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(users, key = { _, u -> u.optLong("id", 0) }) { i, u ->
                    val id = u.optLong("id", 0)
                    val enabled = u.optBoolean("enabled", true)
                    KbCard(
                        modifier = Modifier.kbItemEnter(i),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        (u.optString("displayName").ifBlank { u.optString("username") })
                                            + "（${u.optString("username")}）",
                                        fontSize = 14.sp, fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        listOf(
                                            roleCn(u.optString("role")),
                                            if (enabled) "正常" else "已禁用",
                                            "积分 ${u.optInt("points", 0)}",
                                            formatDateTime(u.optString("createdAt"))
                                        ).joinToString(" · "),
                                        fontSize = 11.sp, color = KbTextDim
                                    )
                                }
                                Switch(checked = enabled, onCheckedChange = { on ->
                                    if (!on) {
                                        // 禁用是破坏性操作，先确认（误触一次就锁住别人账号）
                                        disableTarget = u
                                    } else {
                                        scope.launch {
                                            // 只有接口成功才改本地状态：原来 runCatching 吞掉失败后仍
                                            // 更新 UI，会出现「界面显示已启用、服务端其实没变」
                                            val ok = runCatching { KbApi.adminToggleUser(id, true) }.isSuccess
                                            if (ok) {
                                                actionMsg = ""
                                                users = users.map {
                                                    if (it.optLong("id", 0) == id) JSONObject(it.toString()).put("enabled", true) else it
                                                }
                                            } else {
                                                actionMsg = "启用失败，请重试"
                                            }
                                        }
                                    }
                                })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { roleTarget = u }, enabled = enabled) {
                                    Text("改角色", fontSize = 12.sp)
                                }
                                TextButton(onClick = { resetTarget = u }) {
                                    Text("重置密码", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (page > 0) {
                            TextButton(onClick = { load(page - 1) }) { Text("上一页") }
                        }
                        Text("共 $total 人 · 第 ${page + 1} 页", fontSize = 12.sp,
                            color = KbTextDim, modifier = Modifier.padding(vertical = 12.dp))
                        if ((page + 1) * 20 < total) {
                            TextButton(onClick = { load(page + 1) }) { Text("下一页") }
                        }
                    }
                }
            }
        }
    }

    // 禁用账号二次确认：开关一旦拨过去就直接调接口太危险
    disableTarget?.let { u ->
        val uid = u.optLong("id", 0)
        val name = u.optString("displayName").ifBlank { u.optString("username") }
        AlertDialog(
            onDismissRequest = { disableTarget = null },
            title = { Text("禁用账号") },
            text = { Text("确定禁用「$name」吗？禁用后该用户将无法登录。") },
            confirmButton = {
                Button(onClick = {
                    disableTarget = null
                    scope.launch {
                        val ok = runCatching { KbApi.adminToggleUser(uid, false) }.isSuccess
                        if (ok) {
                            actionMsg = ""
                            users = users.map {
                                if (it.optLong("id", 0) == uid) JSONObject(it.toString()).put("enabled", false) else it
                            }
                        } else {
                            actionMsg = "禁用失败，请重试"
                        }
                    }
                }) { Text("确认禁用") }
            },
            dismissButton = {
                TextButton(onClick = { disableTarget = null }) { Text("取消") }
            }
        )
    }

    roleTarget?.let { u ->
        var role by remember { mutableStateOf(u.optString("role")) }
        AlertDialog(
            onDismissRequest = { roleTarget = null },
            title = { Text("修改角色") },
            text = {
                Column {
                    listOf("USER" to "普通用户", "ADMIN" to "管理员").forEach { (v, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { role = v }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(selected = role == v, onClick = { role = v })
                            Text(label, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        if (runCatching { KbApi.adminSetRole(u.optLong("id", 0), role) }.isSuccess) {
                            users = users.map {
                                if (it.optLong("id", 0) == u.optLong("id", 0))
                                    JSONObject(it.toString()).put("role", role) else it
                            }
                            roleTarget = null
                        } else {
                            actionMsg = "角色修改失败，请重试"
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { roleTarget = null }) { Text("取消") } }
        )
    }

    // 新建用户
    if (showCreate) {
        var nu by remember { mutableStateOf("") }
        var nn by remember { mutableStateOf("") }
        var np by remember { mutableStateOf("") }
        var nrole by remember { mutableStateOf("USER") }
        var nEnabled by remember { mutableStateOf(true) }
        var tip by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) showCreate = false },
            title = { Text("新建用户", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = nu, onValueChange = { nu = it.trim() },
                        label = { Text("用户名（登录用）", fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = nn, onValueChange = { nn = it },
                        label = { Text("昵称（留空用用户名）", fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = np, onValueChange = { np = it },
                        label = { Text("初始密码（至少 6 位）", fontSize = 11.sp) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("角色", fontSize = 11.sp, color = KbTextDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("USER" to "普通用户", "ADMIN" to "管理员").forEach { (v, label) ->
                            val on = nrole == v
                            Text(
                                label, fontSize = 12.sp,
                                color = if (on) Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .background(
                                        if (on) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { nrole = v }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("立即启用", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Switch(checked = nEnabled, onCheckedChange = { nEnabled = it })
                    }
                    if (tip.isNotEmpty()) {
                        Text(tip, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        if (nu.isBlank()) { tip = "请填写用户名"; return@Button }
                        if (np.length < 6) { tip = "密码至少 6 位"; return@Button }
                        busy = true
                        scope.launch {
                            val r = runCatching {
                                KbApi.adminCreateUser(
                                    JSONObject()
                                        .put("username", nu)
                                        .put("displayName", nn.trim().ifBlank { nu })
                                        .put("password", np)
                                        .put("role", nrole)
                                        .put("enabled", nEnabled)
                                )
                            }
                            busy = false
                            if (r.isSuccess) {
                                showCreate = false
                                actionMsg = ""
                                load(0)
                            } else {
                                tip = "创建失败：" + (r.exceptionOrNull()?.message ?: "请重试")
                            }
                        }
                    }
                ) { Text(if (busy) "创建中…" else "创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } }
        )
    }

    // 重置密码：服务端生成随机密码，这里直接把新密码显示出来（只显示一次）
    resetTarget?.let { u ->
        val name = u.optString("displayName").ifBlank { u.optString("username") }
        AlertDialog(
            onDismissRequest = { resetTarget = null; resetResult = "" },
            title = { Text(if (resetResult.isEmpty()) "重置密码" else "新密码") },
            text = {
                if (resetResult.isEmpty()) {
                    Text("确定重置「$name」的密码？旧密码立即失效。", fontSize = 13.sp)
                } else {
                    Column {
                        Text("请把新密码发给该用户，关闭后无法再次查看：", fontSize = 12.sp, color = KbTextDim)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            resetResult, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                if (resetResult.isEmpty()) {
                    Button(onClick = {
                        val uid = u.optLong("id", 0)
                        scope.launch {
                            val r = runCatching { KbApi.adminResetPassword(uid) }
                            r.fold(
                                onSuccess = { resp ->
                                    val pwd = resp.optString("password").ifBlank {
                                        resp.optString("newPassword").ifBlank { "（服务端未返回，请让用户走找回密码）" }
                                    }
                                    resetResult = pwd
                                },
                                onFailure = {
                                    resetTarget = null
                                    actionMsg = "重置失败：" + (it.message ?: "请重试")
                                }
                            )
                        }
                    }) { Text("确认重置") }
                } else {
                    Button(onClick = { resetTarget = null; resetResult = "" }) { Text("知道了") }
                }
            },
            dismissButton = if (resetResult.isEmpty()) {
                { TextButton(onClick = { resetTarget = null }) { Text("取消") } }
            } else null
        )
    }
}

private fun roleCn(r: String) = when (r) {
    "ADMIN" -> "管理员"
    "USER" -> "用户"
    else -> r
}

// ============ AI 模型管理 ============

@Composable
fun AdminAiScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var platforms by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var testingId by remember { mutableStateOf<Long?>(null) }
    var actionMsg by remember { mutableStateOf("") }
    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var deleteTarget by remember { mutableStateOf<JSONObject?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun load() {
        loading = true
        error = ""
        scope.launch {
            try {
                models = KbApi.adminAiModels().objects()
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        load()
        // 平台列表拿不到不影响主流程（表单里就只剩手动填）
        platforms = runCatching { KbApi.adminAiPlatforms().objects() }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "AI 模型管理", onBack = onBack) {
            TextButton(onClick = { editing = null; showForm = true }) { Text("添加", fontSize = 13.sp) }
        }
        if (actionMsg.isNotEmpty()) {
            Text(
                actionMsg, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        when {
            loading -> CenterHint("加载中…", loading = true)
            error.isNotEmpty() -> CenterHint(error)
            models.isEmpty() -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("还没有模型", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("添加一个后，AI 问答、出题、OCR 就能用上", fontSize = 12.sp, color = KbTextDim)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { editing = null; showForm = true }) { Text("添加模型") }
            }
            else -> LazyColumn(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .align(Alignment.CenterHorizontally),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(models, key = { _, m -> m.optLong("id", 0) }) { i, m ->
                    val id = m.optLong("id", 0)
                    val enabled = m.optBoolean("enabled", true)
                    val isDefault = m.optBoolean("isDefault", false)
                    KbCard(
                        modifier = Modifier.kbItemEnter(i),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            m.optString("name"),
                                            fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                        if (isDefault) {
                                            Spacer(Modifier.size(6.dp))
                                            Tag("默认", KbBlue)
                                        }
                                        if (m.optBoolean("vision", false)) {
                                            Spacer(Modifier.size(4.dp))
                                            Tag("OCR", KbGreen)
                                        }
                                    }
                                    Text(
                                        m.optString("provider") + " · " + m.optString("model"),
                                        fontSize = 11.sp, color = KbTextDim, maxLines = 1
                                    )
                                }
                                Switch(checked = enabled, onCheckedChange = { on ->
                                    scope.launch {
                                        val r = runCatching { KbApi.adminToggleAiModel(id, on) }
                                        if (r.isSuccess) {
                                            models = models.map {
                                                if (it.optLong("id", 0) == id) JSONObject(it.toString()).put("enabled", on) else it
                                            }
                                        } else {
                                            actionMsg = "切换失败：" + (r.exceptionOrNull()?.message ?: "请重试")
                                        }
                                    }
                                })
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                TextButton(
                                    enabled = testingId != id,
                                    onClick = {
                                        testingId = id
                                        actionMsg = ""
                                        scope.launch {
                                            val r = runCatching { KbApi.adminTestAiModel(id) }
                                            testingId = null
                                            actionMsg = r.fold(
                                                onSuccess = { resp ->
                                                    if (resp.optBoolean("ok", false) || resp.has("latencyMs"))
                                                        "✓「${m.optString("name")}」连通正常"
                                                    else "✗ " + resp.optString("error", "测试失败")
                                                },
                                                onFailure = { "✗ " + (it.message ?: "测试失败") }
                                            )
                                        }
                                    }
                                ) { Text(if (testingId == id) "测试中…" else "测试", fontSize = 12.sp) }
                                if (!isDefault && enabled) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            val r = runCatching { KbApi.adminDefaultAiModel(id) }
                                            if (r.isSuccess) load() else actionMsg = "设置默认失败"
                                        }
                                    }) { Text("设为默认", fontSize = 12.sp) }
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { editing = m; showForm = true }) {
                                    Text("编辑", fontSize = 12.sp)
                                }
                                TextButton(onClick = { deleteTarget = m }) {
                                    Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showForm) {
        AiModelFormDialog(
            platforms = platforms,
            initial = editing,
            saving = saving,
            onDismiss = { showForm = false },
            onSave = { body ->
                scope.launch {
                    saving = true
                    val r = runCatching { KbApi.adminSaveAiModel(body) }
                    saving = false
                    if (r.isSuccess) {
                        actionMsg = ""
                        showForm = false
                        load()
                    } else {
                        actionMsg = "保存失败：" + (r.exceptionOrNull()?.message ?: "请检查填写内容")
                    }
                }
            }
        )
    }

    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除模型", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "确定删除「" + t.optString("name") + "」？删除后 AI 功能会回退到其它可用模型。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = t.optLong("id", 0)
                        deleteTarget = null
                        scope.launch {
                            val r = runCatching { KbApi.adminDeleteAiModel(id) }
                            if (r.isSuccess) load()
                            else actionMsg = "删除失败：" + (r.exceptionOrNull()?.message ?: "请重试")
                        }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

/** 小标签（默认 / OCR），统一圆角底色，避免每处手写一遍 */
@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text, fontSize = 10.sp, color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * 新建 / 编辑模型表单。
 *
 * 字段对齐 Web 端「AI 模型管理」：平台、显示名称、模型 ID、接口地址（仅 CUSTOM）、
 * API Key、上下文窗口、用于 OCR、启用。
 * 编辑时 API Key 留空 = 不修改（后端拿到空值不会覆盖已存的密文）。
 */
@Composable
private fun AiModelFormDialog(
    platforms: List<JSONObject>,
    initial: JSONObject?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (JSONObject) -> Unit
) {
    val editId = initial?.optLong("id", 0) ?: 0L
    var provider by remember { mutableStateOf(initial?.optString("provider").orEmpty().ifBlank { "DEEPSEEK" }) }
    var name by remember { mutableStateOf(initial?.optString("name").orEmpty()) }
    var model by remember { mutableStateOf(initial?.optString("model").orEmpty()) }
    var baseUrl by remember { mutableStateOf(initial?.optString("baseUrl").orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var ctx by remember { mutableStateOf((initial?.optInt("contextWindow", 32000) ?: 32000).toString()) }
    var vision by remember { mutableStateOf(initial?.optBoolean("vision", false) ?: false) }
    var enabled by remember { mutableStateOf(initial?.optBoolean("enabled", true) ?: true) }
    var tip by remember { mutableStateOf("") }
    val isCustom = provider == "CUSTOM"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (editId > 0) "编辑模型" else "添加模型",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("平台", fontSize = 11.sp, color = KbTextDim)
                if (platforms.isEmpty()) {
                    Text(provider, fontSize = 13.sp)
                } else {
                    // 平台一旦建好就不再改（换平台等于换接口协议），编辑时置灰
                    platforms.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { p ->
                                val key = p.optString("key")
                                val on = key == provider
                                Text(
                                    p.optString("label").ifBlank { key },
                                    fontSize = 12.sp,
                                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .background(
                                            if (on) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable(enabled = editId == 0L) { provider = key }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("显示名称（留空用模型 ID）", fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("模型 ID（如 deepseek-chat）", fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                if (isCustom) {
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it },
                        label = { Text("接口地址（https://…/v1）", fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text(if (editId > 0) "API Key（留空不修改）" else "API Key", fontSize = 11.sp) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ctx,
                    onValueChange = { v -> ctx = v.filter { it.isDigit() }.take(7) },
                    label = { Text("上下文窗口（tokens）", fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("用于图片 OCR / 视觉识别", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(checked = vision, onCheckedChange = { vision = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (tip.isNotEmpty()) {
                    Text(tip, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    if (model.isBlank()) { tip = "请填写模型 ID"; return@Button }
                    if (editId == 0L && apiKey.isBlank()) { tip = "新建时必须填写 API Key"; return@Button }
                    if (isCustom && baseUrl.isBlank()) { tip = "自定义平台要填接口地址"; return@Button }
                    val body = JSONObject()
                        .put("provider", provider)
                        .put("model", model.trim())
                        .put("name", name.trim().ifBlank { model.trim() })
                        .put("contextWindow", ctx.toIntOrNull() ?: 32000)
                        .put("vision", vision)
                        .put("enabled", enabled)
                    if (baseUrl.isNotBlank()) body.put("baseUrl", baseUrl.trim())
                    if (apiKey.isNotBlank()) body.put("apiKey", apiKey.trim())
                    if (editId > 0) body.put("id", editId)
                    onSave(body)
                }
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ============ 文章审核 ============

@Composable
fun AdminReviewScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var rejectTarget by remember { mutableStateOf<JSONObject?>(null) }
    var msg by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf("") }

    fun load() {
        loading = true
        error = ""
        scope.launch {
            try {
                docs = KbApi.adminPendingDocs().objects()
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "文章审核", onBack = onBack)
        if (msg.isNotEmpty()) {
            Text(msg, fontSize = 12.sp, color = KbGreen, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (actionError.isNotEmpty()) {
            Text(actionError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (loading) {
            CenterHint("加载中…", loading = true)
        } else if (error.isNotEmpty()) {
            CenterHint(error)
        } else if (docs.isEmpty()) {
            CenterHint("没有待审核的文章 🎉")
        } else {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(docs, key = { i, d -> d.optLong("id", -1L).let { if (it == -1L) "review-$i" else it } }) { i, d ->
                    val id = d.optLong("id", 0)
                    KbCard(
                        modifier = Modifier.kbItemEnter(i),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(d.optString("title").ifBlank { "未命名" }, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                listOfNotNull(
                                    d.optString("ownerName").takeIf { it.isNotEmpty() },
                                    formatDateTime(d.optString("createdAt"))
                                ).joinToString(" · "),
                                fontSize = 11.sp, color = KbTextDim
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                Button(onClick = {
                                    scope.launch {
                                        if (runCatching { KbApi.adminApproveDoc(id) }.isSuccess) {
                                            docs = docs.filter { it.optLong("id", 0) != id }
                                            msg = "已通过一篇"
                                        } else {
                                            actionError = "通过失败，请重试"
                                        }
                                    }
                                }) { Text("通过", fontSize = 12.sp) }
                                OutlinedButton(onClick = { rejectTarget = d }) { Text("驳回", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }
    }

    rejectTarget?.let { d ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejectTarget = null },
            title = { Text("驳回文章") },
            text = {
                Column {
                    Text(d.optString("title"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        placeholder = { Text("驳回原因（将通知作者）") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        if (reason.isBlank()) { actionError = "请填写驳回原因"; return@launch }
                        if (runCatching { KbApi.adminRejectDoc(d.optLong("id", 0), reason.trim()) }.isSuccess) {
                            docs = docs.filter { it.optLong("id", 0) != d.optLong("id", 0) }
                            rejectTarget = null
                            msg = "已驳回一篇"
                        } else {
                            actionError = "驳回失败，请重试"
                        }
                    }
                }) { Text("确认驳回") }
            },
            dismissButton = { TextButton(onClick = { rejectTarget = null }) { Text("取消") } }
        )
    }
}

// ============ 公告管理 ============

@Composable
fun AdminAnnounceScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<JSONObject?>(null) }
    var actionMsg by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf("") }

    fun load() {
        loading = true
        error = ""
        scope.launch {
            try {
                list = KbApi.adminAnnouncements().objects()
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "公告管理", onBack = onBack, actions = {
            TextButton(onClick = { showCreate = true }) { Text("发布", fontSize = 14.sp) }
        })
        if (actionMsg.isNotEmpty()) {
            Text(actionMsg, fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        if (loading) {
            CenterHint("加载中…", loading = true)
        } else if (error.isNotEmpty()) {
            CenterHint(error)
        } else if (list.isEmpty()) {
            CenterHint("暂无公告")
        } else {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(list, key = { i, a -> a.optLong("id", -1L).let { if (it == -1L) "ann-$i" else it } }) { i, a ->
                    val id = a.optLong("id", 0)
                    val enabled = a.optBoolean("enabled", true)
                    KbCard(
                        modifier = Modifier.kbItemEnter(i),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (a.optBoolean("pinned", false)) {
                                    Text("置顶", fontSize = 10.sp, color = KbWarn,
                                        modifier = Modifier
                                            .background(KbWarn.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp))
                                    Spacer(Modifier.size(6.dp))
                                }
                                Text(
                                    a.optString("title").ifBlank { "未命名公告" },
                                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(checked = enabled, onCheckedChange = { on ->
                                    scope.launch {
                                        if (!runCatching { KbApi.adminToggleAnnouncement(id, on) }.isSuccess) {
                                            actionMsg = "状态修改失败，请重试"; return@launch
                                        }
                                        list = list.map {
                                            if (it.optLong("id", 0) == id)
                                                JSONObject(it.toString()).put("enabled", on) else it
                                        }
                                    }
                                })
                            }
                            Text(
                                a.optString("content"),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                            Row(Modifier.padding(top = 6.dp)) {
                                TextButton(onClick = { editTarget = a }) { Text("编辑", fontSize = 12.sp) }
                                TextButton(onClick = {
                                    scope.launch {
                                        if (runCatching { KbApi.adminPinAnnouncement(id) }.isSuccess) load()
                                        else actionMsg = "置顶操作失败"
                                    }
                                }) { Text("置顶/取消", fontSize = 12.sp) }
                                TextButton(onClick = {
                                    scope.launch {
                                        if (runCatching { KbApi.adminDeleteAnnouncement(id) }.isSuccess)
                                            list = list.filter { it.optLong("id", 0) != id }
                                        else actionMsg = "删除失败，请重试"
                                    }
                                }) { Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        var title by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("发布公告") },
            text = {
                Column {
                    OutlinedTextField(value = title, onValueChange = { title = it },
                        placeholder = { Text("标题") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = content, onValueChange = { content = it },
                        placeholder = { Text("内容") }, minLines = 3,
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (title.isNotBlank()) {
                        scope.launch {
                            if (runCatching { KbApi.adminCreateAnnouncement(title.trim(), content.trim()) }.isSuccess) {
                                showCreate = false
                                load()
                            } else {
                                actionMsg = "发布失败，请重试"
                            }
                        }
                    }
                }) { Text("发布") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } }
        )
    }

    // 编辑公告：与发布共用同一套字段，保存走 PUT
    editTarget?.let { a ->
        val id = a.optLong("id", 0)
        var title by remember { mutableStateOf(a.optString("title")) }
        var content by remember { mutableStateOf(a.optString("content")) }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) editTarget = null },
            title = { Text("编辑公告", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it },
                        placeholder = { Text("标题") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = content, onValueChange = { content = it },
                        placeholder = { Text("内容") }, minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (editError.isNotEmpty()) {
                        Text(editError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy && title.isNotBlank(),
                    onClick = {
                        busy = true
                        scope.launch {
                            val r = runCatching { KbApi.adminUpdateAnnouncement(id, title.trim(), content.trim()) }
                            busy = false
                            if (r.isSuccess) { editTarget = null; editError = ""; load() } else { editError = "保存失败，请重试" }
                        }
                    }
                ) { Text(if (busy) "保存中…" else "保存") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("取消") } }
        )
    }
}
