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




// ---------------- 用户 ----------------

@Composable
internal fun UsersTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<AdminUserItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick, q) {
        loading = true
        err = null
        try {
            users = api.adminUsers(session.token, q.trim())
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("用户", "${users.size} 人（最多显示 50）", onRefresh = { tick++ })
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchBox(q, { q = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = KbBorder)
        Box(Modifier.weight(1f)) {
            when {
                loading -> AdminHint("加载中…")
                err != null -> AdminError(err ?: "", { tick++ })
                users.isEmpty() -> AdminHint("没有匹配的用户")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(users, key = { it.id }) { u ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        u.displayName.ifBlank { u.username },
                                        fontSize = 13.sp,
                                        color = if (u.enabled) KbTextMain else Color(0xFF9CA3AF),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (u.role.equals("ADMIN", true)) {
                                        Spacer(Modifier.width(6.dp))
                                        AdminChip("管理员", Color(0xFFEEF2FF), KbPrimary)
                                    }
                                    if (!u.enabled) {
                                        Spacer(Modifier.width(6.dp))
                                        AdminChip("已停用", Color(0xFFFEE2E2), Color(0xFFB91C1C))
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    listOf(u.username, "${u.points} 积分", fmtAdminTime(u.createdAt)).filter { it.isNotBlank() }.joinToString(" · "),
                                    fontSize = 10.5.sp,
                                    color = KbTextSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
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
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                    }
                }
            }
        }
        Text(
            "改角色 / 重置密码 / 调积分请在 Web 端「用户管理」操作",
            fontSize = 10.sp,
            color = KbTextSub,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
        )
    }
    AdminToast(toast)
}

// ---------------- 部门（只读树） ----------------

@Composable
internal fun DepartmentsTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    var depts by remember { mutableStateOf<List<DeptItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
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
        AdminHeader("部门", "${depts.size} 个（只读）", onRefresh = { tick++ })
        Box(Modifier.weight(1f)) {
            when {
                loading -> AdminHint("加载中…")
                err != null -> AdminError(err ?: "", { tick++ })
                depts.isEmpty() -> AdminHint("还没有部门\n新建 / 编辑请回 Web 端")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows.size) { i ->
                        val (d, depth) = rows[i]
                        Row(
                            Modifier.fillMaxWidth().padding(start = 18.dp + 18.dp * depth, end = 18.dp, top = 7.dp, bottom = 7.dp),
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
                            )
                            val code = d.code
                            if (!code.isNullOrBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(code, fontSize = 10.5.sp, color = KbTextSub)
                            }
                        }
                    }
                }
            }
        }
        Text(
            "部门增删改与成员分配请回 Web 端「部门管理」",
            fontSize = 10.sp,
            color = KbTextSub,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
        )
    }
}
