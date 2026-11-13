package com.kb.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull


/** 侧栏高亮底/内容面板等常量来自 Theme.kt；这里是聊天面板局部色。 */
private val ChatRailBg = Color(0xFFFAFBFC)
private val ChatCodeBg = Color(0xFFF5F6F8)
private val ChatLinkBlue = Color(0xFF3B6FF6)
private val ChatChipBg = Color(0xFFF3F6FD)
private val ChatReasonBg = Color(0xFFF8F9FB)
private val ChatErrRed = Color(0xFFDC2626)

/** 一条渲染中的消息：流式期间 content/reasoning 递增，结束后补 sources/qaId。 */
private data class ChatMsg(
    val role: String,
    val content: String = "",
    val mode: String = "kb",
    val sources: List<AiSource> = emptyList(),
    val reasoning: String = "",
    val streaming: Boolean = false,
    val qaId: Long? = null,
    val fbRating: Int = 0,   // 1=赞 -1=踩 0=无（仅本会话新产生的回答可评价，历史消息没有 qaId）
    val images: List<String> = emptyList(),   // 用户随问附带的图片 dataURL（仅当次可见，后端不落历史）
)

/** 一条待发送的图片附件（本地已压缩成 dataURL）。 */
private data class Att(val name: String, val dataUrl: String)

/**
 * AI 问答面板：左侧会话列表 + 右侧流式对话。
 * 口径与 Web 端 AiChat.vue / api/ai.js 一致：POST /api/ai/ask/stream 逐段读
 * {"delta"}/{"reasoning"}，done 事件带 qaId/sources/conversationId；用户「停止」断开连接、
 * 已生成内容保留；当前会话为空时先建会话（标题取问题前 24 字）再提问。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChatPanel(session: Session, onOpenDoc: (Long) -> Unit) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()

    var convs by remember { mutableStateOf<List<AiConversationItem>>(emptyList()) }
    var convLoading by remember { mutableStateOf(true) }
    var convError by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<AiModelOption>>(emptyList()) }
    var selModel by remember { mutableStateOf<AiModelOption?>(null) }
    var currentConv by remember { mutableStateOf<AiConversationItem?>(null) }
    var msgs by remember { mutableStateOf<List<ChatMsg>>(emptyList()) }
    var historyLoading by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("kb") }
    var input by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf(false) }
    val streamHandle = remember { mutableStateOf<ChatStream?>(null) }
    // 会话重命名：rail 行内编辑
    var editingConvId by remember { mutableStateOf<Long?>(null) }
    var renameInput by remember { mutableStateOf("") }
    // 图片附件（vision）：本地压缩成 dataURL，随问发送
    var atts by remember { mutableStateOf<List<Att>>(emptyList()) }
    var attachError by remember { mutableStateOf<String?>(null) }

    // 面板销毁（切菜单/退出登录）时断开还在生成的连接，避免后台空转
    DisposableEffect(Unit) { onDispose { streamHandle.value?.abort() } }

    val reloadConvs: () -> Unit = {
        scope.launch {
            convLoading = true
            try {
                convs = api.aiConversations(session.token)
                convError = null
            } catch (e: Exception) {
                convError = e.message ?: "加载失败"
            }
            convLoading = false
        }
    }

    LaunchedEffect(Unit) {
        reloadConvs()
        // 模型列表失败不阻塞：下拉只显示「默认模型」
        try { models = api.aiModels(session.token) } catch (_: Exception) {}
    }

    val openConv: (AiConversationItem) -> Unit = { c ->
        if (!streaming) {
            currentConv = c
            msgs = emptyList()
            historyLoading = true
            scope.launch {
                try {
                    msgs = api.aiMessages(session.token, c.id).map { m ->
                        ChatMsg(role = m.role, content = m.content, mode = m.mode, sources = m.sources)
                    }
                } catch (e: Exception) {
                    msgs = listOf(ChatMsg(role = "ai", content = "历史消息加载失败：${e.message ?: "网络错误"}"))
                }
                historyLoading = false
            }
        }
    }

    val deleteConv: (AiConversationItem) -> Unit = { c ->
        if (!streaming) {
            scope.launch {
                try {
                    api.aiDeleteConversation(session.token, c.id)
                    if (currentConv?.id == c.id) { currentConv = null; msgs = emptyList() }
                    convs = convs.filterNot { it.id == c.id }
                } catch (e: Exception) {
                    convError = e.message ?: "删除失败"
                }
            }
        }
    }

    val newConv: () -> Unit = {
        if (!streaming) {
            scope.launch {
                try {
                    currentConv = api.aiCreateConversation(session.token, "新对话")
                    convs = api.aiConversations(session.token)
                    msgs = emptyList()
                } catch (e: Exception) {
                    convError = e.message ?: "创建失败"
                }
            }
        }
    }

    val startRename: (AiConversationItem) -> Unit = { c ->
        editingConvId = c.id
        renameInput = c.title
    }

    val submitRename: () -> Unit = {
        val id = editingConvId
        if (id != null) {
            val t = renameInput.trim()
            editingConvId = null
            if (t.isNotEmpty()) {
                scope.launch {
                    try {
                        val c = api.aiRenameConversation(session.token, id, t)
                        convs = convs.map { if (it.id == id) c else it }
                        if (currentConv?.id == id) currentConv = c
                    } catch (e: Exception) {
                        convError = e.message ?: "重命名失败"
                    }
                }
            }
        }
    }

    val cancelRename: () -> Unit = { editingConvId = null }

    // 赞/踩：1=赞 -1=踩 0=取消（与 Web 端同语义）；同一个键再点一次即取消，失败不回滚 UI
    val rateMsg: (ChatMsg, Int) -> Unit = { m, rating ->
        val qaId = m.qaId
        if (qaId != null) {
            val next = if (m.fbRating == rating) 0 else rating
            msgs = msgs.map { mm -> if (mm.role == "ai" && mm.qaId == m.qaId) mm.copy(fbRating = next) else mm }
            scope.launch {
                try {
                    api.aiFeedback(session.token, qaId, next)
                } catch (_: Exception) {
                    // 评价是轻量行为：失败不回滚，下次进会话以服务端为准（历史消息不带评价，可重新点）
                }
            }
        }
    }

    // 图片附件：原生文件对话框多选（Windows 不认 FilenameFilter，按可解码性过滤），本地压缩后入列
    // 注意：LocalAwtWindow.current 只能在组合期读，先捕获成普通变量再进点击回调
    val awtFrame = LocalAwtWindow.current as? Frame
    val pickImages: () -> Unit = {
        if (awtFrame != null) {
            val fd = FileDialog(awtFrame, "选择图片（最多 4 张）", FileDialog.LOAD)
            fd.isMultipleMode = true
            fd.isVisible = true   // 模态阻塞至用户确认/取消
            val files = fd.files
            attachError = when {
                files.isEmpty() -> null
                atts.size >= 4 -> "最多 4 张图片"
                else -> {
                    var skipped = 0
                    val picked = ArrayList<Att>()
                    for (f in files.take(4 - atts.size)) {
                        val du = compressImageFile(f.absolutePath)
                        if (du != null) picked.add(Att(f.name, du)) else skipped++
                    }
                    if (files.size > 4 - atts.size) skipped += files.size - (4 - atts.size)
                    atts = atts + picked
                    if (skipped > 0) "已忽略 $skipped 个无法识别的文件" else null
                }
            }
        }
    }

    val send: () -> Unit = {
        val q = input.trim()
        // 纯图无文字时按 Web 端口径自动补提问语
        val qText = q.ifBlank { if (atts.isNotEmpty()) "请识别并说明这些图片的内容" else "" }
        if (!streaming && qText.isNotEmpty()) {
            val images = atts.map { it.dataUrl }
            input = ""
            atts = emptyList()
            attachError = null
            streaming = true   // 先置位再起协程，双击不会发两问
            scope.launch {
                try {
                    var conv = currentConv
                    if (conv == null) {
                        conv = api.aiCreateConversation(session.token, qText.take(24).ifBlank { "新对话" })
                        currentConv = conv
                        try { convs = api.aiConversations(session.token) } catch (_: Exception) {}
                    }
                    val convId = conv.id
                    msgs = msgs + ChatMsg(role = "user", content = q, mode = mode, images = images)
                    val aiIdx = msgs.size
                    msgs = msgs + ChatMsg(role = "ai", mode = mode, streaming = true)

                    val handle = api.askStreamStart(
                        token = session.token,
                        question = qText,
                        conversationId = convId,
                        mode = mode,
                        modelId = selModel?.takeIf { it.scope == "system" }?.id,
                        userModelId = selModel?.takeIf { it.scope == "user" }?.id,
                        images = images,
                    )
                    streamHandle.value = handle

                    var result: AiAskResult? = null
                    var pendingEvent = "message"
                    val datas = ArrayList<String>()
                    fun dispatch() {
                        val json = datas.joinToString("\n")
                        val ev = pendingEvent
                        datas.clear()
                        pendingEvent = "message"
                        val payload = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
                        when (ev) {
                            "done" -> result = AiAskResult(
                                conversationId = payload.jLong("conversationId") ?: 0L,
                                title = payload.jStr("title").orEmpty(),
                                answer = payload.jStr("answer").orEmpty(),
                                qaId = payload.jLong("qaId"),
                                sources = parseSourceList(payload["sources"]),
                                modelName = payload.jStr("modelName"),
                                reasoning = payload.jStr("reasoning"),
                            )
                            "error" -> throw ApiError(payload.jStr("error") ?: "AI 生成中断，请重试")
                            else -> {
                                val delta = payload.jStr("delta")
                                val rsn = payload.jStr("reasoning")
                                if (!delta.isNullOrEmpty() || !rsn.isNullOrEmpty()) {
                                    msgs = msgs.mapIndexed { i, m ->
                                        if (i == aiIdx) {
                                            m.copy(content = m.content + (delta ?: ""), reasoning = m.reasoning + (rsn ?: ""))
                                        } else m
                                    }
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.IO) {
                        while (true) {
                            val line = handle.nextLine() ?: break
                            when {
                                line.startsWith("event:") -> pendingEvent = line.substring(6).trim()
                                line.startsWith("data:") -> datas.add(line.substring(5).trim())
                                line.isEmpty() && datas.isNotEmpty() -> dispatch()
                            }
                        }
                        if (datas.isNotEmpty()) dispatch()
                    }

                    val r = result
                    msgs = msgs.mapIndexed { i, m ->
                        if (i == aiIdx) m.copy(
                            streaming = false,
                            content = if (m.content.isBlank() && r != null) r.answer else m.content,
                            sources = r?.sources ?: m.sources,
                            reasoning = r?.reasoning ?: m.reasoning,
                            qaId = r?.qaId ?: m.qaId,
                        ) else m
                    }
                    if (r != null && r.title.isNotBlank()) {
                        currentConv = currentConv?.copy(title = r.title)
                        convs = convs.map { if (it.id == r.conversationId) it.copy(title = r.title) else it }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 出错写进气泡，保留已生成内容（口径同 Web 端）
                    val at = msgs.indexOfFirst { it.streaming }
                    if (at >= 0) {
                        val m = msgs[at]
                        val prefix = if (m.content.isBlank()) "" else "${m.content}\n\n"
                        msgs = msgs.mapIndexed { i, mm ->
                            if (i == at) mm.copy(streaming = false, content = "$prefix（出错：${e.message ?: "网络错误"}）") else mm
                        }
                    }
                } finally {
                    streaming = false
                    streamHandle.value?.close()
                    streamHandle.value = null
                }
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.scrollToItem(msgs.size - 1)
    }
    // 流式内容增长时贴底跟随（用户上翻则不打扰）
    LaunchedEffect(msgs.lastOrNull()?.content?.length, msgs.lastOrNull()?.reasoning?.length) {
        if (msgs.isNotEmpty()) {
            val near = (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= msgs.size - 2
            if (near) listState.scrollToItem(msgs.size - 1)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showRail = maxWidth >= 620.dp
        Row(Modifier.fillMaxSize()) {
            if (showRail) {
                ConvRail(
                    convs = convs,
                    loading = convLoading,
                    error = convError,
                    currentId = currentConv?.id,
                    onNew = newConv,
                    onOpen = openConv,
                    onDelete = deleteConv,
                    onRetry = reloadConvs,
                    editingId = editingConvId,
                    renameText = renameInput,
                    onRenameText = { renameInput = it },
                    onStartRename = startRename,
                    onSubmitRename = submitRename,
                    onCancelRename = cancelRename,
                )
                Box(Modifier.width(1.dp).fillMaxHeight().background(KbBorder))
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModePills(mode) { mode = it }
                    Spacer(Modifier.weight(1f))
                    ModelMenu(selModel, models) { selModel = it }
                }
                HorizontalDivider()
                Box(Modifier.weight(1f)) {
                    when {
                        historyLoading -> ChatHint("加载中…")
                        msgs.isEmpty() && currentConv == null -> ChatEmpty()
                        msgs.isEmpty() -> ChatHint("还没有消息，开始提问吧")
                        else -> LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(msgs) { _, m -> ChatMsgRow(m, onOpenDoc = onOpenDoc, rateMsg = rateMsg) }
                        }
                    }
                }
                HorizontalDivider()
                // 附件条：已选图片缩略图 + 移除；选择失败原因（attachError）也在这行提示
                if (atts.isNotEmpty() || attachError != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (attachError != null) {
                            Text(attachError.orEmpty(), fontSize = 11.sp, color = ChatErrRed)
                        }
                        for (a in atts) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ChatChipBg)
                                    .border(1.dp, Color(0xFFE2E6EE), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 5.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AttThumb(a.dataUrl, 26.dp)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    a.name,
                                    fontSize = 10.5.sp,
                                    color = KbTextMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 90.dp),
                                )
                                Text(
                                    " ✕",
                                    fontSize = 10.sp,
                                    color = KbTextSub,
                                    modifier = Modifier.clickable { atts = atts.filterNot { it == a } },
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = pickImages,
                        enabled = !streaming,
                        modifier = Modifier.height(38.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = KbTextMain),
                    ) { Text("📎", fontSize = 15.sp) }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                            val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
                            if (enter && !e.isShiftPressed && e.type == KeyEventType.KeyDown) {
                                send()
                                true
                            } else false
                        },
                        placeholder = {
                            Text(
                                if (atts.isEmpty()) "输入问题，Enter 发送，Shift+Enter 换行" else "输入问题（可留空，Enter 直接看图）",
                                fontSize = 12.sp,
                                color = KbTextSub,
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                        minLines = 1,
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = KbPrimary,
                            unfocusedBorderColor = KbBorder,
                        ),
                    )
                    if (streaming) {
                        Button(
                            onClick = { streamHandle.value?.abort() },
                            modifier = Modifier.height(38.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64748B), contentColor = Color.White),
                        ) { Text("停止", fontSize = 12.5.sp) }
                    } else {
                        Button(
                            onClick = send,
                            enabled = input.isNotBlank() || atts.isNotEmpty(),
                            modifier = Modifier.height(38.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = KbPrimary,
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFFC7CBDA),
                                disabledContentColor = Color.White,
                            ),
                        ) { Text("发送", fontSize = 12.5.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMsgRow(m: ChatMsg, onOpenDoc: (Long) -> Unit, rateMsg: (ChatMsg, Int) -> Unit) {
    if (m.role == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(Modifier.widthIn(max = 460.dp), horizontalAlignment = Alignment.End) {
                if (m.images.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (du in m.images.take(4)) AttThumb(du, 84.dp)
                    }
                    if (m.content.isNotBlank()) Spacer(Modifier.height(6.dp))
                }
                if (m.content.isNotBlank()) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(KbPrimary)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(m.content, fontSize = 13.sp, lineHeight = 20.sp, color = Color.White)
                    }
                }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(KbIconGradient(KbTileGreen.first, KbTileGreen.second)),
                contentAlignment = Alignment.Center,
            ) { MenuGlyph(MenuKind.CHAT, Color.White, Modifier.size(14.dp)) }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                if (m.reasoning.isNotBlank()) ReasoningBox(m.reasoning)
                if (m.content.isBlank() && m.streaming) {
                    Text(
                        if (m.reasoning.isNotBlank()) "生成中…" else "思考中…",
                        fontSize = 12.5.sp,
                        color = KbTextSub,
                    )
                } else if (m.content.isNotBlank()) {
                    MdText(m.content)
                }
                if (m.sources.isNotEmpty()) SourcesRow(m.sources, onOpenDoc)
                if (m.qaId != null && !m.streaming) FeedbackRow(m, rateMsg)
            }
        }
    }
}

/** 图片缩略图（dataURL 解码；解码失败显示占位）。附件条与用户气泡共用。 */
@Composable
private fun AttThumb(dataUrl: String, size: Dp) {
    val bmp = remember(dataUrl) { decodeDataUrlImage(dataUrl) }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(ChatCodeBg),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text("图", fontSize = 9.sp, color = KbTextSub)
        }
    }
}

@Composable
private fun ReasoningBox(reasoning: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(
            Modifier.clickable { open = !open }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (open) "▾" else "▸", fontSize = 10.sp, color = KbTextSub)
            Spacer(Modifier.width(4.dp))
            Text("思考过程（${reasoning.length} 字）", fontSize = 11.sp, color = KbTextSub)
        }
        if (open) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ChatReasonBg)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(reasoning, fontSize = 11.5.sp, lineHeight = 17.sp, color = KbTextSub)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesRow(sources: List<AiSource>, onOpenDoc: (Long) -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        HorizontalDivider(color = Color(0xFFEEF0F4))
        Spacer(Modifier.height(6.dp))
        Text("引用来源（${sources.size}）", fontSize = 10.5.sp, color = KbTextSub)
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (s in sources.take(6)) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ChatChipBg)
                        .border(1.dp, Color(0xFFE2E6EE), RoundedCornerShape(6.dp))
                        .clickable { onOpenDoc(s.id) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        s.title.ifBlank { "文章 #${s.id}" },
                        fontSize = 11.sp,
                        color = ChatLinkBlue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 240.dp),
                    )
                }
            }
        }
        if (sources.size > 6) {
            Spacer(Modifier.height(4.dp))
            Text("其余 ${sources.size - 6} 条来源未展示", fontSize = 10.sp, color = KbTextSub)
        }
    }
}

/** 赞/踩行（本会话新产生的回答才有 qaId；再点同键取消，与 Web 端一致）。 */
@Composable
private fun FeedbackRow(m: ChatMsg, rateMsg: (ChatMsg, Int) -> Unit) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        FbButton("👍 有帮助", m.fbRating == 1) { rateMsg(m, 1) }
        Spacer(Modifier.width(6.dp))
        FbButton("👎 没帮助", m.fbRating == -1) { rateMsg(m, -1) }
        if (m.fbRating == 1) {
            Spacer(Modifier.width(6.dp))
            Text("已赞，再点一次可取消", fontSize = 10.sp, color = KbTextSub)
        } else if (m.fbRating == -1) {
            Spacer(Modifier.width(6.dp))
            Text("已反馈，再点一次可取消", fontSize = 10.sp, color = KbTextSub)
        }
    }
}

@Composable
private fun FbButton(label: String, sel: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 10.5.sp,
        color = if (sel) KbPrimary else KbTextSub,
        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (sel) ChatChipBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun ConvRail(
    convs: List<AiConversationItem>,
    loading: Boolean,
    error: String?,
    currentId: Long?,
    onNew: () -> Unit,
    onOpen: (AiConversationItem) -> Unit,
    onDelete: (AiConversationItem) -> Unit,
    onRetry: () -> Unit,
    editingId: Long?,
    renameText: String,
    onRenameText: (String) -> Unit,
    onStartRename: (AiConversationItem) -> Unit,
    onSubmitRename: () -> Unit,
    onCancelRename: () -> Unit,
) {
    Column(Modifier.width(212.dp).fillMaxHeight().background(ChatRailBg)) {
        Box(Modifier.fillMaxWidth().padding(10.dp)) {
            Button(
                onClick = onNew,
                modifier = Modifier.fillMaxWidth().height(32.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KbPrimary, contentColor = Color.White),
            ) { Text("＋ 新对话", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        }
        HorizontalDivider()
        Box(Modifier.weight(1f)) {
            when {
                loading -> ChatHint("加载中…")
                error != null -> ChatHint("$error（点此重试）", onClick = onRetry)
                convs.isEmpty() -> ChatHint("还没有历史会话")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(convs, key = { _, c -> c.id }) { _, c ->
                        if (editingId == c.id) {
                            RenameRow(
                                text = renameText,
                                onText = onRenameText,
                                onSubmit = onSubmitRename,
                                onCancel = onCancelRename,
                            )
                        } else {
                            ConvRow(
                                c = c,
                                sel = c.id == currentId,
                                onOpen = { onOpen(c) },
                                onDelete = { onDelete(c) },
                                onRename = { onStartRename(c) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 会话行内重命名：小输入框 + 确认/取消，Enter 提交、Esc 取消。 */
@Composable
private fun RenameRow(
    text: String,
    onText: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(KbSidebarSelected)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                when {
                    e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) -> {
                        onSubmit(); true
                    }
                    e.type == KeyEventType.KeyDown && e.key == Key.Escape -> {
                        onCancel(); true
                    }
                    else -> false
                }
            },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 11.5.sp, color = KbTextMain),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = KbPrimary,
                unfocusedBorderColor = KbBorder,
            ),
        )
        TextButton(
            onClick = onSubmit,
            modifier = Modifier.width(26.dp),
            contentPadding = PaddingValues(0.dp),
        ) { Text("✓", fontSize = 12.sp, color = KbPrimary, fontWeight = FontWeight.Bold) }
        TextButton(
            onClick = onCancel,
            modifier = Modifier.width(26.dp),
            contentPadding = PaddingValues(0.dp),
        ) { Text("✕", fontSize = 11.sp, color = KbTextSub) }
    }
}

@Composable
private fun ConvRow(
    c: AiConversationItem,
    sel: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (sel) KbSidebarSelected else Color.Transparent)
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            c.title.ifBlank { "新对话" },
            fontSize = 12.sp,
            color = if (sel) KbPrimary else KbTextMain,
            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onRename,
            modifier = Modifier.width(26.dp),
            contentPadding = PaddingValues(0.dp),
        ) { Text("✎", fontSize = 10.5.sp, color = KbTextSub) }
        TextButton(
            onClick = onDelete,
            modifier = Modifier.width(30.dp),
            contentPadding = PaddingValues(0.dp),
        ) { Text("✕", fontSize = 11.sp, color = KbTextSub) }
    }
}

@Composable
private fun ChatHint(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (onClick != null) ChatLinkBlue else KbTextSub,
            textAlign = TextAlign.Center,
            modifier = (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp),
        )
    }
}

@Composable
private fun ChatEmpty() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(KbIconGradient(KbTileGreen.first, KbTileGreen.second)),
                contentAlignment = Alignment.Center,
            ) { MenuGlyph(MenuKind.CHAT, Color.White, Modifier.size(26.dp)) }
            Spacer(Modifier.height(12.dp))
            Text("向知识库提问", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
            Spacer(Modifier.height(4.dp))
            Text("回答基于库内文章；切「通用」模式可直接作答", fontSize = 11.5.sp, color = KbTextSub)
        }
    }
}

@Composable
private fun ModePills(mode: String, onMode: (String) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF1F2F4))
            .padding(2.dp),
    ) {
        ModePill("知识库", mode == "kb") { onMode("kb") }
        ModePill("通用", mode == "general") { onMode("general") }
    }
}

@Composable
private fun ModePill(label: String, sel: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (sel) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            fontSize = 11.5.sp,
            color = if (sel) KbPrimary else KbTextSub,
            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ModelMenu(sel: AiModelOption?, models: List<AiModelOption>, onSelect: (AiModelOption?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                sel?.name ?: "默认模型",
                fontSize = 11.5.sp,
                color = KbTextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text("▾", fontSize = 9.sp, color = KbTextSub)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        (if (sel == null) "● " else "") + "默认模型（管理员设置）",
                        fontSize = 12.sp,
                        color = if (sel == null) KbPrimary else KbTextMain,
                    )
                },
                onClick = { onSelect(null); open = false },
            )
            for (m in models) {
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (m.id == sel?.id) "● " else "") + m.name + if (m.scope == "user") "（个人）" else "",
                            fontSize = 12.sp,
                            color = if (m.id == sel?.id) KbPrimary else KbTextMain,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = { onSelect(m); open = false },
                )
            }
            if (models.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("暂无可选模型", fontSize = 12.sp, color = KbTextSub) },
                    onClick = {},
                )
            }
        }
    }
}

// ---------- 轻量 markdown 渲染（AI 回答）：块级 = 段落/标题/列表/代码块，行内 = 粗体/行内码/链接 ----------

private sealed interface MdBlock
private data class MdHead(val level: Int, val text: String) : MdBlock
private data class MdPara(val text: String) : MdBlock
private data class MdListItem(val marker: String, val text: String) : MdBlock
private data class MdCode(val text: String) : MdBlock

private fun mdBlocks(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val para = StringBuilder()
    fun flushPara() {
        if (para.isNotBlank()) {
            out.add(MdPara(para.toString().trim()))
            para.clear()
        }
    }
    var inCode = false
    val code = StringBuilder()
    for (raw in src.lines()) {
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                if (code.isNotBlank()) out.add(MdCode(code.toString().trimEnd('\n')))
                code.clear()
            }
            inCode = !inCode
            continue
        }
        if (inCode) {
            code.appendLine(raw)
            continue
        }
        val t = line.trim()
        if (t.isEmpty()) {
            flushPara()
            continue
        }
        val h = Regex("^(#{1,6})\\s+(.*)$").find(t)
        if (h != null) {
            flushPara()
            out.add(MdHead(h.groupValues[1].length, h.groupValues[2].trim()))
            continue
        }
        val li = Regex("^([-*•]|\\d{1,2}[.、)])\\s+(.*)$").find(t)
        if (li != null) {
            flushPara()
            out.add(MdListItem(if (li.groupValues[1][0].isDigit()) li.groupValues[1] else "•", li.groupValues[2].trim()))
            continue
        }
        para.append(t).append('\n')
    }
    if (inCode && code.isNotBlank()) out.add(MdCode(code.toString().trimEnd('\n')))
    flushPara()
    return out
}

/** 行内三态一次匹配：[文本](链接) / **粗体** / `行内码`。 */
private val MD_INLINE = Regex("""\[([^\]]+)\]\(([^)\s]+)\)|\*\*(.+?)\*\*|`([^`]+)`""")

private fun appendInline(builder: AnnotatedString.Builder, text: String) {
    var idx = 0
    for (m in MD_INLINE.findAll(text)) {
        if (m.range.first > idx) builder.append(text.substring(idx, m.range.first))
        when {
            m.groupValues[2].isNotEmpty() -> {
                builder.pushStyle(SpanStyle(color = ChatLinkBlue))
                builder.append(m.groupValues[1])
                builder.pop()
            }
            m.groupValues[3].isNotEmpty() -> {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                builder.append(m.groupValues[3])
                builder.pop()
            }
            m.groupValues[4].isNotEmpty() -> {
                builder.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = KbPrimaryDeep, background = ChatCodeBg))
                builder.append(m.groupValues[4])
                builder.pop()
            }
        }
        idx = m.range.last + 1
    }
    if (idx < text.length) builder.append(text.substring(idx))
}

@Composable
private fun MdText(content: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        for (b in mdBlocks(content)) when (b) {
            is MdHead -> Text(
                b.text,
                fontSize = when (b.level) { 1 -> 15.5.sp; 2 -> 14.5.sp; else -> 13.5.sp },
                fontWeight = FontWeight.Bold,
                color = KbTextMain,
            )
            is MdCode -> Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ChatCodeBg)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(b.text, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 17.sp, color = KbTextMain)
            }
            is MdListItem -> Row(Modifier.fillMaxWidth()) {
                Text("${b.marker} ", fontSize = 13.sp, color = KbTextSub)
                Text(
                    buildAnnotatedString { appendInline(this, b.text) },
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = KbTextMain,
                    modifier = Modifier.weight(1f),
                )
            }
            is MdPara -> Text(
                buildAnnotatedString { appendInline(this, b.text) },
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = KbTextMain,
            )
        }
    }
}

// ---------- 小工具 ----------

/** 本地压缩图片为 dataURL（对齐 Web 端 compressImage：最长边 1280 / JPEG 0.82 / 白底），失败返回 null。 */
internal fun compressImageFile(path: String, maxEdge: Int = 1280, quality: Float = 0.82f): String? = runCatching {
    val src = ImageIO.read(File(path)) ?: return null   // 非图片（Windows 对话框不认扩展名过滤）→ 丢弃
    val scale = minOf(1f, maxEdge.toFloat() / maxOf(src.width, src.height))
    val w = maxOf(1, (src.width * scale).toInt())
    val h = maxOf(1, (src.height * scale).toInt())
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.color = java.awt.Color.WHITE
    g.fillRect(0, 0, w, h)   // PNG 透明区转 JPEG 会变黑，先铺白底（同 Web 端）
    g.drawImage(src, 0, 0, w, h, null)
    g.dispose()
    val jpg = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("jpg").next()
    val p = writer.defaultWriteParam
    p.compressionMode = ImageWriteParam.MODE_EXPLICIT
    p.compressionQuality = quality
    writer.output = MemoryCacheImageOutputStream(jpg)
    writer.write(null, IIOImage(out, null, null), null)
    writer.dispose()
    "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpg.toByteArray())
}.getOrNull()

/** dataURL → Compose ImageBitmap（仅当次渲染用；失败返回 null）。 */
internal fun decodeDataUrlImage(dataUrl: String): ImageBitmap? = runCatching {
    val b64 = dataUrl.substringAfter("base64,", "")
    if (b64.isEmpty()) return null
    loadImageBitmap(ByteArrayInputStream(Base64.getDecoder().decode(b64)))
}.getOrNull()

private fun JsonObject.jStr(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.jLong(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
