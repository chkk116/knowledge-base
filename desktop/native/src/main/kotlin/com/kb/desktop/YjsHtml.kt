package com.kb.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.carcdr.ycrdt.FormattingChunk
import net.carcdr.ycrdt.YBindingFactory
import net.carcdr.ycrdt.YXmlElement
import net.carcdr.ycrdt.YXmlText

/**
 * 协同正文：Yjs 二进制状态 → HTML。
 *
 * 数据形态（Tiptap + y-prosemirror）：`Y.XmlFragment("default")` → `doc` → 块节点（paragraph/heading/
 * bulletList/listItem/codeBlock/blockquote/table…），行内格式是 **YXmlText 的 formatting chunks**
 * （`getFormattingChunks()` 的 attrs：bold/italic/strike/underline/code/link），不是嵌套元素——
 * 所以**不能**用 `toString()`（它会把 marks 输出成 `<bold>` 伪标签）。
 *
 * 只读用途：不做事务、不写回，解析失败一律返回 null 由调用方回退 content 快照。
 */
object YjsHtml {

    private const val MAX_DEPTH = 64

    /** 解析失败（损坏/超深/库异常）返回 null，调用方回退快照。 */
    fun toHtml(state: ByteArray): String? = runCatching {
        val binding = YBindingFactory.auto()
        binding.createDoc().use { doc ->
            doc.applyUpdate(state)
            val frag = doc.getXmlFragment("default")
            val sb = StringBuilder()
            for (i in 0 until frag.length()) {
                renderNode(frag.getChild(i), sb, 0)
            }
            sb.toString()
        }
    }.getOrNull()

    private fun renderNode(node: Any?, sb: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH) return
        when (node) {
            is YXmlElement -> renderElement(node, sb, depth)
            is YXmlText -> renderText(node, sb)
            else -> Unit
        }
    }

    private fun renderChildren(e: YXmlElement, sb: StringBuilder, depth: Int) {
        for (i in 0 until e.childCount()) {
            renderNode(e.getChild(i), sb, depth + 1)
        }
    }

    private fun renderElement(e: YXmlElement, sb: StringBuilder, depth: Int) {
        val tag = e.getTag().lowercase()
        fun attr(name: String): String? = e.getAttribute(name)?.toString()?.takeIf { it.isNotBlank() }

        when (tag) {
            // 容器（TipTap 根 doc）
            "doc", "fragment", "root" -> renderChildren(e, sb, depth)

            // 块
            "paragraph" -> block("p", e, sb, depth, attr("textalign")?.let { "text-align:$it" })
            "heading" -> {
                val level = attr("level")?.toIntOrNull()?.coerceIn(1, 6) ?: 1
                block("h$level", e, sb, depth)
            }
            "bulletlist" -> block("ul", e, sb, depth)
            "orderedlist" -> {
                val start = attr("start")
                if (start != null) sb.append("<ol start=\"").append(escape(start)).append("\">")
                else sb.append("<ol>")
                renderChildren(e, sb, depth)
                sb.append("</ol>")
            }
            "listitem", "taskitem" -> {
                sb.append("<li>")
                if (tag == "taskitem") {
                    val checked = attr("checked") == "true"
                    sb.append(if (checked) "☑ " else "☐ ")
                }
                renderChildren(e, sb, depth)
                sb.append("</li>")
            }
            "tasklist" -> block("ul", e, sb, depth)
            "blockquote" -> block("blockquote", e, sb, depth)
            "codeblock" -> {
                sb.append("<pre><code>")
                renderChildren(e, sb, depth)
                sb.append("</code></pre>")
            }
            "horizontalrule" -> sb.append("<hr>")
            "hardbreak" -> sb.append("<br>")
            "image" -> {
                val src = safeImgSrc(attr("src"))
                if (src != null) {
                    sb.append("<img src=\"").append(escape(src)).append('"')
                    attr("alt")?.let { sb.append(" alt=\"").append(escape(it)).append('"') }
                    attr("title")?.let { sb.append(" title=\"").append(escape(it)).append('"') }
                    sb.append('>')
                }
            }
            "table" -> block("table", e, sb, depth)
            "tablerow" -> block("tr", e, sb, depth)
            "tablecell" -> block("td", e, sb, depth)
            "tableheader" -> block("th", e, sb, depth)

            // 行内标记（少数实现会以元素形式出现）
            "bold", "strong" -> inline("strong", e, sb, depth)
            "italic", "em" -> inline("em", e, sb, depth)
            "strike", "del", "s" -> inline("s", e, sb, depth)
            "underline", "u" -> inline("u", e, sb, depth)
            "code" -> inline("code", e, sb, depth)
            "highlight", "mark" -> inline("mark", e, sb, depth)
            "link" -> {
                val href = safeHref(attr("href"))
                if (href != null) {
                    sb.append("<a href=\"").append(escape(href)).append("\">")
                    renderChildren(e, sb, depth)
                    sb.append("</a>")
                } else renderChildren(e, sb, depth)
            }

            // TipTap 自定义图块：能静态渲染就渲染（表格 / 流程图），渲不了给中文提示，别把节点名丢给用户
            "sheet-block", "sheetblock" -> sb.append(sheetBlockHtml(attr("data"), attr("title")))
            "flowchart-block", "flowchartblock" -> sb.append(fcBlockHtml(attr("data"), attr("title")))
            "mindmap-block", "mindmapblock" -> sb.append(
                blockNotice("🧠", attr("title") ?: "思维导图", "这是交互块，桌面端只显示提示；要编辑请点「编辑」进网页版")
            )

            else -> {
                // 未知标签按容器下钻（保住里面的文字），与 DocHtml 的处理口径一致
                renderChildren(e, sb, depth)
            }
        }
    }

    private fun block(tag: String, e: YXmlElement, sb: StringBuilder, depth: Int, style: String? = null) {
        if (style != null) sb.append('<').append(tag).append(" style=\"").append(escape(style)).append("\">")
        else sb.append('<').append(tag).append('>')
        renderChildren(e, sb, depth)
        sb.append("</").append(tag).append('>')
    }

    private fun inline(tag: String, e: YXmlElement, sb: StringBuilder, depth: Int) {
        sb.append('<').append(tag).append('>')
        renderChildren(e, sb, depth)
        sb.append("</").append(tag).append('>')
    }

    /** 文本节点：按 formatting chunks 逐段包行内标签（这是 marks 的真实载体）。 */
    private fun renderText(t: YXmlText, sb: StringBuilder) {
        val chunks: List<FormattingChunk> = runCatching { t.getFormattingChunks() }.getOrDefault(emptyList())
        if (chunks.isEmpty()) {
            sb.append(escape(t.toString()))
            return
        }
        for (c in chunks) {
            val attrs = c.getAttributes()
            val open = ArrayList<String>(4)
            val close = ArrayList<String>(4)
            fun mark(on: Boolean, o: String, c2: String) {
                if (on) { open.add(o); close.add(c2) }
            }
            mark(attrs["bold"] == true, "<strong>", "</strong>")
            mark(attrs["italic"] == true, "<em>", "</em>")
            mark(attrs["strike"] == true, "<s>", "</s>")
            mark(attrs["underline"] == true, "<u>", "</u>")
            mark(attrs["code"] == true, "<code>", "</code>")
            mark(attrs["highlight"] != null, "<mark>", "</mark>")
            val linkAttrs = attrs["link"]
            var href: String? = null
            if (linkAttrs is Map<*, *>) href = linkAttrs["href"]?.toString()
            else if (linkAttrs is String) href = linkAttrs
            val safeLink = safeHref(href)
            if (safeLink != null) { open.add("<a href=\"" + escape(safeLink) + "\">"); close.add("</a>") }

            for (o in open) sb.append(o)
            sb.append(escape(c.getText()))
            for (c2 in close.asReversed()) sb.append(c2)
        }
    }

    // ---------- TipTap 图块（表格 / 流程图 / 导图）的静态渲染 ----------

    private const val MAX_SHEET_ROWS = 30
    private const val MAX_SHEET_COLS = 15
    private const val MAX_FC_NODES = 40
    private const val MAX_FC_EDGES = 60

    /** 图块统一提示框（渲染不了的交互块给出信息，而不是裸节点名）。 */
    private fun blockNotice(icon: String, title: String, hint: String): String =
        "<div style=\"margin:14px 0;padding:12px 14px;border:1px dashed #c9cdd4;border-radius:10px;" +
            "background:#f7f8fa;color:#4e5969;font-size:13px;line-height:1.6\">" + icon + " <b>" +
            escape(title) + "</b><br><span style=\"font-size:12px;color:#86909c\">" + escape(hint) + "</span></div>"

    /**
     * sheetBlock：`data = {rows:{len,"<row>":{cells:{"<col>":{text}}}}, cols:{len}, merges:[…]}`。
     * 桌面端渲染成静态表格（截断到 30×15，避免超大表把 WebView 拖死）；空表给提示。
     */
    private fun sheetBlockHtml(data: String?, title: String?): String {
        val name = title ?: "电子表格"
        val nameHtml = escape(name)
        val root = data?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return blockNotice("📊", name, "没有可读快照，请点「编辑」在网页版查看")
        val rows = runCatching { root["rows"]?.jsonObject }.getOrNull()
            ?: return blockNotice("📊", name, "数据为空，请点「编辑」在网页版查看")
        val cellsByRow = sortedMapOf<Int, JsonObject>()
        for ((k, v) in rows) {
            val r = k.toIntOrNull() ?: continue
            val cells = runCatching { (v as JsonObject).get("cells")?.jsonObject }.getOrNull() ?: continue
            if (cells.isNotEmpty()) cellsByRow[r] = cells
        }
        if (cellsByRow.isEmpty()) return blockNotice("📊", name, "数据为空，请点「编辑」在网页版查看")
        var maxCol = 0
        for (cells in cellsByRow.values) {
            for (k in cells.keys) k.toIntOrNull()?.let { maxCol = maxOf(maxCol, it) }
        }
        val lastRow = minOf(cellsByRow.lastKey(), MAX_SHEET_ROWS - 1)
        val lastCol = minOf(maxCol, MAX_SHEET_COLS - 1)
        val sb = StringBuilder()
        sb.append("<div style=\"margin:10px 0 6px;font-size:12px;color:#86909c\">📊 ")
            .append(nameHtml).append(" · 静态快照（只读）</div>")
        sb.append("<table style=\"border-collapse:collapse;width:100%;font-size:13px\">")
        for (r in 0..lastRow) {
            val cells = cellsByRow[r]
            sb.append("<tr>")
            for (c in 0..lastCol) {
                val text = cells?.get(c.toString())?.let { cell ->
                    runCatching { (cell as JsonObject).get("text")?.jsonPrimitive?.content }.getOrNull()
                }.orEmpty()
                sb.append("<td style=\"border:1px solid #dcdfe6;padding:4px 6px;vertical-align:top;min-width:54px\">")
                    .append(if (text.isBlank()) "&nbsp;" else escape(text))
                    .append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
        return sb.toString()
    }

    /**
     * flowchartBlock：`data = {nodes:[{id,data:{label}}], edges:[{source,target,label}]}`。
     * 桌面端不做画布，输出「节点 + 连线」清单（最多 40 节点 / 60 连线）。
     */
    private fun fcBlockHtml(data: String?, title: String?): String {
        val name = title ?: "流程图"
        val nameHtml = escape(name)
        val root = data?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return blockNotice("🧭", name, "数据为空，请点「编辑」在网页版查看")
        val nodes = runCatching { root["nodes"]?.jsonArray }.getOrNull().orEmpty()
        val edges = runCatching { root["edges"]?.jsonArray }.getOrNull().orEmpty()
        if (nodes.isEmpty() && edges.isEmpty()) return blockNotice("🧭", name, "数据为空，请点「编辑」在网页版查看")

        fun text(o: JsonObject, key: String): String? =
            runCatching { (o[key] as JsonObject) }.getOrNull()?.let { inner ->
                runCatching { inner["label"]?.jsonPrimitive?.content }.getOrNull()
            } ?: runCatching { o[key]?.jsonPrimitive?.content }.getOrNull()

        val idLabel = HashMap<String, String>()
        val sb = StringBuilder()
        sb.append("<div style=\"margin:10px 0 6px;font-size:12px;color:#86909c\">🧭 ")
            .append(nameHtml).append(" · 静态快照（").append(nodes.size).append(" 个节点 / ")
            .append(edges.size).append(" 条连线）</div><ul style=\"font-size:13px;line-height:1.8\">")
        nodes.take(MAX_FC_NODES).forEachIndexed { i, el ->
            val o = el as? JsonObject ?: return@forEachIndexed
            val id = runCatching { o["id"]?.jsonPrimitive?.content }.getOrNull() ?: "n$i"
            val label = text(o, "data") ?: runCatching { o["label"]?.jsonPrimitive?.content }.getOrNull() ?: id
            idLabel[id] = label
            sb.append("<li>").append(escape(label)).append("</li>")
        }
        sb.append("</ul>")
        val drawn = edges.take(MAX_FC_EDGES).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val from = runCatching { (o["source"] ?: o["from"])?.jsonPrimitive?.content }.getOrNull()
                ?: return@mapNotNull null
            val to = runCatching { (o["target"] ?: o["to"])?.jsonPrimitive?.content }.getOrNull()
                ?: return@mapNotNull null
            val label = text(o, "data") ?: runCatching { o["label"]?.jsonPrimitive?.content }.getOrNull()
            val line = "${idLabel[from] ?: from} → ${idLabel[to] ?: to}"
            if (label.isNullOrBlank()) line else "$line（${label}）"
        }
        if (drawn.isNotEmpty()) {
            sb.append("<ul style=\"font-size:13px;line-height:1.8;color:#4e5969\">")
            drawn.forEach { sb.append("<li>").append(escape(it)).append("</li>") }
            sb.append("</ul>")
        }
        return sb.toString()
    }

    /**
     * 图片 src 协议白名单：仅 http/https 与站内相对路径（单个 / 开头）。
     * 其余（javascript: / data: / vbscript: 等）一律返回 null 丢弃该属性。
     */
    private fun safeImgSrc(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val lower = s.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> s
            s.startsWith("/") && !s.startsWith("//") -> s
            else -> null
        }
    }

    /**
     * 链接 href 协议白名单：http/https、mailto:、`#` 锚点与站内相对路径（单个 / 开头）。
     * 其余一律返回 null，丢弃该属性（避免 javascript: 之类被执行）。
     */
    private fun safeHref(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val lower = s.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> s
            lower.startsWith("mailto:") -> s
            s.startsWith("#") -> s
            s.startsWith("/") && !s.startsWith("//") -> s
            else -> null
        }
    }

    private fun escape(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val sb = StringBuilder(s.length + 16)
        for (ch in s) when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(ch)
        }
        return sb.toString()
    }
}