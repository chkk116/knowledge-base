/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AI 回答的 Markdown 轻量渲染（原生壳专用，非完整 CommonMark）。
 *
 * 决策：完整 Markdown 渲染在原生端要引 markdown-parser + 自绘布局，成本高；
 * AI 回答 95% 的元素是 段落 / 加粗 / 行内代码 / 代码块 / 标题 / 列表，这里全部覆盖，
 * 其余语法降级为普通文本（不会崩、不会丢内容）。与 Web 端 markdown-it 的观感差距可接受。
 *
 * 支持：``` 围栏代码块、# 标题、短横线或星号开头的无序列表、1. 有序列表、**加粗**、`行内代码`。
 */
private sealed class Block {
    data class Code(val lines: List<String>) : Block()
    data class Text(val lines: List<String>) : Block()
}

@Composable
fun MarkdownLite(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Code -> Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (color == Color.Unspecified) MaterialTheme.colorScheme.surfaceVariant
                    else color.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        b.lines.joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }
                is Block.Text -> b.lines.forEach { line ->
                    Text(
                        inline(line),
                        fontSize = 14.5.sp,
                        lineHeight = 23.sp,
                        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
                    )
                }
            }
        }
    }
}

/** 分块：围栏代码块单独成块，其余按行归为文本块 */
private fun parseBlocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val codeBuf = mutableListOf<String>()
    val textBuf = mutableListOf<String>()
    var inCode = false

    fun flushText() {
        if (textBuf.isNotEmpty()) { out.add(Block.Text(textBuf.toList())); textBuf.clear() }
    }
    fun flushCode() {
        if (codeBuf.isNotEmpty()) { out.add(Block.Code(codeBuf.toList())); codeBuf.clear() }
    }

    for (raw in text.split("\n")) {
        val line = raw.trimEnd()
        val fence = line.trim()
        if (fence.startsWith("```")) {
            if (inCode) { flushCode(); inCode = false }
            else { flushText(); inCode = true }
            continue
        }
        if (inCode) codeBuf.add(raw)
        else {
            var l = line
            // 标题 → 去井号加粗；无序列表 → 项目符号；有序列表原样
            val heading = Regex("^#{1,6}\\s+(.*)$").find(l)
            if (heading != null) l = "**" + heading.groupValues[1] + "**"
            l = Regex("^[-*]\\s+").replace(l, "•  ")
            textBuf.add(l)
        }
    }
    flushText(); flushCode()
    if (inCode && codeBuf.isNotEmpty()) out.add(Block.Code(codeBuf.toList()))
    return out
}

/** 行内解析：**加粗** 与 `代码` */
private val INLINE = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")

private fun inline(line: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(line)) {
        append(line.substring(last, m.range.first))
        m.groupValues[1].takeIf { it.isNotEmpty() }?.let {
            pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
            append(it)
            pop()
        }
        m.groupValues[2].takeIf { it.isNotEmpty() }?.let {
            pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
            append(it)
            pop()
        }
        last = m.range.last + 1
    }
    if (last < line.length) append(line.substring(last))
}
