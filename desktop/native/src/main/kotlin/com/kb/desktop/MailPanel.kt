package com.kb.desktop

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 邮箱面板（三栏）：账号 → 收件箱 → 阅读。
 *
 * 后端：`GET /api/mail/accounts`、`GET /api/mail/messages`（对象 `{items,total,…}`）、
 * `GET /api/mail/messages/{id}`（带 textBody/htmlBody）、`POST …/{id}/read`、`DELETE …/{id}`、
 * `POST /api/mail/accounts/{id}/sync`、`POST /api/mail/accounts/code`（授权码绑定，服务端真连 IMAP 验证）。
 */
@Composable
internal fun MailPanel(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<MailAccountItem>>(emptyList()) }
    var accId by remember { mutableStateOf(0L) }
    var q by remember { mutableStateOf("") }
    var mails by remember { mutableStateOf<List<MailBrief>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    var sel by remember { mutableStateOf<MailBrief?>(null) }
    var detail by remember { mutableStateOf<MailDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var bindOpen by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    // 写信 / 回复（后端 /send、/drafts 未升级时会把 404/405 透出来）
    var composeOpen by remember { mutableStateOf(false) }
    var composeTo by remember { mutableStateOf("") }
    var composeSubject by remember { mutableStateOf("") }
    var composeReplyTo by remember { mutableStateOf<String?>(null) }
    var composeBody by remember { mutableStateOf("") }
    // 收件箱 / 草稿箱（草稿 folder=DRAFT，本地存的，不占 IMAP）
    var folder by remember { mutableStateOf("INBOX") }
    // 已加载页（「加载更多」递增；切账号/文件夹/刷新归零）
    var page by remember { mutableStateOf(0) }
    // 签名编辑（非空即弹窗，只对选中的账号生效）
    var signingAccount by remember { mutableStateOf<MailAccountItem?>(null) }

    // 切换服务器/账号：账号、邮件与选中项都属于上一个会话，必须先清掉，
    // 否则界面上还是旧账号的邮件，而删除/回复会带着新 token 打到新账号
    LaunchedEffect(session.server, session.token) {
        accounts = emptyList()
        accId = 0L
        mails = emptyList()
        total = 0L
        sel = null
        detail = null
        confirmingDelete = false
        signingAccount = null
        composeOpen = false
        composeReplyTo = null
        page = 0
    }

    LaunchedEffect(session.server, session.token, refresh) {
        accounts = runCatching { api.mailAccounts(session.token) }.getOrDefault(emptyList())
    }

    LaunchedEffect(session.server, session.token, accId, folder, refresh) {
        loading = true
        err = ""
        page = 0
        try {
            val (list, t) = api.mailMessages(
                session.token, accountId = accId.takeIf { it > 0 }, folder = folder, q = q, size = 80
            )
            mails = list
            total = t
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
            mails = emptyList()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(session.server, session.token, sel) {
        val s = sel ?: return@LaunchedEffect
        detail = null
        detailLoading = true
        detail = runCatching { api.mailDetail(session.token, s.id) }.getOrNull()
        detailLoading = false
        if (s.unread) {
            runCatching { api.mailMarkRead(session.token, s.id, true) }
            mails = mails.map { if (it.id == s.id) it.copy(unread = false) else it }
        }
    }

    Row(Modifier.fillMaxSize()) {
        // ---------- 左：账号 ----------
        Column(Modifier.width(210.dp).fillMaxHeight().background(Color_kbBgPanel)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("邮箱", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { bindOpen = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("绑定", fontSize = 11.sp)
                }
            }
            HorizontalDivider(color = KbBorder)

            AccountRow("全部邮箱", accounts.size.toString(), sel = accId <= 0L, onClick = { accId = 0L })
            accounts.forEach { a ->
                val ok = a.status == "OK"
                AccountRow(
                    a.email.ifBlank { a.provider.ifBlank { "邮箱 ${a.id}" } },
                    if (ok) a.provider else "异常",
                    sel = accId == a.id,
                    warn = !ok,
                    onClick = { accId = a.id },
                )
            }

            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = KbBorder)
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (accId > 0L) {
                    TextButton(onClick = {
                        msg = "同步中…"
                        scope.launch {
                            msg = try {
                                val added = api.mailSync(session.token, accId)
                                "同步完成，新增 ${added} 封"
                            } catch (e: Exception) {
                                e.message ?: "同步失败"
                            }
                            refresh++
                        }
                    }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("同步此邮箱", fontSize = 11.sp) }
                    TextButton(
                        onClick = {
                            signingAccount = accounts.firstOrNull { it.id == accId }
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) { Text("签名", fontSize = 11.sp) }
                }
                Text(
                    if (accounts.isEmpty()) "还没有绑定邮箱" else "共 ${accounts.size} 个邮箱",
                    fontSize = 10.5.sp, color = KbTextSub,
                )
            }
        }

        Box(Modifier.width(1.dp).fillMaxHeight().background(KbBorder))

        // ---------- 中：列表 ----------
        Column(Modifier.width(330.dp).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchBox(value = q, onValue = { q = it; refresh++ }, placeholder = "搜索主题 / 发件人")
                Spacer(Modifier.width(6.dp))
                if (accounts.isNotEmpty()) {
                    TextButton(onClick = {
                        composeTo = ""
                        composeSubject = ""
                        composeReplyTo = null
                        composeBody = ""
                        composeOpen = true
                    }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("写邮件", fontSize = 11.sp)
                    }
                }
                TextButton(onClick = { refresh++ }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("刷新", fontSize = 11.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SortChip("收件箱", sel = folder == "INBOX") { folder = "INBOX" }
                SortChip("草稿箱", sel = folder == "DRAFT") { folder = "DRAFT" }
                // 已发送：发信成功后后端在本地存 folder=SENT（uid 为负的合成记录）
                SortChip("已发送", sel = folder == "SENT") { folder = "SENT" }
            }
            if (msg.isNotEmpty()) {
                Text(msg, fontSize = 10.5.sp, color = KbPrimary, modifier = Modifier.padding(horizontal = 12.dp))
            }
            HorizontalDivider(color = KbBorder)
            when {
                loading -> KbSkeletonList(rows = 6)
                err.isNotEmpty() -> CenterNotice("邮件加载失败：$err", onRetry = { refresh++ })
                mails.isEmpty() -> HintCenter(
                    when {
                        accounts.isEmpty() -> "先在左侧「绑定」一个邮箱"
                        q.isNotBlank() -> "没有匹配「$q」的邮件"
                        folder == "DRAFT" -> "草稿箱是空的\n写邮件时点「存草稿」会存到这里"
                        folder == "SENT" -> "还没有发出过邮件"
                        else -> "收件箱是空的\n点「刷新」或同步此邮箱"
                    }
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(mails, key = { it.id }) { m ->
                        MailRow(m, sel = sel?.id == m.id, draft = folder == "DRAFT") { sel = m }
                    }
                    if (mails.size < total) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                var loadingMore by remember { mutableStateOf(false) }
                                TextButton(
                                    enabled = !loadingMore,
                                    onClick = {
                                        loadingMore = true
                                        scope.launch {
                                            try {
                                                val next = page + 1
                                                val (more, _) = api.mailMessages(
                                                    session.token, accountId = accId.takeIf { it > 0 },
                                                    folder = folder, q = q, page = next, size = 80,
                                                )
                                                val known = mails.map { it.id }.toSet()
                                                mails = mails + more.filter { it.id !in known }
                                                page = next
                                            } catch (e: Exception) {
                                                msg = e.message ?: "加载更多失败"
                                            } finally {
                                                loadingMore = false
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text(
                                        if (loadingMore) "加载中…" else "加载更多（还有 ${total - mails.size} 封）",
                                        fontSize = 10.5.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Box(Modifier.width(1.dp).fillMaxHeight().background(KbBorder))

        // ---------- 右：阅读 ----------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val s = sel
            if (s == null) {
                HintCenter("选择一封邮件查看正文")
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("邮件详情", fontSize = 11.sp, color = KbTextSub)
                    Spacer(Modifier.weight(1f))
                    if (folder == "DRAFT") {
                        TextButton(onClick = {
                            composeTo = detail?.toAddrs ?: ""
                            composeSubject = s.subject
                            composeReplyTo = null
                            composeBody = detail?.textBody ?: ""
                            composeOpen = true
                        }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text("编辑草稿", fontSize = 11.sp)
                        }
                    }
                    TextButton(onClick = {
                        composeTo = s.fromAddr
                        composeSubject = "Re: " + s.subject
                        // 详情里有原始 Message-ID 时才串线程；没加载完就是 null（仍可发送）
                        composeReplyTo = detail?.messageId?.takeIf { it.isNotBlank() }
                        composeOpen = true
                    }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("回复", fontSize = 11.sp)
                    }
                    TextButton(onClick = { confirmingDelete = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("删除", fontSize = 11.sp, color = KbTileRose.second)
                    }
                }
                HorizontalDivider(color = KbBorder)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
                    Text(s.subject.ifBlank { "（无主题）" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        s.fromName.ifBlank { s.fromAddr } + "  <" + s.fromAddr + ">",
                        fontSize = 11.5.sp, color = KbTextSub,
                    )
                    detail?.toAddrs?.takeIf { it.isNotBlank() }?.let {
                        Text("收件人：$it", fontSize = 11.5.sp, color = KbTextSub)
                    }
                    Text(s.sentAt.replace('T', ' '), fontSize = 10.5.sp, color = KbTextSub)
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = KbBorder)
                    Spacer(Modifier.height(12.dp))
                    when {
                        detailLoading -> KbSkeletonList(rows = 4)
                        detail == null -> Text("正文加载失败（可再点一次列表）", fontSize = 12.sp, color = KbTileRose.second)
                        else -> {
                            val d = detail!!
                            val body = d.textBody.ifBlank { plainFromHtml(d.htmlBody) }
                            Text(
                                body.ifBlank { "（这封邮件没有可显示的正文）" },
                                fontSize = 12.5.sp, color = KbTextMain,
                            )
                            if (d.attachments.isNotEmpty()) {
                                Spacer(Modifier.height(14.dp))
                                Text("附件（${d.attachments.size}）", fontSize = 10.5.sp, color = KbTextSub)
                                d.attachments.forEach { (name, url) ->
                                    Text(
                                        "📎 $name",
                                        fontSize = 11.sp,
                                        color = KbPrimary,
                                        modifier = Modifier
                                            .clickable { openInBrowser(session.server, url) }
                                            .padding(vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (bindOpen) {
        MailBindDialog(
            session = session,
            onDismiss = { bindOpen = false },
            onBound = { bindOpen = false; msg = "绑定成功，正在同步…"; refresh++ },
        )
    }

    if (composeOpen) {
        MailComposeDialog(
            session = session,
            accounts = accounts,
            initialTo = composeTo,
            initialSubject = composeSubject,
            initialBody = composeBody,
            inReplyTo = composeReplyTo,
            onDismiss = { composeOpen = false },
            onDone = { m ->
                composeOpen = false
                msg = m
                refresh++
            },
        )
    }

    signingAccount?.let { acc ->
        MailSignatureDialog(
            session = session,
            account = acc,
            onDismiss = { signingAccount = null },
            onSaved = {
                signingAccount = null
                msg = "签名已保存（写信时会自动追加）"
                refresh++
            },
        )
    }

    if (confirmingDelete && sel != null) {
        val target = sel!!
        KbDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除这封邮件？") },
            text = { Text("本地记录会删除，并尽力在服务器上也删除（不可恢复）。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    scope.launch {
                        if (runCatching { api.mailDelete(session.token, target.id) }.isSuccess) {
                            mails = mails.filter { it.id != target.id }
                            sel = null
                            detail = null
                            msg = "已删除"
                        } else {
                            msg = "删除失败"
                        }
                    }
                }) { Text("删除", color = KbTileRose.second) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("取消") } },
        )
    }
}

private val Color_kbBgPanel = androidx.compose.ui.graphics.Color(0xFFFBFCFD)

@Composable
private fun AccountRow(label: String, sub: String, sel: Boolean, warn: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (sel) KbSidebarSelected else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(6.dp).background(
                if (warn) KbTileRose.first else KbTileGreen.first, CircleShape
            )
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.5.sp, color = KbTextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 9.5.sp, color = KbTextSub, maxLines = 1)
        }
    }
}

@Composable
private fun MailRow(m: MailBrief, sel: Boolean, draft: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (sel) KbSidebarSelected else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (m.unread) {
                Box(Modifier.size(5.dp).background(KbPrimary, CircleShape))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                m.fromName.ifBlank { m.fromAddr },
                fontSize = 11.5.sp,
                fontWeight = if (m.unread) FontWeight.SemiBold else FontWeight.Normal,
                color = KbTextMain,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(m.sentAt.replace('T', ' ').take(16), fontSize = 9.5.sp, color = KbTextSub)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            (if (draft) "【草稿】" else "") + m.subject.ifBlank { "（无主题）" },
            fontSize = 12.5.sp,
            fontWeight = if (m.unread) FontWeight.SemiBold else FontWeight.Normal,
            color = KbTextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (m.snippet.isNotBlank()) {
            Text(
                m.snippet, fontSize = 10.5.sp, color = KbTextSub, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (m.hasAttach) Text("含附件", fontSize = 9.5.sp, color = KbTextSub)
    }
    HorizontalDivider(color = KbBorder)
}

@Composable
private fun HintCenter(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = KbTextSub)
    }
}

@Composable
private fun CenterNotice(text: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, fontSize = 12.sp, color = KbTextSub)
        TextButton(onClick = onRetry) { Text("重试", fontSize = 12.sp) }
    }
}

/** htmlBody 兜底：只取纯文本（不渲染远程 HTML，避免追踪像素与外链）。 */
private fun plainFromHtml(html: String): String {
    if (html.isBlank()) return ""
    return html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|tr|li|h[1-6])>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

@Composable
private fun MailBindDialog(session: Session, onDismiss: () -> Unit, onBound: () -> Unit) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<MailProvider>>(emptyList()) }
    var provider by remember { mutableStateOf("QQ") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    LaunchedEffect(session.server, session.token) {
        providers = runCatching { api.mailProviders(session.token) }.getOrDefault(emptyList())
    }

    KbDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("绑定邮箱（授权码）") },
        text = {
            Column {
                if (providers.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        providers.take(5).forEach { p ->
                            SortChip(p.name.ifBlank { p.id }, sel = provider == p.id) { provider = p.id }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Field("邮箱地址", email, { email = it }, providers.firstOrNull { it.id == provider }?.emailHint.orEmpty())
                Spacer(Modifier.height(6.dp))
                Field("授权码（不是登录密码）", code, { code = it }, "")
                if (err.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(err, fontSize = 11.sp, color = KbTileRose.second)
                }
                Spacer(Modifier.height(6.dp))
                Text("在邮箱网页版「设置 → 账户」开启 IMAP/SMTP 后获取授权码。", fontSize = 10.sp, color = KbTextSub)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && email.isNotBlank() && code.isNotBlank(), onClick = {
                busy = true
                err = ""
                scope.launch {
                    try {
                        api.mailBindCode(session.token, provider, email.trim(), code.trim())
                        onBound()
                    } catch (e: Exception) {
                        err = e.message ?: "绑定失败"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "验证中…" else "绑定") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

/** 写信 / 回复：真投递走 `/mail/send`，存草稿走 `/mail/drafts`。后端未升级时把 404/405 说明白。 */
@Composable
private fun MailComposeDialog(
    session: Session,
    accounts: List<MailAccountItem>,
    initialTo: String,
    initialSubject: String,
    initialBody: String = "",
    inReplyTo: String?,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var accId by remember { mutableStateOf(accounts.firstOrNull()?.id ?: 0L) }
    var to by remember { mutableStateOf(initialTo) }
    var subject by remember { mutableStateOf(initialSubject) }
    var body by remember { mutableStateOf(initialBody) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    fun recipients(): List<String> =
        to.split(',', ';', '，', '；', '\n').map { it.trim() }.filter { it.contains("@") }

    // 后端只存签名不拼，这里发信/存草稿时追加（已经包含就不重复）
    fun bodyWithSignature(): String {
        val sig = accounts.firstOrNull { it.id == accId }?.signature.orEmpty()
        if (sig.isBlank() || body.contains(sig)) return body
        val b = body.trimEnd()
        return if (b.isEmpty()) sig else b + "\n\n" + sig
    }

    fun friendly(e: Exception): String {
        val m = e.message ?: "操作失败"
        return if (m.startsWith("请求失败(404)") || m.startsWith("请求失败(405)")) "$m（服务端邮箱发信接口可能尚未升级）" else m
    }

    KbDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (inReplyTo == null) "写邮件" else "回复邮件") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    accounts.forEach { a ->
                        SortChip(a.email.ifBlank { "邮箱${a.id}" }, sel = accId == a.id) { accId = a.id }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Field("收件人（多个用逗号分隔）", to, { to = it }, "someone@example.com")
                // 历史收件人补全：取最后一段做关键词，300ms 防抖（/contacts 未升级时返回空，不报错）
                var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
                LaunchedEffect(session.server, session.token, to) {
                    val frag = to.substringAfterLast(',').trim()
                    if (frag.length >= 2 && !frag.contains('@')) {
                        delay(300)
                        suggestions = runCatching { api.mailContacts(session.token, frag) }.getOrDefault(emptyList())
                    } else {
                        suggestions = emptyList()
                    }
                }
                if (suggestions.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        suggestions.take(3).forEach { s ->
                            SortChip(s, sel = false) {
                                val prefix = if (to.contains(',')) to.substringBeforeLast(',') + ", " else ""
                                to = prefix + s + ", "
                                suggestions = emptyList()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Field("主题", subject, { subject = it }, "（无主题）")
                Spacer(Modifier.height(6.dp))
                Text("正文", fontSize = 10.5.sp, color = KbTextSub)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(6.dp))
                        .padding(8.dp),
                ) {
                    if (body.isEmpty()) {
                        Text("写点什么…", fontSize = 11.sp, color = KbTextSub.copy(alpha = 0.7f))
                    }
                    BasicTextField(
                        value = body,
                        onValueChange = { body = it },
                        textStyle = TextStyle(fontSize = 11.5.sp, color = KbTextMain),
                        cursorBrush = SolidColor(KbPrimary),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (err.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(err, fontSize = 11.sp, color = KbTileRose.second)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && accId > 0 && recipients().isNotEmpty(),
                onClick = {
                    busy = true
                    err = ""
                    scope.launch {
                        try {
                            api.mailSend(
                                token = session.token,
                                accountId = accId,
                                to = recipients(),
                                subject = subject.ifBlank { "（无主题）" },
                                body = bodyWithSignature(),
                                inReplyTo = inReplyTo,
                            )
                            onDone("已发送")
                        } catch (e: Exception) {
                            err = friendly(e)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "发送中…" else "发送") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !busy && accId > 0,
                    onClick = {
                        busy = true
                        err = ""
                        scope.launch {
                            try {
                                api.mailSaveDraft(session.token, accId, to, subject, bodyWithSignature())
                                onDone("已保存草稿（网页版邮箱可继续编辑）")
                            } catch (e: Exception) {
                                err = friendly(e)
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("存草稿", fontSize = 12.sp) }
                TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 账号签名编辑：后端只存不拼，写信时由客户端追加（见 `bodyWithSignature`）。 */
@Composable
private fun MailSignatureDialog(
    session: Session,
    account: MailAccountItem,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var text by remember(account.id) { mutableStateOf(account.signature) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    KbDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("邮箱签名 · ${account.email}") },
        text = {
            Column {
                Text("写信 / 存草稿时自动追加到正文末尾（只对这个账号生效）", fontSize = 10.5.sp, color = KbTextSub)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(6.dp))
                        .padding(8.dp),
                ) {
                    if (text.isEmpty()) {
                        Text("例如：— 张三 / 产品部", fontSize = 11.sp, color = KbTextSub.copy(alpha = 0.7f))
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { if (it.length <= 2000) text = it },
                        textStyle = TextStyle(fontSize = 11.5.sp, color = KbTextMain),
                        cursorBrush = SolidColor(KbPrimary),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text("${text.length} / 2000", fontSize = 10.sp, color = KbTextSub)
                if (err.isNotEmpty()) Text(err, fontSize = 11.sp, color = KbTileRose.second)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                err = ""
                scope.launch {
                    try {
                        api.mailSaveSignature(session.token, account.id, text)
                        onSaved()
                    } catch (e: Exception) {
                        err = e.message ?: "保存失败"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit, placeholder: String) {
    Column {
        Text(label, fontSize = 10.5.sp, color = KbTextSub)
        Box(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, fontSize = 11.sp, color = KbTextSub.copy(alpha = 0.7f))
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = TextStyle(fontSize = 11.sp, color = KbTextMain),
                cursorBrush = SolidColor(KbPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}