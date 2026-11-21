package com.kb.kbplatform.shell.ui.docs

import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.KbChip
import com.kb.kbplatform.shell.ui.common.KbEmpty
import com.kb.kbplatform.shell.ui.common.KbError
import com.kb.kbplatform.shell.ui.common.KbLoading
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.theme.KbGreen
import com.kb.kbplatform.shell.ui.theme.KbRed
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 团队空间成员管理（原生）：列表 / 邀请 / 改权限 / 移除。
 *
 * 后端：`GET|POST /api/doc-shares/{sid}/members`、`PUT|DELETE …/members/{userId}`、
 * `GET /api/doc-shares/users?keyword=`（邀请候选，启用账号最多 20 个）。
 * 权限值只有 READ / WRITE / ADMIN；增删改仅 OWNER / ADMIN（后端再校验一次）。
 * 所有者行来自 `GET /api/doc-shares/my` 的 `ownerName`（不在成员表里，不可移除）。
 */
@Composable
fun SpaceMembersScreen(shareId: Long, spaceName: String, onBack: () -> Unit) {
    val cs = androidx.compose.runtime.rememberCoroutineScope()
    var members by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var busyId by remember { mutableStateOf<Long?>(null) }

    // 空间 meta（所有者 / 我的权限 / 容量）：从 /doc-shares/my 里找同 id 那条
    var ownerName by remember { mutableStateOf("") }
    var myPerm by remember { mutableStateOf("") }
    var used by remember { mutableStateOf(0L) }
    var capacity by remember { mutableStateOf(0L) }

    var inviting by remember { mutableStateOf(false) }
    var kw by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var invitePerm by remember { mutableStateOf("WRITE") }

    val canManage = myPerm.uppercase() == "OWNER" || myPerm.uppercase() == "ADMIN"

    LaunchedEffect(tick) {
        loading = true
        err = ""
        try {
            members = KbApi.shareMembers(shareId).objects()
            runCatching { KbApi.myShares() }.getOrNull()?.let { arr ->
                arr.objects().firstOrNull { it.optLong("id", 0) == shareId }?.let { s ->
                    ownerName = s.optString("ownerName")
                    myPerm = s.optString("myPermission")
                    used = s.optLong("used", 0)
                    capacity = s.optLong("capacity", 0)
                }
            }
        } catch (e: Exception) {
            err = e.message ?: "成员列表加载失败"
        } finally {
            loading = false
        }
    }

    // 邀请候选：展开后按关键词查询（本地 250ms 防抖，避免每敲一个字就打一次）
    LaunchedEffect(inviting, kw) {
        if (!inviting) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        candidates = runCatching { KbApi.shareInviteUsers(kw.trim()).objects() }.getOrDefault(emptyList())
    }

    fun applyPerm(uid: Long, perm: String) {
        busyId = uid
        cs.launch {
            try {
                KbApi.updateShareMember(shareId, uid, perm)
                note = "已把权限改为「" + permLabel(perm) + "」"
                tick++
            } catch (e: Exception) {
                note = e.message ?: "改权限失败"
            } finally {
                busyId = null
            }
        }
    }

    fun remove(uid: Long) {
        busyId = uid
        cs.launch {
            try {
                KbApi.removeShareMember(shareId, uid)
                note = "已移除该成员"
                tick++
            } catch (e: Exception) {
                note = e.message ?: "移除失败"
            } finally {
                busyId = null
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "成员 · " + spaceName.ifBlank { "空间" }, onBack = onBack, actions = {
            TextButton(onClick = { tick++ }) { Text("刷新", fontSize = 13.sp) }
        })

        // meta：所有者 / 我的权限 / 容量
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                "所有者：" + ownerName.ifBlank { "未知" } +
                    " · 我的权限：" + permLabel(myPerm.ifBlank { "READ" }) +
                    if (capacity > 0) " · 已用 " + fmtBytes(used) + " / " + fmtBytes(capacity) else "",
                fontSize = 11.5.sp,
                color = KbTextDim
            )
            if (note.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(note, fontSize = 11.5.sp, color = KbGreen)
            }
        }

        when {
            loading -> KbLoading()
            err.isNotEmpty() -> KbError("成员加载失败：$err", onRetry = { tick++ })
            members.isEmpty() && ownerName.isBlank() -> KbEmpty("👥", "这个空间还没有成员")
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (ownerName.isNotBlank()) {
                    item(key = "owner") {
                        MemberCard(
                            name = ownerName,
                            sub = "所有者",
                            perm = "OWNER",
                            canManage = false,
                            busy = false,
                            onPerm = {},
                            onRemove = {},
                        )
                    }
                }
                items(members, key = { it.optLong("userId", it.hashCode().toLong()) }) { m ->
                    val uid = m.optLong("userId", 0)
                    MemberCard(
                        name = m.optString("displayName").ifBlank { m.optString("username").ifBlank { "用户 $uid" } },
                        sub = m.optString("username"),
                        perm = m.optString("permission").ifBlank { "READ" },
                        canManage = canManage,
                        busy = busyId == uid,
                        onPerm = { p -> applyPerm(uid, p) },
                        onRemove = { remove(uid) },
                    )
                }
            }
        }

        if (canManage) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { inviting = true }) { Text("＋ 邀请成员", fontSize = 13.sp) }
                Spacer(Modifier.weight(1f))
                Text("仅可管理成员能增删", fontSize = 11.sp, color = KbTextDim)
            }
        } else {
            Text(
                "只有所有者或「可管理」成员能增删成员",
                fontSize = 11.sp,
                color = KbTextDim,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }

    if (inviting) {
        AlertDialog(
            onDismissRequest = { inviting = false; kw = "" },
            title = { Text("邀请成员", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("新成员权限：", fontSize = 11.5.sp, color = KbTextDim)
                        for (p in listOf("READ", "WRITE", "ADMIN")) {
                            KbChip(permLabel(p), selected = invitePerm == p, onClick = { invitePerm = p })
                            Spacer(Modifier.size(6.dp))
                        }
                    }
                    OutlinedTextField(
                        value = kw,
                        onValueChange = { kw = it },
                        placeholder = { Text("按用户名 / 昵称搜索", fontSize = 11.5.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                    Box(Modifier.fillMaxWidth().height(220.dp).padding(top = 6.dp)) {
                        if (candidates.isEmpty()) {
                            Text("没有可邀请的用户", fontSize = 12.sp, color = KbTextDim)
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(candidates, key = { it.optLong("id", it.hashCode().toLong()) }) { u ->
                                    val uid = u.optLong("id", 0)
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            u.optString("displayName").ifBlank { u.optString("username") },
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            enabled = busyId != uid,
                                            onClick = {
                                                busyId = uid
                                                cs.launch {
                                                    try {
                                                        KbApi.addShareMember(shareId, uid, invitePerm)
                                                        note = "已邀请加入，权限「" + permLabel(invitePerm) + "」"
                                                        members = runCatching { KbApi.shareMembers(shareId).objects() }
                                                            .getOrDefault(members)
                                                    } catch (e: Exception) {
                                                        note = e.message ?: "邀请失败"
                                                    } finally {
                                                        busyId = null
                                                    }
                                                }
                                            }
                                        ) { Text(if (busyId == uid) "加入中…" else "加入", fontSize = 12.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { inviting = false; kw = "" }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun MemberCard(
    name: String,
    sub: String,
    perm: String,
    canManage: Boolean,
    busy: Boolean,
    onPerm: (String) -> Unit,
    onRemove: () -> Unit,
) {
    KbCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(name.take(1), fontSize = 12.sp, color = KbTextDim)
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (sub.isNotBlank() && sub != name) {
                        Text(sub, fontSize = 11.sp, color = KbTextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                KbChip(permLabel(perm), gray = true, tint = if (perm.uppercase() == "OWNER") KbGreen else null)
            }
            if (canManage && perm.uppercase() != "OWNER") {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("权限：", fontSize = 11.sp, color = KbTextDim)
                    for (p in listOf("READ", "WRITE", "ADMIN")) {
                        KbChip(
                            permLabel(p),
                            selected = perm.uppercase() == p,
                            onClick = { if (!busy && perm.uppercase() != p) onPerm(p) }
                        )
                        Spacer(Modifier.size(6.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = !busy, onClick = onRemove) {
                        Text("移除", fontSize = 12.sp, color = KbRed)
                    }
                }
            }
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

/** READ / WRITE / ADMIN / OWNER 的中文名（与桌面端 permLabel 同口径） */
private fun permLabel(perm: String): String = when (perm.uppercase()) {
    "OWNER" -> "所有者"
    "ADMIN" -> "可管理"
    "WRITE", "EDIT" -> "可编辑"
    "READ", "VIEW" -> "只读"
    else -> perm.lowercase()
}

/** 容量展示：0 = 未使用 */
private fun fmtBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1) String.format("%.1f GB", gb)
    else String.format("%.0f MB", bytes.toDouble() / (1024.0 * 1024.0))
}