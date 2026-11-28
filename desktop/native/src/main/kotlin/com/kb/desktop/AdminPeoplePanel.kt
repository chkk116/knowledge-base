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
import androidx.compose.foundation.layout.heightIn
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




// ---------------- 用户 ----------------

@Composable
internal fun UsersTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(0) }
    var size by remember { mutableStateOf(20) }
    var users by remember { mutableStateOf<List<AdminUserItem>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    // 「管理」对话框的目标用户（改角色 / 调积分 / 重置密码）
    var acting by remember { mutableStateOf<AdminUserItem?>(null) }
    // 批量启用 / 停用：后端只有单个 toggle，逐个调用后汇总
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var batchBusy by remember { mutableStateOf(false) }

    fun runBatch(enabled: Boolean) {
        val ids = selected.toList()
        if (ids.isEmpty() || batchBusy) return
        batchBusy = true
        scope.launch {
            var ok = 0
            var fail = 0
            var firstErr: String? = null
            for (id in ids) {
                try {
                    api.setUserEnabled(session.token, id, enabled)
                    ok++
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail++
                    if (firstErr == null) firstErr = e.message
                }
            }
            selected = emptySet()
            batchBusy = false
            toast = if (fail == 0) "已${if (enabled) "启用" else "停用"} $ok 人"
            else "成功 $ok 人，失败 $fail 人（${firstErr ?: "未知原因"}）"
            tick++
        }
    }

    LaunchedEffect(session.server, session.token, tick, q, page, size) {
        selected = emptySet()   // 换页 / 搜索 / 刷新 / 换账号后清空选择，避免批量到看不见的人
        loading = true
        err = null
        try {
            val box = api.adminUsersPage(session.token, q.trim(), page, size)
            users = box.items
            total = box.total
            // 当前页被删空（或搜索后总量变小）时自动退回最后一页，避免空列表假象
            if (users.isEmpty() && page > 0 && box.total > 0) {
                page = ((box.total - 1) / size).toInt().coerceAtLeast(0)
            }
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("用户", "共 $total 人", onRefresh = { tick++ })
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchBox(q, { q = it; page = 0 }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        if (selected.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已选 ${selected.size} 人", fontSize = 11.5.sp, color = KbTextMain)
                Spacer(Modifier.width(10.dp))
                AdminButton("全选本页", enabled = !batchBusy) { selected = users.map { it.id }.toSet() }
                Spacer(Modifier.width(6.dp))
                AdminButton(if (batchBusy) "处理中…" else "批量停用", danger = true, enabled = !batchBusy) { runBatch(false) }
                Spacer(Modifier.width(6.dp))
                AdminButton(if (batchBusy) "处理中…" else "批量启用", enabled = !batchBusy) { runBatch(true) }
                Spacer(Modifier.weight(1f))
                AdminButton("取消选择", enabled = !batchBusy) { selected = emptySet() }
            }
        }
        KbTableHead(listOf("" to 0.4f, "用户" to 3f, "角色" to 1f, "积分" to 0.9f, "注册时间" to 1.5f, "操作" to 1.7f))
        Box(Modifier.weight(1f)) {
            when {
                loading -> KbSkeletonList()
                err != null -> AdminError(err ?: "", { tick++ })
                users.isEmpty() -> AdminHint("没有匹配的用户")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(users, key = { it.id }) { u ->
                        KbTableRow {
                            val checked = u.id in selected
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (checked) KbAccent else Color.Transparent)
                                    .border(1.dp, if (checked) KbAccent else KbBorder, RoundedCornerShape(4.dp))
                                    .clickable {
                                        selected = if (checked) selected - u.id else selected + u.id
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (checked) Text("✓", fontSize = 10.sp, color = Color.White)
                            }
                            Column(Modifier.weight(3f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        u.displayName.ifBlank { u.username },
                                        fontSize = 13.sp,
                                        color = if (u.enabled) KbTextMain else Color(0xFF9CA3AF),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!u.enabled) {
                                        Spacer(Modifier.width(6.dp))
                                        KbTag("已停用", KbTone.Danger)
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    u.username,
                                    fontSize = 10.5.sp,
                                    color = KbTextSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                KbTag(
                                    if (u.role.equals("ADMIN", true)) "管理员" else "用户",
                                    if (u.role.equals("ADMIN", true)) KbTone.Primary else KbTone.Neutral,
                                )
                            }
                            Text(
                                "${u.points}",
                                modifier = Modifier.weight(0.9f),
                                fontSize = 12.sp,
                                color = KbTextMain,
                                maxLines = 1,
                            )
                            Text(
                                fmtAdminTime(u.createdAt),
                                modifier = Modifier.weight(1.5f),
                                fontSize = 11.sp,
                                color = KbTextSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(Modifier.weight(1.7f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AdminButton("管理", enabled = !busy) { acting = u }
                                AdminButton(if (u.enabled) "停用" else "启用", danger = u.enabled, enabled = !busy) {
                                    scope.launch {
                                        busy = true
                                        try {
                                            api.setUserEnabled(session.token, u.id, !u.enabled)
                                            users = users.map { if (it.id == u.id) it.copy(enabled = !u.enabled) else it }
                                            toast = if (u.enabled) "已停用 ${u.username}" else "已启用 ${u.username}"
                                        } catch (e: Exception) {
                                            toast = e.message ?: "操作失败"
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = KbBorder)
        KbPager(
            total = total,
            page = page,
            size = size,
            onPage = { page = it },
            onSize = { size = it; page = 0 },
        )
        Text(
            "改角色 / 重置密码 / 调积分请在 Web 端「用户管理」操作",
            fontSize = 10.sp,
            color = KbTextSub,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
    }
    acting?.let { u ->
        UserActionDialog(
            session = session,
            user = u,
            onClose = { acting = null },
            onChanged = { tick++ },
        )
    }
    AdminToast(toast)
}

/**
 * 用户管理对话框：改角色 / 调积分 / 重置密码。
 *
 * 这三件事以前必须开 Web —— 后端接口早已就绪（PUT /users/{id}、POST /points、POST /reset-password），
 * 只是桌面端没做 UI。按「点一下即生效」设计，不弹二次确认：都可撤销，且后端都会写审计日志。
 */
@Composable
private fun UserActionDialog(
    session: Session,
    user: AdminUserItem,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var role by remember { mutableStateOf(user.role.uppercase().ifBlank { "USER" }) }
    var delta by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("管理员调整") }
    var note by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }

    KbDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                "管理 · ${user.displayName.ifBlank { user.username }}",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(Modifier.width(420.dp)) {
                Text(
                    "${user.username} · 当前 ${user.points} 积分 · ${if (user.enabled) "正常" else "已停用"}",
                    fontSize = 11.5.sp,
                    color = KbTextSub,
                )
                if (note.isNotEmpty()) {
                    Text(note, fontSize = 11.5.sp, color = AdminErrRed, modifier = Modifier.padding(top = 4.dp))
                }

                Spacer(Modifier.height(14.dp))
                Text("角色（点一下即生效，会写审计日志）", fontSize = 11.5.sp, color = KbTextSub)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (r in listOf("USER", "ADMIN")) {
                        val selected = role == r
                        AdminButton(
                            (if (r == "ADMIN") "管理员" else "普通用户") + if (selected) " ✓" else "",
                            enabled = !busy && !selected,
                        ) {
                            busy = true
                            scope.launch {
                                try {
                                    api.setUserRole(session.token, user.id, r)
                                    role = r
                                    note = "已改为「${if (r == "ADMIN") "管理员" else "普通用户"}」"
                                    onChanged()
                                } catch (e: Exception) {
                                    note = e.message ?: "改角色失败"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text("调整积分（可填负数扣分）", fontSize = 11.5.sp, color = KbTextSub)
                Row(
                    Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = delta,
                        onValueChange = { delta = it.filter { ch -> ch.isDigit() || ch == '-' } },
                        modifier = Modifier.width(100.dp),
                        singleLine = true,
                        placeholder = { Text("±积分", fontSize = 11.5.sp, color = KbTextSub) },
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KbPrimary, unfocusedBorderColor = KbBorder),
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("原因（写入积分明细）", fontSize = 11.5.sp, color = KbTextSub) },
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KbPrimary, unfocusedBorderColor = KbBorder),
                    )
                    AdminButton("提交", enabled = !busy && (delta.toIntOrNull() ?: 0) != 0) {
                        val d = delta.toIntOrNull() ?: return@AdminButton
                        busy = true
                        scope.launch {
                            try {
                                api.adjustUserPoints(session.token, user.id, d, reason.trim().ifBlank { "管理员调整" })
                                note = "已${if (d > 0) "加" else "扣"} ${kotlin.math.abs(d)} 积分"
                                delta = ""
                                onChanged()
                            } catch (e: Exception) {
                                note = e.message ?: "调整失败"
                            } finally {
                                busy = false
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text("重置密码", fontSize = 11.5.sp, color = KbTextSub)
                Spacer(Modifier.height(6.dp))
                if (newPwd.isEmpty()) {
                    AdminButton("生成新密码", danger = true, enabled = !busy) {
                        busy = true
                        scope.launch {
                            try {
                                val pwd = api.resetUserPassword(session.token, user.id)
                                if (pwd.isBlank()) note = "后端未返回新密码" else newPwd = pwd
                            } catch (e: Exception) {
                                note = e.message ?: "重置失败"
                            } finally {
                                busy = false
                            }
                        }
                    }
                } else {
                    Text(newPwd, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
                    Text(
                        "只显示这一次，请立即转告用户并让其自行修改",
                        fontSize = 10.5.sp,
                        color = Color(0xFFB45309),
                    )
                }
            }
        },
        dismissButton = { AdminButton("关闭") { onClose() } },
    )
}

// ---------------- 部门（只读树） ----------------

@Composable
internal fun DepartmentsTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var depts by remember { mutableStateOf<List<DeptItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var editOpen by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<DeptItem?>(null) }
    var memberTarget by remember { mutableStateOf<DeptItem?>(null) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    // 切换服务器/账号：部门树与各个弹窗目标都属于上一个会话，清掉后再拉（旧部门 id 不能带着删除）
    LaunchedEffect(session.server, session.token) {
        depts = emptyList()
        editOpen = false
        editTarget = null
        memberTarget = null
        confirmDelete = null
    }

    LaunchedEffect(session.server, session.token, tick) {
        loading = true
        err = null
        try {
            depts = api.adminDepartments(session.token)
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    // 扁平 → 树（缺 parent 的孤儿挂到根，避免丢数据）
    val rows = remember(depts) {
        val byParent = depts.groupBy { it.parentId }
        val out = ArrayList<Pair<DeptItem, Int>>(depts.size)
        val seen = HashSet<Long>()
        val roots = depts.filter { it.parentId == null || depts.none { p -> p.id == it.parentId } }
        fun walk(d: DeptItem, depth: Int) {
            if (!seen.add(d.id) || depth > 20) return
            out.add(d to depth)
            byParent[d.id].orEmpty().sortedBy { it.sortOrder }.forEach { walk(it, depth + 1) }
        }
        roots.sortedBy { it.sortOrder }.forEach { walk(it, 0) }
        // 兜底：循环引用等异常情况下把没走到的补在末尾
        depts.filterNot { it.id in seen }.forEach { out.add(it to 0) }
        out
    }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("部门", "共 ${depts.size} 个", onRefresh = { tick++ })
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
            AdminButton("新建部门", enabled = !busy) { editTarget = null; editOpen = true }
        }
        Box(Modifier.weight(1f)) {
            when {
                loading -> KbSkeletonList()
                err != null -> AdminError(err ?: "", { tick++ })
                depts.isEmpty() -> AdminHint("还没有部门\n点上方「新建部门」添加根部门")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows.size) { i ->
                        val (d, depth) = rows[i]
                        Row(
                            Modifier.fillMaxWidth().padding(start = 18.dp + 18.dp * depth, end = 12.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(
                                    if (depth == 0) KbPrimary else Color(0xFFCBD5E1),
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                d.name.ifBlank { "（未命名）" },
                                fontSize = if (depth == 0) 13.sp else 12.5.sp,
                                fontWeight = if (depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                                color = KbTextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            val code = d.code
                            if (!code.isNullOrBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(code, fontSize = 10.5.sp, color = KbTextSub)
                            }
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AdminButton("编辑", enabled = !busy) { editTarget = d; editOpen = true }
                                AdminButton("成员", enabled = !busy) { memberTarget = d }
                                if (confirmDelete == d.id) {
                                    AdminButton("确认删除", danger = true, enabled = !busy) {
                                        busy = true
                                        scope.launch {
                                            try {
                                                api.deleteDepartment(session.token, d.id)
                                                confirmDelete = null
                                                toast = "已删除「${d.name}」"
                                                tick++
                                            } catch (e: Exception) {
                                                toast = e.message ?: "删除失败"
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }
                                    AdminButton("取消", enabled = !busy) { confirmDelete = null }
                                } else {
                                    AdminButton("删除", danger = true, enabled = !busy) { confirmDelete = d.id }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            "层级调整（挂到哪个上级下）请在 Web 端拖拽；这里做增删改与成员加入。",
            fontSize = 10.sp,
            color = KbTextSub,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
        )
    }
    if (editOpen) {
        DeptEditDialog(
            session = session,
            target = editTarget,
            onClose = { editOpen = false },
            onSaved = { msg -> toast = msg; editOpen = false; tick++ },
        )
    }
    memberTarget?.let { d ->
        DeptMemberDialog(
            session = session,
            dept = d,
            onClose = { memberTarget = null },
            onSaved = { msg -> toast = msg; memberTarget = null; tick++ },
        )
    }
    AdminToast(toast)
}

/**
 * 新建 / 编辑部门。
 *
 * 只做名称、编码、排序：层级调整（挂到哪个上级下）仍走 Web 端拖拽——树形父级选择控件
 * 在这里要下拉 + 防成环校验，收益不抵复杂度；后端 move 接口也要求 parentId+sortOrder 同时给。
 */
@Composable
private fun DeptEditDialog(
    session: Session,
    target: DeptItem?,
    onClose: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(target?.name.orEmpty()) }
    var code by remember { mutableStateOf(target?.code.orEmpty()) }
    var sort by remember { mutableStateOf((target?.sortOrder ?: 0).toString()) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val creating = target == null

    KbDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                if (creating) "新建部门（根部门）" else "编辑部门 · ${target.name}",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(Modifier.width(400.dp)) {
                Text(
                    if (creating) "新建出来的是根部门；要挂到某个上级下请在 Web 端拖拽"
                    else "只改名称 / 编码 / 排序；层级调整请在 Web 端拖拽",
                    fontSize = 11.sp,
                    color = KbTextSub,
                )
                if (note.isNotEmpty()) {
                    Text(note, fontSize = 11.5.sp, color = AdminErrRed, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("部门名称", fontSize = 11.5.sp) },
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KbPrimary, unfocusedBorderColor = KbBorder),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("编码（可空）", fontSize = 11.5.sp) },
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KbPrimary, unfocusedBorderColor = KbBorder),
                    )
                    OutlinedTextField(
                        value = sort,
                        onValueChange = { sort = it.filter { ch -> ch.isDigit() || ch == '-' } },
                        modifier = Modifier.width(110.dp),
                        singleLine = true,
                        label = { Text("排序", fontSize = 11.5.sp) },
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KbPrimary, unfocusedBorderColor = KbBorder),
                    )
                }
            }
        },
        dismissButton = { AdminButton("取消", enabled = !busy) { onClose() } },
        confirmButton = {
            AdminButton(if (busy) "保存中…" else "保存", enabled = !busy && name.isNotBlank()) {
                busy = true
                note = ""
                scope.launch {
                    try {
                        val order = sort.toIntOrNull()
                        val codeOrNull = code.trim().ifBlank { if (creating) null else "" }
                        if (creating || target == null) {
                            api.createDepartment(session.token, name.trim(), codeOrNull, null, order, null)
                            onSaved("已新建「${name.trim()}」")
                        } else {
                            api.updateDepartment(session.token, target.id, name.trim(), codeOrNull, null, order, null, null)
                            onSaved("已保存「${name.trim()}」")
                        }
                    } catch (e: Exception) {
                        note = e.message ?: "保存失败"
                    } finally {
                        busy = false
                    }
                }
            }
        },
    )
}

/**
 * 把用户加入部门。
 *
 * 只做「加入」不做「移除」：后端没有按部门查成员的 GET 接口（只有 POST/DELETE members），
 * 无法列出当前成员给用户挑，做半套反而误导。移除在 Web 端「部门管理」里。
 */
@Composable
private fun DeptMemberDialog(
    session: Session,
    dept: DeptItem,
    onClose: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var users by remember { mutableStateOf<List<AdminUserItem>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(session.server, session.token, q) {
        loading = true
        err = null
        kotlinx.coroutines.delay(300)
        try {
            users = api.adminUsersPage(session.token, q.trim(), 0, 20).items
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    KbDialog(
        onDismissRequest = onClose,
        title = { Text("加入成员 · ${dept.name}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(440.dp)) {
                Text(
                    "点用户即加入该部门；移除成员请在 Web 端操作。",
                    fontSize = 11.sp,
                    color = KbTextSub,
                )
                Spacer(Modifier.height(8.dp))
                SearchBox(q, { q = it }, Modifier.fillMaxWidth(), "搜索用户名 / 昵称")
                if (err != null) {
                    Text(err ?: "", fontSize = 11.5.sp, color = AdminErrRed, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.heightIn(max = 320.dp)) {
                    when {
                        loading -> KbSkeletonList(rows = 4)
                        users.isEmpty() -> AdminHint("没有匹配的用户")
                        else -> LazyColumn(Modifier.fillMaxWidth()) {
                            items(users, key = { it.id }) { u ->
                                KbTableRow {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            u.displayName.ifBlank { u.username },
                                            fontSize = 12.5.sp,
                                            color = KbTextMain,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(u.username, fontSize = 10.5.sp, color = KbTextSub, maxLines = 1)
                                    }
                                    AdminButton(
                                        if (added == u.id) "已加入" else "加入",
                                        enabled = !busy && added != u.id,
                                    ) {
                                        busy = true
                                        scope.launch {
                                            try {
                                                api.addDeptMembers(session.token, dept.id, listOf(u.id))
                                                added = u.id
                                            } catch (e: Exception) {
                                                err = e.message ?: "加入失败"
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = { AdminButton("完成") { onSaved("成员已更新") } },
    )
}