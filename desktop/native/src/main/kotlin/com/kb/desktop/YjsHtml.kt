package com.kb.desktop

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
                val src = attr("src").orEmpty()
                if (src.isNotBlank()) {
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
                val href = attr("href")
                if (href != null) {
                    sb.append("<a href=\"").append(escape(href)).append("\">")
                    renderChildren(e, sb, depth)
                    sb.append("</a>")
                } else renderChildren(e, sb, depth)
            }

            // TipTap 自定义块：原生端画不了，明确提示（别静默丢内容）
            "flowchart-block", "mindmap-block", "sheet-block",
            "flowchartblock", "mindmapblock", "sheetblock" -> {
                sb.append("<p>【").append(escape(tag)).append("：交互图块请在 Web 端查看】</p>")
            }

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
            if (!href.isNullOrBlank()) { open.add("<a href=\"" + escape(href) + "\">"); close.add("</a>") }

            for (o in open) sb.append(o)
            sb.append(escape(c.getText()))
            for (c2 in close.asReversed()) sb.append(c2)
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