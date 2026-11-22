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
    /**
     * 图块占位：正文里有 `data-type="…-block"` 但**没有可用 data**（Web 端还没把图数据写进快照，
     * 或该块是表格/导图这类原生端不绘制的类型）。渲染成「在编辑页打开」的卡片，避免整块空白。
     */
    data class DiagramShell(val kind: String, val title: String) : DocPiece()
}

object DiagramParser {

    /** 三类图块：流程图/导图是 Vue Flow 结构，表格是工作簿 JSON（原生端只做占位，不解析） */
    private val BLOCK = Regex(
        "<div[^>]*data-type=\"(flowchart-block|mindmap-block|sheet-block)\"[^>]*>",
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

    /** 按顺序取出每个图块的 data JSON（编辑页按序号取原始串用） */
    fun dataList(html: String): List<String> {
        val out = ArrayList<String>()
        for (m in BLOCK.findAll(html)) {
            val raw = ATTR_DATA.find(m.value) ?: continue
            val v = raw.groupValues.let { if (it[2].isNotEmpty()) it[2] else it[3] }
            if (v.isNotBlank()) out.add(v)
        }
        return out
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
            val title = Regex("""title=(['"])(.*?)\1""").find(openTag)?.groupValues?.get(2)
                ?: "图表"
            val kind = Regex("""data-type=\"([a-z-]+)\""").find(openTag)?.groupValues?.get(1).orEmpty()
            if (kind.startsWith("sheet")) {
                // 表格是工作簿 JSON，原生端不绘制（避免半吊子渲染），统一给占位卡
                pieces.add(DocPiece.DiagramShell("sheet", title))
            } else if (data != null && data.nodes.isNotEmpty()) {
                pieces.add(DocPiece.Diagram(data, title))
            } else {
                // 没有 data：Web 端还没保存含图数据的快照（数据只在 yjs 里），给「编辑页打开」卡片
                pieces.add(DocPiece.DiagramShell(kind, title))
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
