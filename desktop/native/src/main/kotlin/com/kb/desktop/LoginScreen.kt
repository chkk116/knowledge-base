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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

// 滑块验证码几何（与后端/登录页一致）：基准图 320×160，拼块 45×45
private const val CAP_BASE_W = 320
private const val CAP_BASE_H = 160
private const val CAP_PIECE = 45
private const val CAP_HANDLE = 40
private val CAP_PIECE_MAX = (CAP_BASE_W - CAP_PIECE).toFloat()

// 登录页配色（对齐飞书/钉钉桌面端：品牌渐变 + 白底表单）
// 黛青 + 月白 + 一点金：书院气，避开「AI 紫」
private val BrandStart = Color(0xFF1E3A3C)   // 深黛
private val BrandEnd = Color(0xFF2E5C55)     // 黛青
private val Accent = Color(0xFF2F6F63)       // 松绿（按钮/链接/聚焦）
private val Gold = Color(0xFFC9A96A)         // 点缀金
private val PageBg = Color(0xFFF7F5EF)       // 月白
private val FieldFill = Color(0xFFF8F7F2)
private val FieldBorder = Color(0xFFE5E1D6)
private val Placeholder = Color(0xFFA7A69D)
private val InkBtn = Color(0xFF26292F)      // 验证码滑块把手（深色）
private val ErrRed = Color(0xFFDC2626)
private val LinkColor = Accent

/**
 * 统一的点击入口：依赖版本对齐（foundation / material3 同为 1.13.0-beta02）后，
 * material3 提供的 LocalIndication 已是 IndicationNodeFactory，标准 clickable 可以直接用。
 * 保留这个函数只是为了让登录页各处调用点保持一致的 enabled 写法。
 */
@Composable
private fun Modifier.kbClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(enabled = enabled, onClick = onClick)

@Composable
fun LoginScreen(initialServer: String, onLogin: (Session) -> Unit) {
    val server = initialServer.trim() // 界面不再暴露服务器地址：从配置/环境变量来
    var username by remember { mutableStateOf(ConfigStore.read()["username"] ?: "") }
    var password by remember { mutableStateOf("") }
    var showPwd by remember { mutableStateOf(false) }
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
                // 先校验 server 字符串合法（URI 异常即提示）
                ApiClient(server)
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
            .background(PageBg)
            .onPreviewKeyEvent { e ->
                // 登录页任意处按 Enter 即提交（验证码浮层开着时不抢）
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && captcha == null && !busy) {
                    tryLogin()
                    true
                } else false
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 820.dp
            val narrow = !wide

            // 表单内容（宽屏放右侧白底区，窄屏放居中白卡里）
            val fields: @Composable ColumnScope.() -> Unit = {
                if (narrow) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                                .background(Brush.linearGradient(listOf(BrandStart, BrandEnd))),
                            contentAlignment = Alignment.Center,
                        ) { BrandGlyph(Color.White, Modifier.size(22.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Text("知识库", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
                    }
                    Spacer(Modifier.height(22.dp))
                }

                Text("欢迎回来", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
                Spacer(Modifier.height(8.dp))
                Text("登录知识库，继续整理与查阅", fontSize = 13.sp, color = KbTextSub)
                Spacer(Modifier.height(26.dp))

                LoginField(
                    value = username,
                    onChange = { username = it; error = "" },
                    hint = "请输入用户名",
                    focusRequester = userFocus,
                    leading = { FieldGlyph(FieldIconKind.USER, Placeholder, Modifier.size(17.dp)) },
                )
                Spacer(Modifier.height(14.dp))
                LoginField(
                    value = password,
                    onChange = { password = it; error = "" },
                    hint = "请输入密码",
                    obscure = !showPwd,
                    leading = { FieldGlyph(FieldIconKind.LOCK, Placeholder, Modifier.size(17.dp)) },
                    trailing = {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape)
                                .kbClickable { showPwd = !showPwd },
                            contentAlignment = Alignment.Center,
                        ) { EyeGlyph(showPwd, if (showPwd) Accent else Placeholder, Modifier.size(18.dp)) }
                    },
                )

                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "忘记密码？", fontSize = 12.sp, color = LinkColor,
                        modifier = Modifier.kbClickable { openWeb(server, "/reset-password") },
                    )
                }
                Spacer(Modifier.height(14.dp))

                // 主按钮：品牌渐变（大厂桌面端常见）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Brush.horizontalGradient(listOf(BrandStart, BrandEnd)))
                        .alpha(if (busy || capBusy) 0.55f else 1f)
                        .kbClickable(enabled = !busy && !capBusy) { tryLogin() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (busy) "连接中…" else "登 录",
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    )
                }

                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(error, color = ErrRed, fontSize = 12.sp)
                }

                Spacer(Modifier.height(22.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("登录即表示同意", fontSize = 11.5.sp, color = Placeholder)
                    Text(
                        "《隐私政策》", fontSize = 11.5.sp, color = LinkColor,
                        modifier = Modifier.kbClickable { openWeb(server, "/privacy") },
                    )
                    Text("与", fontSize = 11.5.sp, color = Placeholder)
                    Text(
                        "《免责声明》", fontSize = 11.5.sp, color = LinkColor,
                        modifier = Modifier.kbClickable { openWeb(server, "/disclaimer") },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("v${AppInfo.versionName} · 桌面端", fontSize = 11.sp, color = Placeholder)
            }

            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    BrandPanel(Modifier.weight(0.46f).fillMaxHeight())
                    Box(
                        Modifier.weight(0.54f).fillMaxHeight().background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(Modifier.width(376.dp), content = fields)
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier
                            .width(400.dp)
                            .shadow(18.dp, RoundedCornerShape(20.dp), clip = false)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White)
                            .padding(horizontal = 34.dp, vertical = 32.dp),
                        content = fields,
                    )
                }
            }
        }

        // 验证码浮层（安全验证卡：头部标题 + 关闭，舞台右上角换一张）
        if (captcha != null) {
            val scrimInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                Modifier.fillMaxSize().background(Color(0x66000000))
                    .clickable(interactionSource = scrimInteraction, indication = null) {},
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(1.dp, FieldBorder, RoundedCornerShape(16.dp))
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
                                .kbClickable(enabled = !capBusy) { closeCaptcha() },
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
                                .kbClickable(enabled = !capBusy) { scope.launch { loadCaptcha() } },
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

/** 左侧品牌区：品牌渐变 + 产品名 + 卖点（对齐飞书/钉钉桌面登录的左栏） */
@Composable
private fun BrandPanel(modifier: Modifier) {
    Box(modifier.background(Brush.linearGradient(listOf(BrandStart, BrandEnd)))) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = 0.07f), radius = size.minDimension * 0.62f,
                center = Offset(size.width * 0.92f, size.height * 0.08f))
            drawCircle(Color.White.copy(alpha = 0.05f), radius = size.minDimension * 0.46f,
                center = Offset(size.width * 0.02f, size.height * 0.94f))
            drawCircle(Color.White.copy(alpha = 0.045f), radius = size.minDimension * 0.22f,
                center = Offset(size.width * 0.74f, size.height * 0.78f))
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 44.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(13.dp))
                        .background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) { BrandGlyph(Color.White, Modifier.size(26.dp)) }
                Spacer(Modifier.width(12.dp))
                Text("知识库", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(Modifier.weight(1f))
            Box(Modifier.width(34.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Gold))
            Spacer(Modifier.height(18.dp))
            Text(
                "让知识有序沉淀", fontSize = 32.sp, fontWeight = FontWeight.Bold,
                color = Color.White, lineHeight = 42.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text("一处检索，随时协作，多端同步", fontSize = 15.sp, color = Color.White.copy(alpha = 0.82f))
            Spacer(Modifier.height(30.dp))
            listOf(
                "统一检索 · 一处找到所有资料",
                "多人协作 · 实时同步编辑",
                "多端同步 · 桌面与手机无缝衔接",
            ).forEach {
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.75f)))
                    Spacer(Modifier.width(10.dp))
                    Text(it, fontSize = 13.5.sp, color = Color.White.copy(alpha = 0.9f))
                }
            }
        }
    }
}

private enum class FieldIconKind { USER, LOCK }

/** 输入框前置线性图标（自绘，免引入 material-icons-extended） */
@Composable
private fun FieldGlyph(kind: FieldIconKind, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val s = minOf(w, h)
        val stroke = Stroke(width = s * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            FieldIconKind.USER -> {
                drawCircle(tint, radius = s * 0.2f, center = Offset(w / 2, h * 0.33f), style = stroke)
                val p = Path()
                p.moveTo(w * 0.2f, h * 0.87f)
                p.quadraticBezierTo(w / 2, h * 0.52f, w * 0.8f, h * 0.87f)
                drawPath(p, tint, style = stroke)
            }
            FieldIconKind.LOCK -> {
                drawRoundRect(
                    tint, topLeft = Offset(w * 0.22f, h * 0.46f),
                    size = Size(w * 0.56f, h * 0.4f),
                    cornerRadius = CornerRadius(s * 0.1f), style = stroke,
                )
                val p = Path()
                p.moveTo(w * 0.34f, h * 0.46f)
                p.lineTo(w * 0.34f, h * 0.34f)
                p.quadraticBezierTo(w / 2, h * 0.12f, w * 0.66f, h * 0.34f)
                p.lineTo(w * 0.66f, h * 0.46f)
                drawPath(p, tint, style = stroke)
            }
        }
    }
}

/** 密码可见性眼睛（显示时无斜杠，隐藏时加一道斜杠） */
@Composable
private fun EyeGlyph(visible: Boolean, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = Path()
        p.moveTo(0f, h / 2)
        p.quadraticBezierTo(w / 2, 0f, w, h / 2)
        p.quadraticBezierTo(w / 2, h, 0f, h / 2)
        drawPath(p, tint, style = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(tint, radius = w * 0.13f, center = Offset(w / 2, h / 2))
        if (!visible) {
            drawLine(tint, Offset(w * 0.14f, h * 0.86f), Offset(w * 0.86f, h * 0.14f), w * 0.08f, StrokeCap.Round)
        }
    }
}

/** 大厂式输入框：浅灰底、圆角、聚焦品牌描边、前后可带图标 */
@Composable
private fun LoginField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    obscure: Boolean = false,
    focusRequester: FocusRequester? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, color = KbTextMain),
        visualTransformation = if (obscure) PasswordVisualTransformation() else VisualTransformation.None,
        cursorBrush = SolidColor(Accent),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(11.dp))
            .background(FieldFill)
            .border(1.dp, if (focused) Accent else FieldBorder, RoundedCornerShape(11.dp)),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, fontSize = 13.5.sp, color = Placeholder)
                    inner()
                }
                if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
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
                color = Placeholder,
                modifier = Modifier
                    .align(Alignment.Center)
                    .alpha(1f - 0.85f * (pieceLeft / CAP_PIECE_MAX)),
            )
            Box(
                Modifier
                    .offset { IntOffset((pieceLeft / CAP_PIECE_MAX * (CAP_BASE_W - CAP_HANDLE)).toInt(), 0) }
                    .size(CAP_HANDLE.dp, 26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (pieceLeft >= CAP_PIECE_MAX) Accent else InkBtn)
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

/** 用系统浏览器打开站内页面（忘记密码 / 隐私 / 免责） */
private fun openWeb(server: String, path: String) {
    runCatching { Desktop.getDesktop().browse(URI(server.trimEnd('/') + path)) }
}
