/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.diagram

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 原生图块编辑器（流程图为先）。
 *
 * 交互：点节点选中 → 改文字 / 删节点；「连线」进入后依次点起点、终点建边；
 * 拖节点改位置（松手才写回 JSON，避免每帧序列化）。
 *
 * 所有修改都通过 [DiagramOps] 在 JSON 上原位改，未涉及的字段（type/style/handle/shape…）原样保留，
 * 保存后网页端打开样式不丢。
 */
@Composable
fun DiagramEditorDialog(
    initialJson: String,
    title: String = "编辑流程图",
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var json by remember { mutableStateOf(initialJson) }
    val model = remember(json) { DiagramParser.parse(json) ?: DiagramData(emptyList(), emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var linkFrom by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<DNode?>(null) }
    var renameText by remember { mutableStateOf("") }

    val nodeW = 132.dp
    val nodeH = 56.dp

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    if (linkFrom == null) "点节点选中；拖动可移动位置"
                    else "已选起点「${model.nodes.firstOrNull { it.id == linkFrom }?.label ?: ""}」，再点目标节点完成连线",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                // 画布：可横向+纵向滚动的固定视口
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    val density = LocalDensity.current
                    val maxX = (model.nodes.maxOfOrNull { it.x } ?: 0f) + 260f
                    val maxY = (model.nodes.maxOfOrNull { it.y } ?: 0f) + 140f
                    Box(
                        Modifier
                            .width(with(density) { maxX.dp })
                            .height(with(density) { maxY.dp })
                    ) {
                        // 连线（颜色在 DrawScope 外取：MaterialTheme 是 @Composable，不能在绘制回调里调）
                        val lineColor = MaterialTheme.colorScheme.outline
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val byId = model.nodes.associateBy { it.id }
                            model.edges.forEach { e ->
                                val s = byId[e.source] ?: return@forEach
                                val t = byId[e.target] ?: return@forEach
                                val sp = with(density) { Pair((s.x + 66f).dp.toPx(), (s.y + 28f).dp.toPx()) }
                                val tp = with(density) { Pair((t.x + 66f).dp.toPx(), (t.y + 28f).dp.toPx()) }
                                drawLine(
                                    lineColor,
                                    Offset(sp.first, sp.second),
                                    Offset(tp.first, tp.second),
                                    strokeWidth = 1.8f
                                )
                            }
                        }
                        // 节点
                        model.nodes.forEach { n ->
                            val isSel = selected == n.id
                            Box(
                                Modifier
                                    .padding(start = n.x.dp, top = n.y.dp)
                                    .size(nodeW, nodeH)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .border(
                                        if (isSel) 2.dp else 1.dp,
                                        if (isSel) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        if (linkFrom == null) {
                                            selected = n.id
                                        } else if (linkFrom == n.id) {
                                            linkFrom = null
                                        } else {
                                            json = DiagramOps.addEdge(json, linkFrom!!, n.id)
                                            linkFrom = null
                                            selected = n.id
                                        }
                                    }
                                    .pointerInput(n.id) {
                                        detectDragGestures(
                                            onDragEnd = {
                                                val cur = DiagramParser.parse(json)?.nodes?.firstOrNull { it.id == n.id }
                                                if (cur != null) json = DiagramOps.setPosition(json, n.id, cur.x, cur.y)
                                            },
                                            onDrag = { change, drag ->
                                                change.consume()
                                                val cur = DiagramParser.parse(json) ?: return@detectDragGestures
                                                val old = cur.nodes.firstOrNull { it.id == n.id } ?: return@detectDragGestures
                                                json = DiagramOps.setPosition(
                                                    json, n.id,
                                                    (old.x + drag.x / density.density).coerceAtLeast(0f),
                                                    (old.y + drag.y / density.density).coerceAtLeast(0f)
                                                )
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    n.label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                // 操作条
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        val (nj, id) = DiagramOps.addNode(json, "新节点", 40f, (model.nodes.maxOfOrNull { it.y } ?: 0f) + 80f)
                        json = nj
                        selected = id
                    }) { Text("加节点", fontSize = 12.sp) }
                    TextButton(enabled = selected != null, onClick = {
                        val n = model.nodes.firstOrNull { it.id == selected }
                        if (n != null) { renaming = n; renameText = n.label }
                    }) { Text("改文字", fontSize = 12.sp) }
                    TextButton(enabled = selected != null, onClick = {
                        selected?.let { json = DiagramOps.removeNode(json, it); selected = null }
                    }) { Text("删节点", fontSize = 12.sp) }
                    TextButton(enabled = selected != null, onClick = { linkFrom = selected }) {
                        Text("连线", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(json) }) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    // 改文字子弹窗
    renaming?.let { node ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("修改节点文字") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    json = DiagramOps.setLabel(json, node.id, renameText.ifBlank { node.label })
                    renaming = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } }
        )
    }
}
