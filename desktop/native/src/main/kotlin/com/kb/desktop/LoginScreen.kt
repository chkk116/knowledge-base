package com.kb.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// 滑块验证码几何（与后端/登录页一致）：基准图 320×160，拼块 45×45
private const val CAP_BASE_W = 320
private const val CAP_BASE_H = 160
private const val CAP_PIECE = 45
private const val CAP_HANDLE = 40
private val CAP_PIECE_MAX = (CAP_BASE_W - CAP_PIECE).toFloat()

// 登录页专属配色（Claude 式暖底 + ChatGPT 式墨色主按钮）：
private val LoginBgTop = Color(0xFFF7F5F1)     // 暖奶油底（上）
private val LoginBgBottom = Color(0xFFEFEBE3)  // 暖底（下，轻渐变加深度）
private val FieldFill = Color(0xFFFEFEFD)      // 输入框白
private val FieldBorder = Color(0xFFE5E1D8)    // 暖灰描边
private val InkBtn = Color(0xFF26292F)         // 墨色主按钮
private val InkBtnPressed = Color(0xFF383D45)
private val ErrRed = Color(0xFFDC2626)

@Composable
fun LoginScreen(initialServer: String, onLogin: (Session) -> Unit) {
    val server = initialServer.trim() // 界面不再暴露服务器地址：从配置/环境变量来
    var username by remember { mutableStateOf(ConfigStore.read()["username"] ?: "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf<CaptchaImg?>(null) } // 非 null = 验证码浮层展开
    var pendingU by remember { mutableStateOf("") }
    var pendingP by remember { mutableStateOf("") }
    var pieceLeft by remember { mutableStateOf(0f) }
    var capMsg by remember { mutableStateOf("") }
    var capErr by remember { mutableStateOf(false) }
    var capBusy by remember { mutableStateOf(false) }
    val trace = remember { mutableStateListOf<Pair<Int, Long>>() }
    val scope = rememberCoroutineScope()
    val userFocus = remember { FocusRequester() }

    fun closeCaptcha() {
        captcha = null
        trace.clear()
    }

    suspend fun loadCaptcha() {
        val api = ApiClient(server)
        pieceLeft = 0f
        trace.clear()
        capMsg = "拖动滑块完成拼图"
        capErr = false
        try {
            captcha = api.captcha()
        } catch (e: Exception) {
            captcha = null
            error = e.message ?: "验证码加载失败"
        }
    }

    suspend fun submitCaptcha() {
        val cap = captcha ?: return
        capBusy = true
        capMsg = "验证中…"
        try {
            val api = ApiClient(server)
            api.verifySlide(cap.token, pieceLeft.toInt(), trace.toList())
            capMsg = "验证通过，正在登录…"
            val token = api.login(pendingU, pendingP, cap.token)
            ConfigStore.write(server, pendingU)
            closeCaptcha()
            onLogin(Session(server, token, pendingU))
        } catch (e: Exception) {
            capMsg = e.message ?: "验证失败"
            capErr = true
            capBusy = false
            loadCaptcha()
        }
    }

    fun tryLogin() {
        if (busy || captcha != null) return
        val u = username.trim()
        if (u.isEmpty() || password.isEmpty()) {
            error = "请输入用户名和密码"
            return
        }
        error = ""
        busy = true
        scope.launch {
            try {
                // 先拉取新验证码并弹出（与 Web 端 Login.vue 行为一致）
                ApiClient(server) // 仅校验 server 字符串合法（URI 异常即提示）
            } catch (e: Exception) {
                error = "服务器地址无效"
                busy = false
                return@launch
            }
            pendingU = u
            pendingP = password
            busy = false
            loadCaptcha()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(LoginBgTop, LoginBgBottom)))
            .onPreviewKeyEvent { e ->
                // 登录页任意处按 Enter 即提交（验证码浮层开着时不抢）
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && captcha == null && !busy) {
                    tryLogin()
                    true
                } else false
            },
    ) {
        // 大厂构成：大留白背景 + 居中 400dp 白卡（细暖边 + 软阴影，卡内左对齐编辑式排版）
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(400.dp)
                    .shadow(
                        elevation = 14.dp,
                        shape = RoundedCornerShape(16.dp),
                        clip = false,
                        ambientColor = Color(0x24000000),
                        spotColor = Color(0x2E000000),
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFE9E5DC), RoundedCornerShape(16.dp))
                    .padding(horizontal = 32.dp, vertical = 30.dp),
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(KbIconGradient(KbTileViolet.first, KbTileViolet.second)),
                    contentAlignment = Alignment.Center,
                ) { BrandGlyph(Color.White, Modifier.size(24.dp)) }
                Spacer(Modifier.height(14.dp))
                Text("欢迎回来", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
                Spacer(Modifier.height(5.dp))
                Text("登录知识库，继续整理与查阅", fontSize = 12.5.sp, color = KbTextSub)
                Spacer(Modifier.height(24.dp))

                LoginField(username, { username = it; error = "" }, "用户名", focusRequester = userFocus)
                Spacer(Modifier.height(12.dp))
                LoginField(password, { password = it; error = "" }, "密码", isPassword = true)
                Spacer(Modifier.height(22.dp))

                Button(
                    onClick = { tryLogin() },
                    enabled = !busy && !capBusy,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = InkBtn,
                        disabledContainerColor = InkBtn.copy(alpha = 0.45f),
                        contentColor = Color.White,
                        disabledContentColor = Color.White.copy(alpha = 0.8f),
                    ),
                ) { Text(if (busy) "连接中…" else "登 录", fontSize = 14.5.sp, fontWeight = FontWeight.Medium) }

                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(error, color = ErrRed, fontSize = 12.sp, textAlign = TextAlign.Left)
                }
            }
        }

        // 验证码浮层（安全验证卡：头部标题 + 关闭，舞台右上角换一张）
        if (captcha != null) {
            val scrimInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                Modifier.fillMaxSize().background(Color(0x59000000))
                    .clickable(interactionSource = scrimInteraction, indication = null) {},
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFEDEAE2), RoundedCornerShape(16.dp))
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        Modifier.width(CAP_BASE_W.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("安全验证", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable(enabled = !capBusy) { closeCaptcha() },
                            contentAlignment = Alignment.Center,
                        ) { CloseGlyph(KbTextSub, Modifier.size(11.dp)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Box {
                        CaptchaStage(
                            captcha = captcha!!,
                            pieceLeft = pieceLeft,
                            onDrag = { dx ->
                                if (capBusy) return@CaptchaStage
                                val before = pieceLeft
                                pieceLeft = (pieceLeft + dx).coerceIn(0f, CAP_PIECE_MAX)
                                if (pieceLeft != before) trace.add(pieceLeft.toInt() to System.currentTimeMillis())
                            },
                            onRelease = {
                                if (!capBusy && captcha != null) scope.launch { submitCaptcha() }
                            },
                        )
                        // 换一张：舞台右上角小圆钮
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(0xF2FFFFFF))
                                .border(1.dp, FieldBorder, CircleShape)
                                .clickable(enabled = !capBusy) { scope.launch { loadCaptcha() } },
                            contentAlignment = Alignment.Center,
                        ) { RefreshGlyph(KbTextMain, Modifier.size(13.dp)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        capMsg,
                        fontSize = 12.sp,
                        color = if (capErr) ErrRed else KbTextSub,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(CAP_BASE_W.dp),
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { userFocus.requestFocus() } }
}

/** Claude/ChatGPT 式输入框：无浮动标签，白底暖描边，聚焦描边转品牌色 */
@Composable
private fun LoginField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    isPassword: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, color = KbTextMain),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        cursorBrush = SolidColor(KbPrimary),
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(FieldFill)
            .border(1.dp, if (focused) KbPrimary else FieldBorder, RoundedCornerShape(10.dp)),
        decorationBox = { inner ->
            Box(
                Modifier.padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(hint, fontSize = 13.5.sp, color = Color(0xFFA8A29E))
                }
                inner()
            }
        },
    )
}

@Composable
private fun CaptchaStage(
    captcha: CaptchaImg,
    pieceLeft: Float,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
) {
    val bgBmp = remember(captcha) { runCatching { captcha.bg.decodeToImageBitmap() }.getOrNull() }
    val pieceBmp = remember(captcha) { runCatching { captcha.piece.decodeToImageBitmap() }.getOrNull() }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 舞台：按基准 1:1 呈现（320×160 dp），拖拽坐标即基准坐标
        Box(
            Modifier
                .size(CAP_BASE_W.dp, CAP_BASE_H.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFEDEFF3)),
        ) {
            if (bgBmp != null) {
                Image(bgBmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
            if (pieceBmp != null) {
                Image(
                    pieceBmp,
                    null,
                    Modifier
                        .offset { IntOffset(pieceLeft.toInt(), captcha.pieceY) }
                        .size(CAP_PIECE.dp, CAP_PIECE.dp),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // 滑条：全圆角药丸，墨色把手，提示文字随拖动淡出
        Box(
            Modifier
                .size(CAP_BASE_W.dp, 34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(Color.White)
                .border(1.dp, FieldBorder, RoundedCornerShape(17.dp)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                "拖动滑块完成拼图",
                fontSize = 11.5.sp,
                color = Color(0xFFA8A29E),
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(1f - 0.85f * (pieceLeft / CAP_PIECE_MAX)),
            )
            Box(
                Modifier
                    .offset { IntOffset((pieceLeft / CAP_PIECE_MAX * (CAP_BASE_W - CAP_HANDLE)).toInt(), 0) }
                    .size(CAP_HANDLE.dp, 26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (pieceLeft >= CAP_PIECE_MAX) KbPrimary else InkBtn)
                    .pointerInput(captcha) {
                        detectDragGestures(
                            onDragEnd = { onRelease() },
                            onDragCancel = { onRelease() },
                        ) { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // 把手上三道横线
                Canvas(Modifier.size(14.dp, 10.dp)) {
                    val stroke = Stroke(width = 1.6f, cap = StrokeCap.Round)
                    for (i in 0..2) {
                        val y = size.height * (i + 1) / 4f
                        drawLine(Color.White, Offset(0f, y), Offset(size.width, y), stroke.width, StrokeCap.Round)
                    }
                }
            }
        }
    }
}

/** 关闭 × */
@Composable
private fun CloseGlyph(tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        drawLine(tint, Offset(0f, 0f), Offset(w, w), 1.8f, StrokeCap.Round)
        drawLine(tint, Offset(w, 0f), Offset(0f, w), 1.8f, StrokeCap.Round)
    }
}

/** 换一张 ↻（270° 圆弧 + 箭头三角，顺时针到 9 点钟方向朝上） */
@Composable
private fun RefreshGlyph(tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.width
        val r = s * 0.36f
        val c = s / 2f
        drawArc(
            color = tint,
            startAngle = -90f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(c - r, c - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = Stroke(width = s * 0.14f, cap = StrokeCap.Round),
        )
        // 弧末端在 9 点钟位置（x = c - r），顺时针运动方向朝上 → 箭头朝上
        val ex = c - r
        val path = Path()
        path.moveTo(ex, c - s * 0.24f)
        path.lineTo(ex - s * 0.13f, c + s * 0.02f)
        path.lineTo(ex + s * 0.13f, c + s * 0.02f)
        path.close()
        drawPath(path, tint)
    }
}

/** 品牌图标：三本抽象书块（与 Electron 版 build-icon 设计一致） */
@Composable
private fun BrandGlyph(tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val s = size.width
        val path = Path()
        // 三本书块
        path.moveTo(s * 0.16f, s * 0.62f); path.lineTo(s * 0.42f, s * 0.28f); path.lineTo(s * 0.50f, s * 0.36f)
        path.moveTo(s * 0.34f, s * 0.80f); path.lineTo(s * 0.62f, s * 0.44f); path.lineTo(s * 0.72f, s * 0.54f)
        path.moveTo(s * 0.54f, s * 0.94f); path.lineTo(s * 0.80f, s * 0.62f); path.lineTo(s * 0.92f, s * 0.74f)
        drawPath(path, tint, style = Stroke(width = s * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // 书架线
        drawLine(tint, Offset(s * 0.10f, s * 0.97f), Offset(s * 0.94f, s * 0.97f), s * 0.075f, StrokeCap.Round)
    }
}
