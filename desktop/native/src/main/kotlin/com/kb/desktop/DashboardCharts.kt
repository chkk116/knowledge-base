package com.kb.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                    .background(Brush.linearGradient(listOf(tint.first, tint.second))),
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
                                startAngle = start + 1.5f, sweepAngle = (sweep - 3f).coerceAtLeast(0.4f),
                                useCenter = false,
                                // Butt 而不是 Round：圆头会让 1/1000 这种极小分段画出远超真实占比的弧长，
                                // 还会压到相邻分段上，把「待审核/草稿」这类稀有类目画大
                                topLeft = Offset(inset, inset),
                                size = Size(size.width - stroke, size.height - stroke),
                                style = Stroke(width = stroke, cap = StrokeCap.Butt),
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
                        "合计 ${segments.sumOf { it.second }}",
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
    // 数值文字 + 间距 + 底部标签约占 34dp，柱子只能在这个范围内按比例长；
    // 直接对整列 130dp 用 fillMaxHeight 会把最高柱顶出容器（标签被裁掉）
    val barArea = 96.dp
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
                                .height(barArea * (v.toFloat() / maxV).coerceIn(0.02f, 1f))
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    Brush.verticalGradient(listOf(tint.first, tint.second))
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
                for ((_, raw, color) in series) {
                    // 序列来自服务端解析，长度不保证等于轴标签数：多的截掉（否则点会画到画布外），
                    // 少的按自身点数均分，避免与下方刻度错位
                    val data = if (raw.size > labels.size) raw.take(labels.size) else raw
                    if (data.isEmpty()) continue
                    val step = if (data.size <= 1) 0f else w / (data.size - 1)
                    fun xAt(idx: Int) = if (data.size <= 1) w / 2 else step * idx
                    fun yAt(v: Int) = padTop + h * (1f - v.toFloat() / maxV)
                    val path = Path()
                    data.forEachIndexed { idx, v ->
                        if (idx == 0) path.moveTo(xAt(idx), yAt(v)) else path.lineTo(xAt(idx), yAt(v))
                    }
                    drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                    data.forEachIndexed { idx, v ->
                        drawCircle(color, radius = 2.2.dp.toPx(), center = Offset(xAt(idx), yAt(v)))
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
