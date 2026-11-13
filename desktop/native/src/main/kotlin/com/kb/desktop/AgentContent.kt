package com.kb.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/**
 * Agent 写入正文用的内容生成工具（纯函数）。
 *
 * 口径与 Web 端 utils/aiPure.js 的 mdToHtml、utils/flowchartGen.js 的
 * buildFlowchartGraph/flowchartHtml 保持一致：Markdown → 简单 HTML；
 * 流程图 → TipTap 自定义节点 `<div data-type="flowchart-block" data='{...}'>`，
 * 后端 HtmlSanitizer 明确放行 div 的 data-type/data/locked/title 属性，能原样存进共享文档。
 */
object AgentContent {

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** 只允许站内相对资源，避免文档里塞外站追踪图片（与 Web 端 ALLOWED_IMG 同口径）。 */
    private fun safeAsset(u: String): Boolean =
        u.startsWith("/uploads/") || u.startsWith("/files/") || u.startsWith("/api/files/")

    /** Markdown → HTML（标题 / 列表 / 代码块 / 粗体 / 图片 / 音视频 / 段落）。 */
    fun mdToHtml(md: String): String {
        val out = ArrayList<String>()
        var inCode = false
        var inList = false
        fun closeList() { if (inList) { out.add("</ul>"); inList = false } }

        for (raw in md.split("\n")) {
            val line = raw.trimEnd()
            if (line.startsWith("```")) {
                closeList()
                out.add(if (inCode) "</pre>" else "<pre>")
                inCode = !inCode
                continue
            }
            if (inCode) { out.add(esc(line)); continue }

            val img = Regex("""^!\[([^]]*)\]\(([^)\s]+)\)\s*$""").find(line)
            if (img != null) {
                closeList()
                val url = img.groupValues[2]
                out.add(
                    if (safeAsset(url)) "<img src=\"${esc(url)}\" alt=\"${esc(img.groupValues[1])}\"/>"
                    else "<p>${esc(line)}</p>"
                )
                continue
            }
            val media = Regex("""^@\[(video|audio)\]\(([^)\s]+)\)\s*$""").find(line)
            if (media != null) {
                closeList()
                val url = media.groupValues[2]
                val tag = media.groupValues[1]
                out.add(
                    if (safeAsset(url)) "<$tag src=\"${esc(url)}\" controls></$tag>"
                    else "<p>${esc(line)}</p>"
                )
                continue
            }
            val h = Regex("""^(#{1,4})\s+(.*)$""").find(line)
            if (h != null) {
                closeList()
                val n = h.groupValues[1].length
                out.add("<h$n>${esc(h.groupValues[2])}</h$n>")
                continue
            }
            val li = Regex("""^\s*[-*]\s+(.*)$""").find(line)
            if (li != null) {
                if (!inList) { out.add("<ul>"); inList = true }
                out.add("<li>${esc(li.groupValues[1])}</li>")
                continue
            }
            closeList()
            if (line.isBlank()) continue
            val para = esc(line).replace(Regex("""\*\*(.+?)\*\*"""), "<strong>$1</strong>")
            out.add("<p>$para</p>")
        }
        closeList()
        if (inCode) out.add("</pre>")
        return out.joinToString("")
    }

    private val SHAPES = setOf("ellipse", "rect", "diamond", "doc", "db", "sub")

    private fun JsonObject?.text(key: String, alt: String? = null, limit: Int = 60): String {
        val v = (this?.get(key) as? JsonPrimitive)?.contentOrNull
            ?: (alt?.let { (this?.get(it) as? JsonPrimitive)?.contentOrNull })
            ?: ""
        return v.take(limit)
    }

    /**
     * 供 Agent 调用的入口：直接吃模型输出的 JSON 数组字符串，返回 (图谱 JSON, 节点数 to 连线数)。
     * 解析失败或节点为空返回 null。
     */
    fun buildFlowchartGraphFromJson(nodesJson: String, edgesJson: String): Pair<String, Pair<Int, Int>>? {
        val nodes = runCatching {
            Json.parseToJsonElement(nodesJson).jsonArray.mapNotNull { it as? JsonObject }
        }.getOrDefault(emptyList())
        val edges = runCatching {
            Json.parseToJsonElement(edgesJson).jsonArray.mapNotNull { it as? JsonObject }
        }.getOrDefault(emptyList())
        return buildFlowchartGraph(nodes, edges)
    }

    /**
     * 把 Agent 给的 nodes/edges 规范化成 Vue Flow 图谱 JSON 字符串（含自动分层排版）。
     * 去重 id、过滤无效边/自环/重复边，节点上限 40、连线 60 —— 与 Web 端口径一致。
     */
    private fun buildFlowchartGraph(nodes: List<JsonObject>, edges: List<JsonObject>): Pair<String, Pair<Int, Int>>? {
        val seen = HashSet<String>()
        val clean = ArrayList<Triple<String, String, String>>()
        for ((i, n) in nodes.withIndex()) {
            val id = ((n["id"] as? JsonPrimitive)?.contentOrNull ?: (i + 1).toString()).take(40)
            if (!seen.add(id)) continue
            val label = n.text("label", "text", 60)
            val rawShape = (n["shape"] as? JsonPrimitive)?.contentOrNull ?: "rect"
            clean.add(Triple(id, label, if (rawShape in SHAPES) rawShape else "rect"))
            if (clean.size >= 40) break
        }
        if (clean.isEmpty()) return null
        val ids = clean.map { it.first }.toSet()

        val seenEdges = HashSet<String>()
        val norm = ArrayList<Triple<String, String, String>>()
        for (e in edges) {
            val from = (e["from"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val to = (e["to"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (from !in ids || to !in ids || from == to) continue
            if (!seenEdges.add("$from->$to")) continue
            norm.add(Triple(from, to, e.text("label", limit = 20)))
            if (norm.size >= 60) break
        }

        // 分层布局：入度为 0 起 BFS，未连到的节点放到最后一层
        val indegree = LinkedHashMap<String, Int>()
        ids.forEach { indegree[it] = 0 }
        val next = HashMap<String, MutableList<String>>()
        norm.forEach { (f, t, _) ->
            next.getOrPut(f) { ArrayList() }.add(t)
            indegree[t] = (indegree[t] ?: 0) + 1
        }
        val layer = HashMap<String, Int>()
        val queue = ArrayDeque<String>()
        ids.filter { (indegree[it] ?: 0) == 0 }.forEach { layer[it] = 0; queue.add(it) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val lv = layer[cur] ?: 0
            for (nx in next[cur].orEmpty()) {
                if (!layer.containsKey(nx)) { layer[nx] = lv + 1; queue.add(nx) }
            }
        }
        val maxLayer = layer.values.maxOrNull() ?: 0
        ids.filter { !layer.containsKey(it) }.forEach { layer[it] = maxLayer + 1 }
        val rowIndex = HashMap<Int, Int>()

        fun position(id: String): JsonObject = buildJsonObject {
            val lv = layer[id] ?: 0
            val row = rowIndex[lv] ?: 0
            rowIndex[lv] = row + 1
            put("x", 60 + lv * 220)
            put("y", 60 + row * 110)
        }

        val graph = buildJsonObject {
            put("nodes", buildJsonArray {
                for ((id, label, shape) in clean) {
                    add(buildJsonObject {
                        put("id", id)
                        put("type", "fcNode")
                        put("position", position(id))
                        put("data", buildJsonObject { put("label", label); put("shape", shape) })
                    })
                }
            })
            put("edges", buildJsonArray {
                for ((i, e) in norm.withIndex()) {
                    add(buildJsonObject {
                        put("id", "e" + (i + 1))
                        put("source", e.first)
                        put("target", e.second)
                        put("type", "fcEdge")
                        put("data", buildJsonObject { put("label", e.third) })
                    })
                }
            })
        }
        return graph.toString() to (clean.size to norm.size)
    }

    /** 生成共享文档里的 flowchart 块 HTML（属性用单引号，内部 JSON 的双引号原样保留）。 */
    fun flowchartHtml(title: String, graphJson: String): String {
        val safeTitle = title.replace("&", "&amp;").replace(Regex("""["'<>]"""), "").take(80)
        val json = graphJson
            .replace("&", "&amp;")
            .replace("'", "&#39;")
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
        return "<div data-type=\"flowchart-block\" data='$json' title=\"$safeTitle\"></div>"
    }
}
