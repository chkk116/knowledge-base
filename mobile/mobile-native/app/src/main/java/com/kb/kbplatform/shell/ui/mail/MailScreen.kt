package com.kb.kbplatform.shell.ui.mail

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.KbChip
import com.kb.kbplatform.shell.ui.common.KbEmpty
import com.kb.kbplatform.shell.ui.common.KbError
import com.kb.kbplatform.shell.ui.common.KbSkeletonPage
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.formatDateTime
import com.kb.kbplatform.shell.ui.theme.KbBlue
import com.kb.kbplatform.shell.ui.theme.KbRed
import com.kb.kbplatform.shell.ui.theme.KbTextDim
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 邮箱（原生）：账号切换 + 收件箱列表 + 阅读 + 已读/删除 + 授权码绑定。
 *
 * 后端能力见 `/api/mail`（MailController）：列表是 **对象** `{items,total,page,size}`，
 * 账号 `email` 已在服务端掩码；详情除列表字段外还带 `textBody/htmlBody/toAddrs`。
 * OAuth（Gmail/Outlook）需要管理员先配 OAuth 应用，这里只做授权码绑定（国内邮箱）。
 */
@Composable
fun MailScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var accId by remember { mutableLongStateOf(0L) }
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var syncing by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var binding by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf<ComposeSeed?>(null) }
    // 收件箱 / 草稿箱：后端 folder 直接对应 IMAP 目录名，草稿写在 DRAFT 里
    var folder by remember { mutableStateOf("INBOX") }
    // 已加载到的页（「加载更多」递增；切账号/搜索/文件夹/刷新时归零）
    var page by remember { mutableIntStateOf(0) }
    // 签名编辑（仅对当前选中账号生效）
    var signing by remember { mutableStateOf(false) }

    LaunchedEffect(tick) {
        runCatching { KbApi.mailAccounts() }
            .onSuccess { accounts = it.objects() }
            .onFailure { if (it.message != null) msg = it.message!! }
    }

    LaunchedEffect(accId, q, folder, tick) {
        loading = true
        err = ""
        page = 0
        try {
            val r = KbApi.mailMessages(
                accountId = accId.takeIf { v -> v > 0 }, folder = folder, q = q, page = 0, size = 30
            )
            items = r.optJSONArray("items")?.objects() ?: emptyList()
            total = r.optInt("total", items.size)
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
            items = emptyList()
        } finally {
            loading = false
        }
    }

    // 写信（含回复）：优先于详情，发送成功回列表
    composing?.let { seed ->
        MailCompose(
            accounts = accounts,
            seed = seed,
            onBack = { composing = null },
            onDone = { m ->
                composing = null
                msg = m
                tick++
            }
        )
        return
    }

    // 详情：全屏阅读（返回即回列表）
    detail?.let { d ->
        MailDetail(
            mail = d,
            onBack = { detail = null },
            onReply = { dd ->
                detail = null
                composing = ComposeSeed(
                    to = dd.optString("fromAddr"),
                    subject = "Re: " + dd.optString("subject"),
                    inReplyTo = dd.optString("messageId").takeIf { it.isNotBlank() }
                )
            },
            onEditDraft = if (folder == "DRAFT") {
                { dd ->
                    detail = null
                    // 草稿的收件人在 toAddrs，正文在 textBody；带上原 id 存回去（/drafts 支持 id 覆盖）
                    composing = ComposeSeed(
                        to = dd.optString("toAddrs"),
                        subject = dd.optString("subject"),
                        inReplyTo = null,
                        body = dd.optString("textBody")
                    )
                }
            } else null,
            onDeleted = {
                detail = null
                items = items.filter { it.optLong("id", -1) != d.optLong("id", -2) }
                msg = "已删除"
            },
            onReadChanged = { id, read ->
                items = items.map { x ->
                    if (x.optLong("id", -1) == id) JSONObject(x.toString()).put("unread", !read) else x
                }
            }
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        val syncAccount = accounts.firstOrNull { it.optLong("id", 0) == accId }
        TopBar(title = "邮箱", onBack = onBack, actions = {
            if (accounts.isNotEmpty()) {
                TextButton(onClick = { composing = ComposeSeed("", "", null) }) { Text("写邮件", fontSize = 13.sp) }
            }
            if (accId > 0) {
                TextButton(enabled = !syncing, onClick = {
                    syncing = true
                    msg = ""
                    scope.launch {
                        try {
                            val r = KbApi.mailSync(accId)
                            msg = "同步完成，新增 ${r.optInt("added", 0)} 封"
                            tick++
                        } catch (e: Exception) {
                            msg = e.message ?: "同步失败"
                        } finally {
                            syncing = false
                        }
                    }
                }) { Text(if (syncing) "同步中…" else "同步", fontSize = 13.sp) }
                TextButton(onClick = { signing = true }) { Text("签名", fontSize = 13.sp) }
            }
            TextButton(onClick = { binding = true }) { Text("绑定", fontSize = 13.sp) }
        })

        if (msg.isNotEmpty()) {
            Text(msg, fontSize = 11.5.sp, color = KbBlue, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        if (accounts.isEmpty()) {
            KbEmpty("📮", "还没有绑定邮箱\n点右上角「绑定」填授权码即可（QQ/163/126 等）")
            return@Column
        }

        // 账号切换：全部 / 单个
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KbChip("全部", selected = accId <= 0, onClick = { accId = 0L })
            accounts.take(3).forEach { a ->
                val id = a.optLong("id", 0)
                KbChip(a.optString("email").ifBlank { "邮箱$id" }, selected = accId == id, onClick = { accId = id })
            }
        }

        // 收件箱 / 草稿箱（草稿是本地 folder=DRAFT，不占 IMAP）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KbChip("收件箱", selected = folder == "INBOX", onClick = { folder = "INBOX" })
            KbChip("草稿箱", selected = folder == "DRAFT", onClick = { folder = "DRAFT" })
            // 已发送：后端发信成功后在本地存 folder=SENT（uid 为负的合成记录）
            KbChip("已发送", selected = folder == "SENT", onClick = { folder = "SENT" })
        }

        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            placeholder = { Text("搜索主题 / 发件人", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )

        val accStatus = accounts.firstOrNull { it.optString("status") != "OK" }
        if (accStatus != null && accStatus.optString("lastError").isNotBlank()) {
            Text(
                "账号异常：" + accStatus.optString("lastError"),
                fontSize = 11.sp, color = KbRed,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        Spacer(Modifier.height(4.dp))
        when {
            loading -> KbSkeletonPage(count = 6)
            err.isNotEmpty() -> KbError("邮件加载失败：$err", onRetry = { tick++ })
            items.isEmpty() -> KbEmpty(
                "📭",
                when {
                    q.isNotBlank() -> "没有匹配「$q」的邮件"
                    folder == "DRAFT" -> "草稿箱是空的\n写邮件时点「存草稿」会存到这里"
                    folder == "SENT" -> "还没有发出过邮件"
                    else -> "收件箱是空的\n点右上角「同步」拉取最新邮件"
                }
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.optLong("id", it.hashCode().toLong()) }) { m ->
                    val unread = m.optBoolean("unread", false)
                    KbCard(Modifier.fillMaxWidth().clickable {
                        detail = m
                        if (unread) {
                            scope.launch {
                                runCatching { KbApi.mailMarkRead(m.optLong("id", 0), true) }
                                items = items.map { x ->
                                    if (x.optLong("id", -1) == m.optLong("id", -2)) JSONObject(x.toString()).put("unread", false) else x
                                }
                            }
                        }
                    }) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (unread) {
                                    Text("●", fontSize = 10.sp, color = KbBlue, modifier = Modifier.padding(end = 6.dp))
                                }
                                Text(
                                    m.optString("fromName").ifBlank { m.optString("fromAddr") },
                                    fontSize = 13.sp,
                                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(formatDateTime(m.optString("sentAt")), fontSize = 10.sp, color = KbTextDim)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                (if (folder == "DRAFT") "【草稿】" else "") +
                                    m.optString("subject").ifBlank { "（无主题）" },
                                fontSize = 14.sp,
                                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1
                            )
                            if (m.optString("snippet").isNotEmpty()) {
                                Text(m.optString("snippet"), fontSize = 11.sp, color = KbTextDim, maxLines = 2)
                            }
                            if (m.optBoolean("hasAttach", false)) {
                                Text("📎 含附件", fontSize = 10.sp, color = KbTextDim)
                            }
                        }
                    }
                }
                if (items.size < total) {
                    item {
                        var loadingMore by remember { mutableStateOf(false) }
                        TextButton(
                            enabled = !loadingMore,
                            onClick = {
                                loadingMore = true
                                scope.launch {
                                    try {
                                        val next = page + 1
                                        val r = KbApi.mailMessages(
                                            accountId = accId.takeIf { v -> v > 0 },
                                            folder = folder, q = q, page = next, size = 30
                                        )
                                        val more = r.optJSONArray("items")?.objects() ?: emptyList()
                                        val known = items.map { it.optLong("id", -1L) }.toSet()
                                        items = items + more.filter { it.optLong("id", -1L) !in known }
                                        page = next
                                    } catch (e: Exception) {
                                        msg = e.message ?: "加载更多失败"
                                    } finally {
                                        loadingMore = false
                                    }
                                }
                            }
                        ) {
                            Text(
                                if (loadingMore) "加载中…" else "加载更多（还有 ${total - items.size} 封）",
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (binding) {
        MailBindDialog(
            onDismiss = { binding = false },
            onBound = {
                binding = false
                msg = "绑定成功，正在同步…"
                tick++
            }
        )
    }

    if (signing && accId > 0) {
        val acc = accounts.firstOrNull { it.optLong("id", 0) == accId }
        MailSignatureDialog(
            accountId = accId,
            current = acc?.optString("signature").orEmpty(),
            onDismiss = { signing = false },
            onSaved = {
                signing = false
                msg = "签名已保存（写信时会自动追加）"
                tick++
            }
        )
    }
}

/** 邮件详情：优先纯文本正文，htmlBody 转纯文本兜底（不渲染远程 HTML，避免外链/追踪像素）。 */
@Composable
private fun MailDetail(
    mail: JSONObject,
    onBack: () -> Unit,
    onReply: (JSONObject) -> Unit,
    onEditDraft: ((JSONObject) -> Unit)? = null,
    onDeleted: () -> Unit,
    onReadChanged: (Long, Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var d by remember(mail) { mutableStateOf(mail) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }

    LaunchedEffect(mail.optLong("id", 0)) {
        try {
            d = KbApi.mailMessage(mail.optLong("id", 0))
        } catch (e: Exception) {
            err = e.message ?: "正文加载失败"
        } finally {
            loading = false
        }
    }

    val body = d.optString("textBody").ifBlank {
        val h = d.optString("htmlBody")
        if (h.isBlank()) "" else android.text.Html
            .fromHtml(h, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "邮件", onBack = onBack, actions = {
            if (onEditDraft != null) {
                TextButton(enabled = !loading && err.isEmpty(), onClick = { onEditDraft(d) }) {
                    Text("编辑草稿", fontSize = 13.sp)
                }
            }
            TextButton(onClick = { onReply(d) }) { Text("回复", fontSize = 13.sp) }
            TextButton(onClick = { confirming = true }) { Text("删除", color = KbRed, fontSize = 13.sp) }
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(d.optString("subject").ifBlank { "（无主题）" }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                (d.optString("fromName").ifBlank { d.optString("fromAddr") }) + "  <" + d.optString("fromAddr") + ">",
                fontSize = 12.sp, color = KbTextDim
            )
            Text("收件人：" + d.optString("toAddrs"), fontSize = 12.sp, color = KbTextDim)
            Text(formatDateTime(d.optString("sentAt")), fontSize = 11.sp, color = KbTextDim)
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            when {
                // 正文加载不是列表形状：这里用文字提示，别拿卡片骨架屏（加载完会整块跳变，还会多出一层横向内边距）
                loading -> Text("正文加载中…", fontSize = 13.sp, color = KbTextDim)
                err.isNotEmpty() -> Text("正文加载失败：$err", fontSize = 13.sp, color = KbRed)
                body.isBlank() -> Text("（这封邮件没有可显示的正文）", fontSize = 13.sp, color = KbTextDim)
                else -> Text(body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            // 附件：后端把 attachments 存成 JSON 字符串，形如 [{"name":"a.pdf","size":123,"url":"/uploads/…"}]
            val atts = runCatching { JSONArray(d.optString("attachments")) }.getOrNull()
            if (atts != null && atts.length() > 0) {
                Spacer(Modifier.height(16.dp))
                Text("附件（${atts.length()}）", fontSize = 12.sp, color = KbTextDim)
                val ctx = androidx.compose.ui.platform.LocalContext.current
                for (i in 0 until atts.length()) {
                    val a = atts.optJSONObject(i) ?: continue
                    val name = a.optString("name").ifBlank { "附件 ${i + 1}" }
                    val url = a.optString("url")
                    Text(
                        "📎 " + name,
                        fontSize = 12.5.sp,
                        color = KbBlue,
                        modifier = Modifier
                            .clickable {
                                if (url.isNotBlank()) {
                                    val full = com.kb.kbplatform.shell.AppConfig.effectiveBaseUrl.trimEnd('/') + url
                                    runCatching {
                                        ctx.startActivity(
                                            android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                setData(android.net.Uri.parse(full))
                                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                }
                            }
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("删除这封邮件？") },
            text = { Text("本地记录会删除，并尽力在服务器上也删除（不可恢复）。") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch {
                        if (runCatching { KbApi.mailDelete(d.optLong("id", 0)) }.isSuccess) onDeleted()
                    }
                }) { Text("删除", color = KbRed) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("取消") } }
        )
    }
}

/** 授权码绑定：选服务商 + 邮箱 + 授权码（后端会真连 IMAP 验证）。 */
@Composable
private fun MailBindDialog(onDismiss: () -> Unit, onBound: () -> Unit) {
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var provider by remember { mutableStateOf("QQ") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { KbApi.mailProviders() }.onSuccess { providers = it.objects() }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("绑定邮箱（授权码）") },
        text = {
            Column {
                if (providers.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        providers.take(4).forEach { p ->
                            KbChip(
                                p.optString("name").ifBlank { p.optString("id") },
                                selected = provider == p.optString("id"),
                                onClick = { provider = p.optString("id") }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("邮箱地址", fontSize = 11.sp) }, singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text("授权码（不是登录密码）", fontSize = 11.sp) }, singleLine = true
                )
                if (err.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(err, fontSize = 11.sp, color = KbRed)
                }
                Spacer(Modifier.height(6.dp))
                Text("在邮箱网页版「设置 → 账户 → IMAP/SMTP」开启后获取授权码。", fontSize = 10.5.sp, color = KbTextDim)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && email.isNotBlank() && code.isNotBlank(), onClick = {
                busy = true
                err = ""
                scope.launch {
                    try {
                        KbApi.mailBindCode(provider, email.trim(), code.trim())
                        onBound()
                    } catch (e: Exception) {
                        err = e.message ?: "绑定失败"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "验证中…" else "绑定") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } }
    )
}

/** 账号签名：后端只存不拼，写信时由客户端追加（见 `bodyWithSignature`）。 */
@Composable
private fun MailSignatureDialog(
    accountId: Long,
    current: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(current) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("邮箱签名") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 2000) text = it },
                    label = { Text("写信时自动追加到正文末尾", fontSize = 11.sp) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("${text.length} / 2000", fontSize = 10.sp, color = KbTextDim)
                if (err.isNotEmpty()) {
                    Text(err, fontSize = 11.sp, color = KbRed)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                err = ""
                scope.launch {
                    try {
                        KbApi.mailSaveSignature(accountId, text)
                        onSaved()
                    } catch (e: Exception) {
                        err = e.message ?: "保存失败"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } }
    )
}

/** 写信种子：回复预填收件人/主题/inReplyTo；编辑草稿再带上正文。 */
data class ComposeSeed(
    val to: String,
    val subject: String,
    val inReplyTo: String?,
    val body: String = ""
)

/**
 * 写邮件 / 回复：POST /api/mail/send（真投递）或 /api/mail/drafts（存草稿）。
 * 后端 `/send`、`/drafts` 与 MailSendService 还在别人的工作区里，未升级时这里会拿到 404/405，
 * 所以错误提示里额外说明「服务端发信可能尚未升级」，避免被当成客户端 bug。
 */
@Composable
private fun MailCompose(
    accounts: List<JSONObject>,
    seed: ComposeSeed,
    onBack: () -> Unit,
    onDone: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var accId by remember { mutableLongStateOf(accounts.firstOrNull()?.optLong("id", 0) ?: 0L) }
    var to by remember { mutableStateOf(seed.to) }
    var subject by remember { mutableStateOf(seed.subject) }
    var body by remember { mutableStateOf(seed.body) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    fun parseTo(): List<String> =
        to.split(',', ';', '，', '；', '\n').map { it.trim() }.filter { it.contains("@") }

    fun bodyWithSignature(): String {
        // 后端不发信时追加签名（只在保存账号签名后由客户端拼），已经包含就不重复拼
        val sig = accounts.firstOrNull { it.optLong("id", 0) == accId }
            ?.optString("signature").orEmpty()
        if (sig.isBlank() || body.contains(sig)) return body
        val b = body.trimEnd()
        return if (b.isEmpty()) sig else b + "\n\n" + sig
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = if (seed.inReplyTo == null) "写邮件" else "回复邮件",
            onBack = onBack,
            actions = {
                TextButton(
                    enabled = !busy && accId > 0 && parseTo().isNotEmpty(),
                    onClick = {
                        busy = true
                        err = ""
                        scope.launch {
                            try {
                                KbApi.mailSend(
                                    accountId = accId,
                                    to = parseTo(),
                                    subject = subject.ifBlank { "（无主题）" },
                                    body = bodyWithSignature(),
                                    inReplyTo = seed.inReplyTo
                                )
                                onDone("已发送")
                            } catch (e: Exception) {
                                val m = e.message ?: "发送失败"
                                err = if (m.startsWith("请求失败(404)") || m.startsWith("请求失败(405)"))
                                    "$m（服务端邮箱发信接口可能尚未升级）" else m
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text(if (busy) "发送中…" else "发送", fontSize = 13.sp) }
            }
        )
        if (err.isNotEmpty()) {
            Text(err, fontSize = 11.5.sp, color = KbRed, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                accounts.forEach { a ->
                    val id = a.optLong("id", 0)
                    KbChip(
                        a.optString("email").ifBlank { "邮箱$id" },
                        selected = accId == id,
                        onClick = { accId = id }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = to, onValueChange = { to = it },
                label = { Text("收件人（多个用逗号分隔）", fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            // 历史收件人补全：取最后一段（逗号后）做关键词，300ms 防抖
            var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
            LaunchedEffect(to) {
                val frag = to.substringAfterLast(',').trim()
                if (frag.length >= 2 && !frag.contains('@')) {
                    kotlinx.coroutines.delay(300)
                    suggestions = runCatching {
                        val arr = KbApi.mailContacts(frag)
                        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                    }.getOrDefault(emptyList())
                } else {
                    suggestions = emptyList()
                }
            }
            if (suggestions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.take(3).forEach { s ->
                        KbChip(s, selected = false, onClick = {
                            val prefix = if (to.contains(',')) to.substringBeforeLast(',') + ", " else ""
                            to = prefix + s + ", "
                            suggestions = emptyList()
                        })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = subject, onValueChange = { subject = it },
                label = { Text("主题", fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = body, onValueChange = { body = it },
                label = { Text("正文", fontSize = 11.sp) },
                minLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy && accId > 0, onClick = {
                    busy = true
                    err = ""
                    scope.launch {
                        try {
                            KbApi.mailSaveDraft(accId, to, subject, bodyWithSignature())
                            onDone("已保存草稿（网页版邮箱可继续编辑）")
                        } catch (e: Exception) {
                            val m = e.message ?: "保存失败"
                            err = if (m.startsWith("请求失败(404)") || m.startsWith("请求失败(405)"))
                                "$m（服务端草稿接口可能尚未升级）" else m
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("存草稿", fontSize = 13.sp) }
            }
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }