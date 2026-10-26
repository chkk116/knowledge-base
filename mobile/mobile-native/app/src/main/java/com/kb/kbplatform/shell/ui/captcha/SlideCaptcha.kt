/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.captcha

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.ui.theme.KbBlue
import com.kb.kbplatform.shell.ui.theme.KbGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 滑动拼图验证码（Compose 版）。
 *
 * 与 Web 端 SlideCaptcha.vue 完全同源的协议与坐标换算：
 *  - 舞台按 2:1 显示，图片资源固定 320x160 基准，等比铺满不裁切
 *  - 拼块显示位置 = 手柄比例 × (舞台宽 - 拼块显示宽)，纵坐标 = pieceY × 舞台宽/320
 *  - 松手时把显示坐标按 320/舞台宽 换算回服务端基准再上报（否则窄屏必失败）
 *  - 轨迹 {x: 显示坐标, t: 时间戳}，服务端要求 ≥3 点且首尾 ≥120ms
 *  - 拼对后回调 captchaToken，由调用方拿去 POST /auth/login
 */
@Composable
fun SlideCaptcha(
    onVerified: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var captchaToken by remember { mutableStateOf("") }
    var bg by remember { mutableStateOf<Bitmap?>(null) }
    var piece by remember { mutableStateOf<Bitmap?>(null) }
    var pieceY by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf("") }

    var handleX by remember { mutableFloatStateOf(0f) }          // 滑块左偏移 px
    // 舞台真实尺寸（px）：由 onSizeChanged 量出来，绝不推算。
    // Web 端 measure() 也是读 getBoundingClientRect()，同一个理由 —— 容器有内边距时推算值会偏，
    // 偏一点就表现为「拼块比缺口大一圈、怎么拖都对不齐」（2026-10-25 修）。
    var stageW by remember { mutableFloatStateOf(0f) }
    var stageH by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var verified by remember { mutableStateOf(false) }
    var failMsg by remember { mutableStateOf("") }
    var shakeOffset by remember { mutableFloatStateOf(0f) }
    val trajectory = remember { mutableListOf<JSONObject>() }

    val handleWidthPx = with(density) { 44.dp.toPx() }

    suspend fun refreshCaptcha() {
        loading = true
        failMsg = ""
        handleX = 0f
        verified = false
        try {
            val c = KbApi.fetchCaptcha()
            captchaToken = c.token
            bg = c.bg
            piece = c.piece
            pieceY = c.pieceY
            loadError = ""
        } catch (e: Exception) {
            loadError = e.message ?: "验证码加载失败"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { refreshCaptcha() }

    fun doShake() {
        scope.launch {
            for (dx in floatArrayOf(-6f, 6f, -6f, 6f, 0f)) {
                shakeOffset = dx
                delay(55)
            }
            shakeOffset = 0f
        }
    }

    Box(modifier.fillMaxWidth()) {
        // 拼块显示尺寸：宽按舞台宽、高按舞台高分别缩放（与 Web 端 pieceDisplayW/H 同式）。
        // stageW/stageH 为 0（首帧未测量）时全部退化为 0，拼块本来也要等图片加载完才画，无闪烁。
        val pieceW = 45f * stageW / 320f
        val pieceH = 45f * stageH / 160f
        val maxHandle = (stageW - handleWidthPx).coerceAtLeast(1f)

        // 拼块显示左偏移 = 手柄比例 × (舞台宽 - 拼块宽)（与 Web 端 pieceLeft 同式）
        fun pieceLeftFor(h: Float): Float {
            val ratio = (h / maxHandle).coerceIn(0f, 1f)
            return ratio * (stageW - pieceW)
        }

        fun submit() {
            if (checking || verified || dragging) return
            checking = true
            scope.launch {
                try {
                    val scale = if (stageW > 0) 320f / stageW else 1f
                    val userX = (pieceLeftFor(handleX) * scale).roundToInt()
                    val traj = JSONArray()
                    trajectory.forEach { traj.put(it) }
                    KbApi.slideCaptcha(captchaToken, userX, traj)
                    verified = true
                    onVerified(captchaToken)
                } catch (e: Exception) {
                    val msg = e.message ?: "滑动验证未通过，请重新拖动拼图"
                    doShake()
                    refreshCaptcha()   // 失败后旧票据已作废，必须换新图
                    // refreshCaptcha 开头会清空 failMsg，所以提示要写在它之后（审查 r13）
                    failMsg = msg
                } finally {
                    checking = false
                }
            }
        }

        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("安全验证", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("拖动下方滑块，将拼块对齐缺口", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                IconButton(onClick = { scope.launch { refreshCaptcha() } }, enabled = !checking) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            // 舞台
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f)
                    // 量真实像素尺寸：拼块定位、缩放、上报换算全部基于它
                    .onSizeChanged {
                        stageW = it.width.toFloat()
                        stageH = it.height.toFloat()
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .graphicsLayer { translationX = shakeOffset }
            ) {
                val b = bg
                // 位图包装要缓存：每次重组都 asImageBitmap() 会新建对象，拖动时每帧都分配（审查 r14）
                val bgImage = remember(b) { b?.asImageBitmap() }
                if (bgImage != null) {
                    Image(bgImage, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                }
                val p = piece
                val pieceImage = remember(p) { p?.asImageBitmap() }
                if (b != null && pieceImage != null && !verified && !loading) {
                    val left = pieceLeftFor(handleX)
                    // 纵坐标按舞台真实高度缩放（Web 端 pieceTop = pieceY * H / 160，同式）
                    val top = pieceY * stageH / 160f
                    val pw = with(density) { pieceW.toDp() }
                    val ph = with(density) { pieceH.toDp() }
                    Image(
                        pieceImage,
                        null,
                        Modifier
                            .align(Alignment.TopStart)
                            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                            .size(pw, ph)
                            .shadow(2.dp),
                        contentScale = ContentScale.FillBounds
                    )
                }
                if (loading || (bg == null && loadError.isEmpty())) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("加载中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 滑轨
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(44.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(handleX.roundToInt(), 0) }
                        .width(44.dp)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(if (verified) KbGreen else KbBlue)
                        .pointerInput(verified, checking) {
                            detectDragGestures(
                                onDragStart = {
                                    if (!verified && !checking) {
                                        dragging = true
                                        failMsg = ""
                                        trajectory.clear()
                                    }
                                },
                                onDrag = { change, amount ->
                                    if (dragging) {
                                        change.consume()
                                        // amount 是「相对上一次事件」的增量，必须累加到当前值；
                                        // 用固定起点 + 增量只会保留最后一次位移（审查 r13 Critical）
                                        handleX = (handleX + amount.x).coerceIn(0f, maxHandle)
                                        trajectory.add(
                                            JSONObject()
                                                .put("x", pieceLeftFor(handleX))
                                                .put("t", System.currentTimeMillis())
                                        )
                                    }
                                },
                                onDragEnd = {
                                    if (dragging) {
                                        dragging = false
                                        submit()
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (verified) "✓" else "|||", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    verified -> "验证通过"
                    checking -> "校验中…"
                    failMsg.isNotEmpty() -> failMsg
                    loadError.isNotEmpty() -> loadError
                    else -> "拖动滑块完成拼图"
                },
                fontSize = 12.sp,
                color = when {
                    verified -> KbGreen
                    failMsg.isNotEmpty() || loadError.isNotEmpty() -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
