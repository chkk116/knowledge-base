package com.kb.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

/**
 * 看板图表（Compose Canvas 自绘，不引图表库）。
 *
 * Web 端看板是三张 ECharts 图：文档状态环形、互动总量柱状、7 日趋势折线。
 * 这里用同样语义的极简版：图形交给 [Canvas]，文字（图例/轴标签/数值）用 Compose 排版，
 * 避免在绘制回调里画文本（Compose Desktop 的 `drawText` 需要 TextMeasurer，跨版本不稳）。
 */

/** 指标卡：图标块 + 标题 + 大数字 + 可选副文案 */
@Composable
internal fun StatTile(
    title: String,
    value: String,
    glyph: String,
    tint: Pair<Color, Color>,
    hint: String = "",
) {
    KbSurface(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(tint.first, tint.second))),
                contentAlignment = Alignment.Center,
            ) { Text(glyph, fontSize = 15.sp, color = Color.White) }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontSize = 11.5.sp, color = KbTextSub)
                Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
                if (hint.isNotBlank()) Text(hint, fontSize = 10.5.sp, color = KbTextSub)
            }
        }
    }
}

/** 环形图（甜甜圈）：右侧图例带数值 */
@Composable
internal fun DonutChart(
    title: String,
    segments: List<Triple<String, Int, Color>>,
    modifier: Modifier = Modifier,
) {
    val total = segments.sumOf { it.second }.coerceAtLeast(1)
    KbSurface(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(112.dp)) {
                    val stroke = 16.dp.toPx()
                    val inset = stroke / 2f
                    var start = -90f
                    if (segments.all { it.second <= 0 }) {
                        drawArc(
                            color = Color(0xFFF1F2F4),
                            startAngle = 0f, sweepAngle = 360f, useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    } else {
                        for ((_, v, c) in segments) {
                            if (v <= 0) continue
                            val sweep = 360f * v / total
                            drawArc(
                                color = c,
                                startAngle = start + 1.5f, sweepAngle = (sweep - 3f).coerceAtLeast(1f),
                                useCenter = false,
                                topLeft = Offset(inset, inset),
                                size = Size(size.width - stroke, size.height - stroke),
                                style = Stroke(width = stroke, cap = StrokeCap.Round),
                            )
                            start += sweep
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((name, v, c) in segments) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(c))
                            Spacer(Modifier.width(6.dp))
                            Text(name, fontSize = 11.sp, color = KbTextSub, modifier = Modifier.width(52.dp))
                            Text(
                                v.toString(),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = KbTextMain,
                            )
                        }
                    }
                    Text(
                        "合计 " + segments.sumOf { it.second },
                        fontSize = 10.5.sp,
                        color = KbTextSub,
                    )
                }
            }
        }
    }
}

/** 柱状图：圆角柱 + 柱顶数值 + 底部标签 */
@Composable
internal fun BarChart(
    title: String,
    items: List<Pair<String, Int>>,
    tint: Pair<Color, Color>,
    modifier: Modifier = Modifier,
) {
    val maxV = (items.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    KbSurface(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().height(130.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                for ((name, v) in items) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(
                            v.toString(),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = KbTextMain,
                        )
                        Spacer(Modifier.height(3.dp))
                        Box(
                            Modifier
                                .width(26.dp)
                                .fillMaxHeight((v.toFloat() / maxV).coerceIn(0.02f, 1f) * 0.82f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        listOf(tint.first, tint.second)
                                    )
                                )
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(name, fontSize = 10.sp, color = KbTextSub, maxLines = 1, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** 折线图：可选多条线 + 图例；x 轴标签等分布局 */
@Composable
internal fun LineChart(
    title: String,
    labels: List<String>,
    series: List<Triple<String, List<Int>, Color>>,
    modifier: Modifier = Modifier,
) {
    val maxV = (series.flatMap { it.second }.maxOrNull() ?: 0).coerceAtLeast(1)
    KbSurface(modifier) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
                Spacer(Modifier.width(12.dp))
                for ((name, _, c) in series) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(4.dp))
                    Text(name, fontSize = 10.5.sp, color = KbTextSub)
                    Spacer(Modifier.width(10.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.fillMaxWidth().height(130.dp)) {
                val padTop = 6.dp.toPx()
                val padBottom = 6.dp.toPx()
                val h = size.height - padTop - padBottom
                val w = size.width
                // 网格（4 条横线）
                for (i in 0..3) {
                    val y = padTop + h * i / 3f
                    drawLine(
                        Color(0xFFF1F2F4), Offset(0f, y), Offset(w, y), strokeWidth = 1f,
                    )
                }
                if (labels.isEmpty()) return@Canvas
                val step = if (labels.size <= 1) w else w / (labels.size - 1)
                for ((_, data, color) in series) {
                    if (data.isEmpty()) continue
                    val path = Path()
                    data.forEachIndexed { idx, v ->
                        val x = if (data.size <= 1) w / 2 else step * idx
                        val y = padTop + h * (1f - v.toFloat() / maxV)
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                    data.forEachIndexed { idx, v ->
                        val x = if (data.size <= 1) w / 2 else step * idx
                        val y = padTop + h * (1f - v.toFloat() / maxV)
                        drawCircle(color, radius = 2.2.dp.toPx(), center = Offset(x, y))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            if (labels.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (l in labels) {
                        Text(l, fontSize = 9.5.sp, color = KbTextSub, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 小数/大数都好看一点的整数化，供图表内部用 */
internal fun niceMax(v: Int): Int {
    if (v <= 0) return 1
    var step = 1
    while (step * 10 < v) step *= 10
    return ((v + step - 1) / step) * step
}

internal fun ratio(v: Int, total: Int): Float = if (total <= 0) 0f else min(1f, v.toFloat() / total.coerceAtLeast(1)).let { max(it, 0f) }