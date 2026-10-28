/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.diagram

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import kotlin.math.max
import kotlin.math.min

/**
 * 原生流程图渲染（Compose，不用 WebView）。
 *
 * 为什么不再套一层 WebView：原生壳的意义就是原生渲染与交互；把图块丢回 WebView
 * 等于退回 Capacitor 套壳。这里用 Vue Flow 存的坐标直接排版，Canvas 画连线、Box 画节点。
 *
 * 布局策略：
 *  - 节点带有效坐标（x/y 不同时为 0）→ 按原坐标等比缩放到可用宽度；
 *  - 坐标缺失（AI 生成或老数据）→ 自动纵向链式排布，保证一定能看到东西。
 */
@Composable
fun NativeDiagram(data: DiagramData, modifier: Modifier = Modifier) {
    val nodeW = 128f
    val nodeH = 52f
    val gapX = 40f
    val gapY = 40f

    // 1) 归一化坐标：全 0 时走自动纵向布局
    val hasPos = data.nodes.any { it.x != 0f || it.y != 0f }
    val laid = remember(data, hasPos) {
        if (hasPos) {
            data.nodes
        } else {
            data.nodes.mapIndexed { i, n -> n.copy(x = 0f, y = i * (nodeH + gapY)) }
        }
    }
    val minX = laid.minOfOrNull { it.x } ?: 0f
    val minY = laid.minOfOrNull { it.y } ?: 0f
    val maxX = laid.maxOfOrNull { it.x } ?: 0f
    val maxY = laid.maxOfOrNull { it.y } ?: 0f
    val contentW = max(nodeW, maxX - minX + nodeW)
    val contentH = max(nodeH, maxY - minY + nodeH)

    // 2) 缩放：宽度超出屏幕时整体缩小（最低 0.45 倍，再小就看不清了）
    val density = androidx.compose.ui.platform.LocalDensity.current
    val availPx = with(density) { (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 32.dp).toPx() }
    val scale = min(1f, max(0.45f, availPx / with(density) { contentW.dp.toPx() }))
    val wDp = with(density) { (contentW * scale).toDp() }
    val hDp = with(density) { (contentH * scale).toDp() }

    val edgeColor = MaterialTheme.colorScheme.outline
    val nodeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val nodeBorder = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)

    Box(
        modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(8.dp)
    ) {
        Box(Modifier.width(wDp).height(hDp)) {
            // 3) 连线（先画，压在节点下面）
            Canvas(Modifier.width(wDp).height(hDp)) {
                val sx = size.width / contentW
                val sy = size.height / contentH
                fun cx(n: DNode) = (n.x - minX + nodeW / 2f) * sx
                fun cy(n: DNode) = (n.y - minY + nodeH / 2f) * sy
                val byId = laid.associateBy { it.id }
                data.edges.forEach { e ->
                    val s = byId[e.source] ?: return@forEach
                    val t = byId[e.target] ?: return@forEach
                    val x1 = cx(s); val y1 = cy(s)
                    val x2 = cx(t); val y2 = cy(t)
                    drawLine(edgeColor, Offset(x1, y1), Offset(x2, y2), strokeWidth = 1.6f)
                    // 箭头：靠近目标端画个小三角（用两条短线近似）
                    val dx = x2 - x1; val dy = y2 - y1
                    val len = max(1f, kotlin.math.sqrt(dx * dx + dy * dy))
                    val ux = dx / len; val uy = dy / len
                    val ax = x2 - ux * 10f; val ay = y2 - uy * 10f
                    drawLine(edgeColor, Offset(ax, ay), Offset(ax - uy * 5f - ux * 6f, ay + ux * 5f - uy * 6f), strokeWidth = 1.6f)
                    drawLine(edgeColor, Offset(ax, ay), Offset(ax + uy * 5f - ux * 6f, ay - ux * 5f - uy * 6f), strokeWidth = 1.6f)
                }
            }

            // 4) 节点
            laid.forEach { n ->
                val left = with(density) { ((n.x - minX) * scale).toDp() }
                val top = with(density) { ((n.y - minY) * scale).toDp() }
                val boxW = with(density) { (nodeW * scale).toDp() }
                val boxH = with(density) { (nodeH * scale).toDp() }
                val isRound = n.shape == "circle" || n.shape == "ellipse"
                Box(
                    Modifier
                        .padding(start = left, top = top)
                        .size(width = boxW, height = boxH)
                        .clip(if (isRound) CircleShape else RoundedCornerShape(8.dp))
                        .background(nodeBg)
                        .border(1.dp, nodeBorder, if (isRound) CircleShape else RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = n.label,
                        fontSize = (13f * scale).coerceAtLeast(8f).sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }
        }
    }
}
