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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// 滑块验证码几何（与后端/登录页一致）：基准图 320×160，拼块 45×45，把手宽 44
private const val CAP_BASE_W = 320
private const val CAP_BASE_H = 160
private const val CAP_PIECE = 45
private const val CAP_HANDLE = 44
private val CAP_PIECE_MAX = (CAP_BASE_W - CAP_PIECE).toFloat()

@Composable
fun LoginScreen(initialServer: String, onLogin: (Session) -> Unit) {
    val server = initialServer.trim() // 界面不再暴露服务器地址：从配置/环境变量来
    var username by remember { mutableStateOf("") }
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
        if (busy) return
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

    Box(Modifier.fillMaxSize().background(Color.White)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))

            // 品牌：渐变圆角方块 + 名称
            Box(
                Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(KbIconGradient(KbTileViolet.first, KbTileViolet.second)),
                contentAlignment = Alignment.Center,
            ) { BrandGlyph(Color.White, Modifier.size(32.dp)) }
            Spacer(Modifier.height(12.dp))
            Text("知识库", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
            Text("桌面端", fontSize = 12.sp, color = KbTextSub)
            Spacer(Modifier.height(26.dp))

            Field(username, { username = it }, "用户名")
            Spacer(Modifier.height(10.dp))
            Field(password, { password = it }, "密码", visualTransformation = PasswordVisualTransformation())
            Spacer(Modifier.height(18.dp))

            Button(
                onClick = { tryLogin() },
                enabled = !busy && !capBusy,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KbPrimary),
            ) { Text(if (busy) "连接中…" else "登 录", fontSize = 15.sp) }

            if (error.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = Color(0xFFDC2626), fontSize = 12.sp, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.weight(1f))
        }

        // 验证码浮层
        if (captcha != null) {
            Box(
                Modifier.fillMaxSize().background(Color(0x66000000)).clickable(enabled = false) {},
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
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
                    Spacer(Modifier.height(12.dp))
                    Text(
                        capMsg,
                        fontSize = 12.sp,
                        color = if (capErr) Color(0xFFDC2626) else KbTextSub,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { scope.launch { loadCaptcha() } }, enabled = !capBusy) {
                            Text("换一张", fontSize = 12.sp)
                        }
                        TextButton(onClick = { closeCaptcha() }, enabled = !capBusy) {
                            Text("取消", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    singleLine: Boolean = true,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = fontSize),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color(0xFFF9FAFB),
            disabledContainerColor = Color(0xFFF9FAFB),
            focusedIndicatorColor = KbPrimary,
            unfocusedIndicatorColor = KbBorder,
        ),
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
                .clip(RoundedCornerShape(8.dp))
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

        // 滑条
        Box(
            Modifier
                .size(CAP_BASE_W.dp, 32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFF3F4F6))
                .border(1.dp, KbBorder, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .offset { IntOffset((pieceLeft / CAP_PIECE_MAX * (CAP_BASE_W - CAP_HANDLE)).toInt(), 0) }
                    .size(CAP_HANDLE.dp, 24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (pieceLeft >= CAP_PIECE_MAX) KbPrimary else Color(0xFF9CA3AF))
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
            Text("拖动滑块拼合图片", fontSize = 11.sp, color = Color(0xFF9CA3AF))
        }
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
