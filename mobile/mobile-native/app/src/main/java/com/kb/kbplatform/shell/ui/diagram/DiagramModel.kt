/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.diagram

import org.json.JSONObject

/** 流程图/思维导图节点（Vue Flow 的 fcNode 精简版，只要渲染必需字段） */
data class DNode(
    val id: String,
    val label: String,
    val x: Float,
    val y: Float,
    val shape: String = "rect"
)

data class DEdge(val id: String, val source: String, val target: String, val label: String = "")

data class DiagramData(val nodes: List<DNode>, val edges: List<DEdge>)

/** 正文被图块切开后的片段：html = 普通富文本，diagram = 图块 */
sealed class DocPiece {
    data class Html(val html: String) : DocPiece()
    data class Diagram(val data: DiagramData, val title: String) : DocPiece()
}

object DiagramParser {

    /** 只认流程图/思维导图这类 Vue Flow 图块（表格块结构不同，先跳过） */
    private val BLOCK = Regex(
        "<div[^>]*data-type=\"(flowchart-block|mindmap-block)\"[^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val ATTR_DATA = Regex("""data=('([^']*)'|"([^"]*)")""", RegexOption.IGNORE_CASE)

    fun parse(json: String?): DiagramData? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val arr = o.optJSONArray("nodes") ?: return null
            val nodes = ArrayList<DNode>()
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i) ?: continue
                val pos = n.optJSONObject("position")
                val d = n.optJSONObject("data")
                nodes.add(
                    DNode(
                        id = n.optString("id", "n$i"),
                        label = d?.optString("label").orEmpty().ifBlank { "节点" },
                        x = pos?.optDouble("x", 0.0)?.toFloat() ?: 0f,
                        y = pos?.optDouble("y", 0.0)?.toFloat() ?: 0f,
                        shape = d?.optString("shape").orEmpty().ifBlank { "rect" }
                    )
                )
            }
            val edgesArr = o.optJSONArray("edges")
            val edges = ArrayList<DEdge>()
            if (edgesArr != null) {
                for (i in 0 until edgesArr.length()) {
                    val e = edgesArr.optJSONObject(i) ?: continue
                    edges.add(
                        DEdge(
                            id = e.optString("id", "e$i"),
                            source = e.optString("source"),
                            target = e.optString("target"),
                            label = e.optJSONObject("data")?.optString("label").orEmpty()
                        )
                    )
                }
            }
            DiagramData(nodes, edges)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 把正文 HTML 切成「文本段 + 图块段」。
     * 没有图块时返回单个 Html 片段，调用方可以继续走原来的纯文本渲染。
     */
    fun split(html: String): List<DocPiece> {
        val pieces = ArrayList<DocPiece>()
        var cursor = 0
        for (m in BLOCK.findAll(html)) {
            val openTag = m.value
            // 图块的结束标签：同名 div 里可能嵌套，取紧跟其后的 </div>
            val closeIdx = html.indexOf("</div>", m.range.last + 1)
            if (closeIdx < 0) continue
            if (m.range.first > cursor) {
                val text = html.substring(cursor, m.range.first)
                if (text.isNotBlank()) pieces.add(DocPiece.Html(text))
            }
            val raw = ATTR_DATA.find(openTag)
            val json = raw?.groupValues?.let { if (it[2].isNotEmpty()) it[2] else it[3] }
            val data = parse(json)
            if (data != null && data.nodes.isNotEmpty()) {
                val title = Regex("""title=(['"])(.*?)\1""").find(openTag)?.groupValues?.get(2) ?: "流程图"
                pieces.add(DocPiece.Diagram(data, title))
            }
            cursor = closeIdx + "</div>".length
        }
        if (cursor < html.length) {
            val rest = html.substring(cursor)
            if (rest.isNotBlank()) pieces.add(DocPiece.Html(rest))
        }
        return if (pieces.isEmpty()) listOf(DocPiece.Html(html)) else pieces
    }
}
