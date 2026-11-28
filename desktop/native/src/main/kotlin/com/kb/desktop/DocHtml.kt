package com.kb.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.parser.ParserDelegator

// 文章正文 / 评论内容的原生 HTML 渲染（2026-11-13 起阅读视图不再走 WebView）。
//
// 为什么自己解析而不引第三方 HTML 库：
//   正文是服务端 HtmlSanitizer 净化过的 TipTap 富文本，标签集合有限（p/h1-6/ul/ol/li/
//   strong/em/img/pre/code/blockquote/table/a/br），引整棵 HTML 引擎既重又引入
//   不受控的执行面。解析用 JDK 自带 ParserDelegator（容错、解实体、无头可用），
//   渲染按「块级 → 段落/标题/列表/代码/表格/图片」走 Compose 原生组件。
//
// 安全：链接只放行站内相对地址与 http(s)（拦截 javascript:/data: 伪协议），
// 点击走系统浏览器；图片只取站内 uploads/emojis、data: 与 http(s)。

// ---------- 解析结果模型 ----------

sealed interface DocBlock

data class DbHead(val level: Int, val text: String) : DocBlock

/** 段落（spans 含粗体/斜体/行内码/链接等样式） */
data class DbPara(val spans: AnnotatedString) : DocBlock

/** 列表项：marker（• / 1.）、正文、缩进层级（嵌套列表 +1） */
data class DbItem(val marker: String, val spans: AnnotatedString, val indent: Int)

data class DbList(val items: List<DbItem>, val ordered: Boolean) : DocBlock

/** 代码块（等宽 + 浅灰底，超宽横向滚动） */
data class DbCode(val text: String) : DocBlock

/** 引用块：左边线 + 多行灰字 */
data class DbQuote(val lines: List<AnnotatedString>) : DocBlock

/** 图片（src 为原文相对/绝对地址，渲染时按服务器地址解析） */
data class DbImg(val src: String, val alt: String) : DocBlock

/** 表格：header 为空表示首行不是表头 */
data class DbTable(val header: List<String>, val rows: List<List<String>>) : DocBlock

object DbHr : DocBlock

/**
 * 原生端画不了的块（流程图/思维导图/表格块/内嵌视频等）。
 * 以前这类节点被静默丢弃，整篇只剩图块时上层就显示「正文暂不可解析」——
 * 用户看到的是一句没用的话；现在明确告诉用户是什么块、去哪儿看。
 */
data class DbNotice(val text: String) : DocBlock

/** 目录条目：blockIndex = 该块在 List<DocBlock> 中的下标（点击滚到该块） */
data class TocEntry(val blockIndex: Int, val level: Int, val text: String)

// ---------- 正文配色（对齐 Web 端 PostDetail/readerHtml 的阅读观感） ----------

internal val DocHeadInk = Color(0xFF1F2329)
internal val DocInk = Color(0xFF2B2F36)
internal val DocSub = Color(0xFF8A9099)
internal val DocLink = Color(0xFF3B6FF6)
internal val DocCodeBg = Color(0xFFF5F6F8)
internal val DocBorder = Color(0xFFE2E5EA)
internal val DocQuoteInk = Color(0xFF5C6470)
private const val DOC_LINK_TAG = "kb-doc-link"

// ---------- 解析 ----------

private class HElem(val tag: String) {
    val attrs = LinkedHashMap<String, String>()
    val kids = ArrayList<Any>()   // HElem 或 String（文本）
}

/** ParserDelegator 回调 → 轻量元素树（容错：未闭合标签按最近匹配回弹） */
private class TreeBuilder : HTMLEditorKit.ParserCallback() {
    val root = HElem("#root")
    private val stack = ArrayDeque<HElem>().apply { addLast(root) }

    private fun attrsOf(a: MutableAttributeSet?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (a != null) {
            // AttributeSet 只给名字枚举 + 按名取值（没有按下标取的方法）
            val names: java.util.Enumeration<*> = a.attributeNames
            while (names.hasMoreElements()) {
                val name = names.nextElement() ?: continue
                val k = name.toString().lowercase()
                out[k] = a.getAttribute(name)?.toString() ?: ""
            }
        }
        return out
    }

    override fun handleStartTag(t: HTML.Tag?, a: MutableAttributeSet?, pos: Int) {
        val name = t?.toString() ?: return
        val e = HElem(name)
        e.attrs.putAll(attrsOf(a))
        stack.last().kids.add(e)
        stack.addLast(e)
    }

    override fun handleEndTag(t: HTML.Tag?, pos: Int) {
        val name = t?.toString() ?: return
        // 找最近的同名开标签再弹栈（HTML 容错：中途夹着的未闭合标签一并出栈）
        val target = stack.toList().asReversed().firstOrNull { it.tag == name } ?: return
        while (stack.size > 1 && stack.last() !== target) stack.removeLast()
        if (stack.size > 1) stack.removeLast()
    }

    override fun handleSimpleTag(t: HTML.Tag?, a: MutableAttributeSet?, pos: Int) {
        val name = t?.toString() ?: return
        val e = HElem(name)
        e.attrs.putAll(attrsOf(a))
        stack.last().kids.add(e)   // img / br / hr 等自闭合
    }

    override fun handleText(data: CharArray?, pos: Int) {
        if (data != null && data.isNotEmpty()) stack.last().kids.add(String(data))
    }
}

object DocHtml {

    /** HTML → 块列表。解析失败返回空列表（上层显示「正文为空」兜底）。 */
    fun parse(html: String): List<DocBlock> {
        if (html.isBlank()) return emptyList()
        val builder = TreeBuilder()
        runCatching {
            ParserDelegator().parse(StringReader(html), builder, false)
        }.getOrElse { return emptyList() }
        val out = ArrayList<DocBlock>()
        walkContainer(builder.root, out)
        return out
    }

    /** 目录：真实 h1~h6 + 编号式段落（一、/ 1.1——很多人不点标题样式直接写章节）。 */
    fun tocOf(blocks: List<DocBlock>): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        blocks.forEachIndexed { i, b ->
            when {
                b is DbHead -> out.add(TocEntry(i, b.level, b.text))
                b is DbPara -> pseudoLevel(b.spans.text)
                    ?.let { lvl -> out.add(TocEntry(i, lvl, b.spans.text.trim().take(40))) }
            }
        }
        return out
    }

    /** Web 端 processContent 同款编号推断：一、→1；1.1→2；1.1.1→3（层级上限 6） */
    private fun pseudoLevel(text: String): Int? {
        val t = text.trim()
        if (Regex("^[一二三四五六七八九十百千零两]+、").containsMatchIn(t)) return 1
        val m = Regex("^(\\d+(?:\\.\\d+)+)(?=[\\s\\u4e00-\\u9fa5：:])").find(t) ?: return null
        return minOf((m.groupValues[1].count { it == '.' }) + 1, 6)
    }

    // ---- 块级遍历 ----

    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "main", "header", "footer", "figure", "figcaption",
        "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "pre", "blockquote", "table", "hr", "img",
        "dl", "dt", "dd", "address", "center",
        // 内嵌媒体：原生画不了，但要出「去 Web 看」提示，不能静默丢
        "iframe", "video", "audio", "embed", "object",
    )

    /** 纯行内标签：文本收进散段落，不产生块 */
    private val INLINE_TAGS = setOf(
        "a", "strong", "b", "em", "i", "u", "s", "del", "strike", "code", "span", "br",
        "sub", "sup", "small", "mark", "font", "cite", "q", "abbr", "ins", "tt", "kbd", "samp", "var",
    )

    /** 整块跳过（head 里的 title/style 等不进正文） */
    private val SKIP_TAGS = setOf("head", "style", "script", "title", "meta", "link", "noscript")

    private fun walkContainer(el: HElem, out: ArrayList<DocBlock>) {
        val stray = StringBuilder()
        fun flush() {
            val t = stray.toString().trim()
            if (t.isNotEmpty()) out.add(DbPara(plainSpans(t)))
            stray.clear()
        }
        for (kid in el.kids) {
            when {
                kid is String -> stray.append(kid)
                (kid as HElem).tag in SKIP_TAGS -> Unit
                kid.tag in BLOCK_TAGS -> {
                    flush()
                    emitBlock(kid, out)
                }
                kid.tag in INLINE_TAGS -> appendInline(stray, kid)
                // 未知标签（html/body/figure…）按容器下钻——绝不能拍平成文本，否则整篇塌成一段
                else -> {
                    flush()
                    walkContainer(kid, out)
                }
            }
        }
        flush()
    }

    private fun emitBlock(el: HElem, out: ArrayList<DocBlock>) {
        when (el.tag) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val text = plainText(el).trim()
                if (text.isNotEmpty()) out.add(DbHead(el.tag.last() - '0', text))
            }
            "p" -> {
                val imgs = el.kids.filterIsInstance<HElem>().filter { it.tag == "img" }
                val text = inlineOf(el)
                if (text.isNotBlank()) out.add(DbPara(text))
                imgs.forEach { img -> srcOf(img)?.let { out.add(DbImg(it, img.attrs["alt"].orEmpty())) } }
            }
            "ul", "ol" -> {
                val items = collectItems(el, ordered = el.tag == "ol", indent = 0)
                if (items.isNotEmpty()) out.add(DbList(items, el.tag == "ol"))
            }
            "pre" -> {
                val t = plainText(el).trimEnd('\n')
                if (t.isNotBlank()) out.add(DbCode(t))
            }
            "blockquote" -> {
                val lines = ArrayList<AnnotatedString>()
                for (kid in el.kids) {
                    when {
                        kid is String -> lines.add(plainSpans(kid.trim()))
                        (kid as HElem).tag == "p" -> {
                            val s = inlineOf(kid)
                            if (s.text.isNotBlank()) lines.add(s)
                        }
                        kid.tag in setOf("ul", "ol") -> collectItems(kid, kid.tag == "ol", 0)
                            .forEach { lines.add(plainSpans("　".repeat(it.indent) + it.marker + " " + it.spans.text)) }
                        else -> {
                            val s = inlineOf(kid)
                            if (s.text.isNotBlank()) lines.add(s)
                        }
                    }
                }
                if (lines.any { it.text.isNotBlank() }) out.add(DbQuote(lines.filter { it.text.isNotBlank() }))
            }
            "table" -> parseTable(el)?.let { out.add(it) }
            "hr" -> out.add(DbHr)
            "img" -> srcOf(el)?.let { out.add(DbImg(it, el.attrs["alt"].orEmpty())) }
            "iframe", "embed", "object" -> {
                // ParserDelegator 对 iframe 这类 DTD 外标签会同时产出「带属性的自闭合元素」和
                // 一个空壳元素，这里只认有 src 或真有子节点的那个，避免同一处出两条提示
                val src = el.attrs["src"]?.takeIf { it.isNotBlank() }
                when {
                    src != null -> out.add(DbNotice("内嵌网页 / 视频（$src）—— 请在 Web 端查看"))
                    el.kids.isNotEmpty() -> out.add(DbNotice("内嵌网页 / 视频 —— 请在 Web 端查看"))
                }
            }
            "video", "audio" -> {
                val src = el.attrs["src"]?.takeIf { it.isNotBlank() }
                if (src != null || el.kids.isNotEmpty()) out.add(DbNotice("音视频 —— 请在 Web 端查看"))
            }
            else -> {
                // 图块类自定义节点（流程图 / 思维导图 / 表格块）：内容在 data-* 或画布里，
                // 原生端没有对应组件；明确提示，不然整篇只剩图块时上层会误报「不可解析」
                val kind = diagramKindOf(el)
                if (kind != null) {
                    val text = plainText(el).trim()
                    out.add(DbNotice(kind + " —— 请在 Web 端查看" + if (text.isNotEmpty()) "（图内文字：$text）" else ""))
                } else {
                    walkContainer(el, out)   // div / section / figure … 递归下钻
                }
            }
        }
    }

    /**
     * 识别「图块」类自定义节点（流程图 / 思维导图 / 表格块）。
     * TipTap 导出的形态不稳定：可能是 data-type、data-diagram 属性，也可能只是 class 关键字；
     * 命中就出提示块，没命中继续按容器下钻（普通 div 不会误判）。
     */
    private fun diagramKindOf(el: HElem): String? {
        val probe = (
            el.attrs["data-type"].orEmpty() + " " +
                el.attrs["class"].orEmpty() + " " +
                el.attrs["data-diagram"].orEmpty()
            ).lowercase()
        val hasDiagramAttr = el.attrs.containsKey("data-diagram")
        return when {
            probe.contains("mindmap") || probe.contains("mind-map") -> "思维导图"
            probe.contains("flow") -> "流程图"
            probe.contains("diagram") || hasDiagramAttr -> "图形块"
            probe.contains("table-block") || probe.contains("kwtable") -> "表格块"
            else -> null
        }
    }

    private fun collectItems(listEl: HElem, ordered: Boolean, indent: Int): List<DbItem> {
        val out = ArrayList<DbItem>()
        var n = 0
        for (kid in listEl.kids) {
            if (kid !is HElem || kid.tag != "li") continue
            n++
            val marker = if (ordered) "$n." else "•"
            // li 内的行内内容（跳过嵌套列表，嵌套单独按 indent+1 收集）
            val b = buildAnnotatedString {
                for (c in kid.kids) {
                    if (c is HElem && c.tag in setOf("ul", "ol")) continue
                    if (c is HElem) appendElem(this, c) else append(c as String)
                }
            }
            if (b.text.isNotBlank()) out.add(DbItem(marker, b, indent))
            for (c in kid.kids) {
                if (c is HElem && c.tag in setOf("ul", "ol")) out.addAll(collectItems(c, c.tag == "ol", indent + 1))
            }
        }
        return out
    }

    private fun parseTable(el: HElem): DbTable? {
        val rows = ArrayList<List<String>>()
        var headerRow: List<String>? = null
        fun walk(n: HElem) {
            for (kid in n.kids) {
                if (kid !is HElem) continue
                when (kid.tag) {
                    "tr" -> {
                        val cells = kid.kids.filterIsInstance<HElem>()
                            .filter { it.tag in setOf("td", "th") }
                            .map { plainText(it).trim() }
                        if (cells.isNotEmpty()) {
                            val isTh = kid.kids.filterIsInstance<HElem>().any { it.tag == "th" }
                            if (isTh && headerRow == null && rows.isEmpty()) headerRow = cells
                            else rows.add(cells)
                        }
                    }
                    "thead", "tbody", "tfoot" -> walk(kid)
                    else -> walk(kid)
                }
            }
        }
        walk(el)
        return if (headerRow == null && rows.isEmpty()) null else DbTable(headerRow ?: emptyList(), rows)
    }

    // ---- 行内样式 ----

    private fun srcOf(img: HElem): String? = img.attrs["src"]?.takeIf { it.isNotBlank() }

    private fun plainText(el: HElem): String {
        val sb = StringBuilder()
        appendInline(sb, el)
        return sb.toString()
    }

    private fun appendInline(sb: StringBuilder, el: HElem) {
        for (kid in el.kids) {
            when {
                kid is String -> sb.append(kid)
                (kid as HElem).tag == "br" -> sb.append('\n')
                else -> appendInline(sb, kid)
            }
        }
    }

    private fun plainSpans(text: String): AnnotatedString = buildAnnotatedString { append(text) }

    private fun inlineOf(el: HElem): AnnotatedString = buildAnnotatedString { appendElem(this, el) }

    private fun appendElem(b: AnnotatedString.Builder, el: HElem) {
        for (kid in el.kids) {
            if (kid is String) {
                b.append(kid)
                continue
            }
            val c = kid as HElem
            when (c.tag) {
                "strong", "b" -> { b.pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); appendElem(b, c); b.pop() }
                "em", "i" -> { b.pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); appendElem(b, c); b.pop() }
                "u" -> { b.pushStyle(SpanStyle(textDecoration = TextDecoration.Underline)); appendElem(b, c); b.pop() }
                "s", "del", "strike" -> { b.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)); appendElem(b, c); b.pop() }
                "code" -> {
                    b.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = DocCodeBg, color = DocHeadInk))
                    appendElem(b, c); b.pop()
                }
                "a" -> {
                    val href = c.attrs["href"]
                    if (href != null && isSafeLink(href)) {
                        b.pushStringAnnotation(DOC_LINK_TAG, href)
                        b.pushStyle(SpanStyle(color = DocLink, textDecoration = TextDecoration.Underline))
                        appendElem(b, c)
                        b.pop(); b.pop()
                    } else appendElem(b, c)
                }
                "br" -> b.append('\n')
                "img" -> { /* 行内图在块级已单独出块，这里忽略 */ }
                else -> appendElem(b, c)
            }
        }
    }

    /** 链接白名单：站内相对地址与 http(s)，拦截 javascript:/data: 等伪协议 */
    private fun isSafeLink(href: String): Boolean {
        val h = href.trim()
        if (h.startsWith("//")) return false   // 协议相对地址会跳到站外，不当站内链接处理
        return !h.contains(':') || h.startsWith("http://", true) || h.startsWith("https://", true)
    }
}

// ---------- 图片加载（站内 uploads 走 /api/thumb 缩略，data: 直接解码） ----------

// 单张图片的硬上限。正文里的图可能是几十 MB 的原图，也可能是「解压炸弹」——几百 KB 的 PNG
// 声明 30000×30000 画布，解码时才炸。所以三重设限：传输字节、解码宽高、解码总像素；
// 任何一项超限都不加载、不进缓存，直接出占位文字（绝不因为一张图把整篇正文拖死或 OOM）。
// 值都放成常量，以后要调只改这里。
private const val DOC_IMG_MAX_BYTES = 10 * 1024 * 1024   // 10MB（下载/data URI 解码后字节数）
private const val DOC_IMG_MAX_EDGE = 10_000             // 宽、高各不超过 10000 像素
private const val DOC_IMG_MAX_PIXELS = 40_000_000L      // 总像素不超过 4000 万

/** 日志里 URL 的截断长度：data: 图片的 base64 动辄上 MB，绝不能整条打进日志 */
private const val DOC_IMG_LOG_URL_MAX = 200

private const val DOC_IMG_NOTE_TOO_LARGE = "图片过大，未加载（单张上限 10MB）"
private const val DOC_IMG_NOTE_BAD_SIZE = "图片尺寸异常，未加载（宽高上限 10000，总像素上限 4000 万）"

/** 图片加载结果：要么拿到位图，要么带一句「为什么没加载」的说明（渲染成占位文字） */
private sealed interface DocImgResult {
    data class Ok(val bitmap: ImageBitmap) : DocImgResult
    data class Failed(val note: String) : DocImgResult
}

/** 单图未加载日志：桌面端没有日志框架，沿用 Main.kt 的 System.err + "[kb]" 前缀。 */
private fun docImgLog(reason: String, url: String, bytes: Long?, extra: String = "") {
    val shown = if (url.length > DOC_IMG_LOG_URL_MAX) url.take(DOC_IMG_LOG_URL_MAX) + "…" else url
    val tail = if (extra.isEmpty()) "" else " $extra"
    System.err.println(
        "[kb] 正文图片未加载 reason=$reason url(len=${url.length})=$shown " +
            "bytes=${bytes ?: -1} limit=$DOC_IMG_MAX_BYTES$tail"
    )
}

private object DocImgCache {
    private const val MAX = 60

    // 按访问顺序淘汰（accessOrder=true）：长文档逐张加载、多篇之间来回翻时，
    // 位图不会无限累积（每张解码后都在堆里，没有上限迟早爆内存）。
    val map: MutableMap<String, ImageBitmap> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
                size > MAX
        }
    )

    /** 设置页展示用：当前缓存了几张已解码的正文图片（内存态，重启归零） */
    fun size(): Int = synchronized(map) { map.size }

    /** 设置页「清空图片缓存」：只清内存里的解码结果，不动磁盘与服务器 */
    fun clear() = synchronized(map) { map.clear() }
}

/** 正文图片缓存大小（张数）；设置页展示用 */
internal fun docImageCacheSize(): Int = DocImgCache.size()

/** 清空正文图片缓存（内存态），下次打开正文会重新下载解码 */
internal fun clearDocImageCache(): Int {
    val n = DocImgCache.size()
    DocImgCache.clear()
    return n
}

private val docImgHttp: HttpClient by lazy {
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
}

/** 正文图片地址解析：uploads 目录图走缩略图接口（宽度吸附 800 档），其余按服务器地址拼全。 */
internal fun resolveDocImgUrl(src: String, server: String): String {
    val root = server.trimEnd('/')
    val s = src.trim()
    return when {
        s.startsWith("data:") -> s
        s.startsWith("http://") || s.startsWith("https://") -> s
        s.startsWith("/uploads/") -> {
            val rel = s.removePrefix("/uploads/")
            "$root/api/thumb?src=${URLEncoder.encode(rel, "UTF-8")}&w=800"
        }
        else -> root + (if (s.startsWith("/")) s else "/$s")
    }
}

private fun fetchDocBitmap(url: String): DocImgResult = try {
    if (url.startsWith("data:")) decodeDataUriImage(url) else downloadDocImage(url)
} catch (e: Exception) {
    // 网络/解码异常一律降级成占位，不往上抛（否则一张坏图会中断整篇正文渲染）
    docImgLog("error", url, null, "err=${e.javaClass.simpleName}")
    DocImgResult.Failed("下载或解码失败")
}

/** 网络图：流式读、边读边计数，超过 DOC_IMG_MAX_BYTES 立刻断开，不把整个响应读进内存。 */
private fun downloadDocImage(url: String): DocImgResult {
    val req = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(15))
        .header("User-Agent", "kb-desktop/${AppInfo.versionName}")
        .GET().build()
    val resp = docImgHttp.send(req, HttpResponse.BodyHandlers.ofInputStream())
    val body: InputStream = resp.body()
    try {
        if (resp.statusCode() >= 400) {
            docImgLog("http${resp.statusCode()}", url, null)
            return DocImgResult.Failed("下载失败（HTTP ${resp.statusCode()}）")
        }
        // 服务端给了 Content-Length 就先判：超限连 body 都不读
        val declared = resp.headers().firstValueAsLong("content-length").orElse(-1L)
        if (declared > DOC_IMG_MAX_BYTES) {
            docImgLog("too-large", url, declared)
            return DocImgResult.Failed(DOC_IMG_NOTE_TOO_LARGE)
        }
        val bytes = readCapped(url, body) ?: return DocImgResult.Failed(DOC_IMG_NOTE_TOO_LARGE)
        if (bytes.isEmpty()) {
            docImgLog("empty", url, 0)
            return DocImgResult.Failed("图片内容为空")
        }
        return decodeDocImage(bytes, url)
    } finally {
        runCatching { body.close() }
    }
}

/** 边读边计数：超过 limit 立即返回 null（此时已读字节数会进日志），不占大内存。 */
private fun readCapped(url: String, input: InputStream): ByteArray? {
    val buf = ByteArrayOutputStream(64 * 1024)
    val chunk = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(chunk)
        if (n < 0) break
        if (n == 0) continue
        total += n
        if (total > DOC_IMG_MAX_BYTES) {
            docImgLog("too-large", url, total)
            return null
        }
        buf.write(chunk, 0, n)
    }
    return buf.toByteArray()
}

/** data: 分支：先按 base64 长度估算字节数，超限连解码都不做。 */
private fun decodeDataUriImage(url: String): DocImgResult {
    val comma = url.indexOf(',')
    if (comma < 0) {
        docImgLog("bad-data-uri", url, null)
        return DocImgResult.Failed("图片地址无效")
    }
    if (!url.substring(0, comma).contains(";base64", ignoreCase = true)) {
        docImgLog("bad-data-uri", url, null, "kind=non-base64")
        return DocImgResult.Failed("图片格式不支持")
    }
    val payload = url.substring(comma + 1)
    // base64 4 字符 → 3 字节：估算值足够判上限，避免为一个超大 data URI 先堆一份解码结果
    val approx = payload.length.toLong() * 3 / 4
    if (approx > DOC_IMG_MAX_BYTES) {
        docImgLog("too-large", url, approx)
        return DocImgResult.Failed(DOC_IMG_NOTE_TOO_LARGE)
    }
    val bytes = Base64.getDecoder().decode(payload)
    if (bytes.size > DOC_IMG_MAX_BYTES) {
        docImgLog("too-large", url, bytes.size.toLong())
        return DocImgResult.Failed(DOC_IMG_NOTE_TOO_LARGE)
    }
    if (bytes.isEmpty()) {
        docImgLog("empty", url, 0)
        return DocImgResult.Failed("图片内容为空")
    }
    return decodeDocImage(bytes, url)
}

/** 解码 + 像素上限校验（先读文件头拿宽高，再解码；解码后按位图尺寸兜底再校一次）。 */
private fun decodeDocImage(bytes: ByteArray, url: String): DocImgResult {
    // 只读图片头拿宽高，不分配像素缓冲：解压炸弹就是「小体积声明大画布」，等解码时才发现就晚了
    val dims = probeImageSize(bytes)
    if (dims != null && !sizeWithinLimit(dims.first, dims.second)) {
        docImgLog("too-many-pixels", url, bytes.size.toLong(), "size=${dims.first}x${dims.second}")
        return DocImgResult.Failed(DOC_IMG_NOTE_BAD_SIZE)
    }
    val bmp = loadImageBitmap(ByteArrayInputStream(bytes))
    // ImageIO 不认的格式（webp/avif 等）拿不到头，只能解码完再兜底校验
    if (!sizeWithinLimit(bmp.width, bmp.height)) {
        docImgLog("too-many-pixels", url, bytes.size.toLong(), "size=${bmp.width}x${bmp.height}(decoded)")
        return DocImgResult.Failed(DOC_IMG_NOTE_BAD_SIZE)
    }
    return DocImgResult.Ok(bmp)
}

/** 用 ImageIO 的 reader 只读元数据（setInput 不触发解码），拿不到返回 null。 */
private fun probeImageSize(bytes: ByteArray): Pair<Int, Int>? {
    val iis = runCatching { ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
    return try {
        val readers = ImageIO.getImageReaders(iis)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.setInput(iis, true, true)
            reader.getWidth(0) to reader.getHeight(0)
        } finally {
            reader.dispose()
        }
    } catch (e: Exception) {
        null
    } finally {
        runCatching { iis.close() }
    }
}

private fun sizeWithinLimit(w: Int, h: Int): Boolean =
    w in 1..DOC_IMG_MAX_EDGE && h in 1..DOC_IMG_MAX_EDGE && w.toLong() * h <= DOC_IMG_MAX_PIXELS

/** 单张正文图片：占位 → 加载 → 失败/超限提示；结果进内存缓存（同页翻回来秒开）。 */
@Composable
fun DocImage(src: String, server: String, modifier: Modifier = Modifier) {
    val url = remember(src, server) { resolveDocImgUrl(src, server) }
    var bitmap by remember(url) { mutableStateOf(DocImgCache.map[url]) }
    var failed by remember(url) { mutableStateOf(false) }
    var note by remember(url) { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        if (bitmap == null && !failed) {
            when (val r = withContext(Dispatchers.IO) { fetchDocBitmap(url) }) {
                is DocImgResult.Ok -> {
                    DocImgCache.map[url] = r.bitmap
                    bitmap = r.bitmap
                }
                is DocImgResult.Failed -> {
                    note = r.note
                    failed = true
                }
            }
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp, max = 460.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(DocCodeBg),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            // 失败/超限都走占位：主行沿用旧失败态文案，下面一行说明原因（过大 / 尺寸异常 / 网络）
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(if (failed) "图片加载失败" else "图片加载中…", fontSize = 11.5.sp, color = DocSub)
                if (failed) {
                    Text(
                        note ?: "稍后重试",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = DocSub,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

// ---------- 块级渲染 ----------

/** 可点链接的文本：有链接注解时点击开系统浏览器，其余行为与普通 Text 一致。 */
@Composable
private fun DocText(
    spans: AnnotatedString,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    val uri = LocalUriHandler.current
    var layout by remember(spans) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = spans,
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = color,
        fontWeight = fontWeight,
        onTextLayout = { layout = it },
        modifier = modifier.pointerInput(spans) {
            detectTapGestures { pos ->
                val off = layout?.getOffsetForPosition(pos) ?: return@detectTapGestures
                val link = spans.getStringAnnotations(DOC_LINK_TAG, off, off).firstOrNull() ?: return@detectTapGestures
                runCatching { uri.openUri(link.item) }
            }
        },
    )
}

/** 渲染单个块（文章正文与评论正文共用）。 */
@Composable
fun DocBlockView(b: DocBlock, server: String, modifier: Modifier = Modifier) {
    when (b) {
        is DbHead -> {
            // 阅读页大标题由页面头部承担，正文内标题按层级递减
            val (fs, top) = when (b.level) {
                1 -> 20.sp to 18.dp
                2 -> 17.5.sp to 14.dp
                3 -> 15.5.sp to 12.dp
                else -> 14.sp to 10.dp
            }
            Spacer(Modifier.height(top))
            Text(
                b.text,
                fontSize = fs,
                fontWeight = FontWeight.Bold,
                lineHeight = (fs.value + 8).sp,
                color = DocHeadInk,
                modifier = modifier.fillMaxWidth(),
            )
        }
        is DbPara -> DocText(
            b.spans, fontSize = 15.sp, lineHeight = 26.sp, color = DocInk,
            modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        )
        is DbList -> Column(modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            for (item in b.items) {
                Row(Modifier.padding(start = 8.dp + 18.dp * item.indent, bottom = 3.dp)) {
                    Text(
                        item.marker + " ",
                        fontSize = 14.5.sp,
                        lineHeight = 25.sp,
                        color = if (b.ordered) DocLink else DocSub,
                        modifier = Modifier.width(if (b.ordered) 26.dp else 16.dp),
                    )
                    DocText(
                        item.spans, fontSize = 14.5.sp, lineHeight = 25.sp, color = DocInk,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        is DbCode -> Box(
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DocCodeBg)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                b.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 20.sp,
                color = DocHeadInk,
            )
        }
        is DbQuote -> Row(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Box(Modifier.width(3.dp).heightIn(min = 24.dp).background(DocBorder))
            Spacer(Modifier.width(12.dp))
            Column {
                for (line in b.lines) {
                    DocText(line, fontSize = 14.sp, lineHeight = 24.sp, color = DocQuoteInk)
                }
            }
        }
        is DbImg -> DocImage(
            b.src, server,
            modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        is DbTable -> Column(
            modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            if (b.header.isNotEmpty()) {
                Row { for (cell in b.header) TableCell(cell, bold = true) }
            }
            for (row in b.rows) {
                Row { for (cell in row) TableCell(cell, bold = false) }
            }
        }
        DbHr -> HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = DocBorder)
        is DbNotice -> Box(
            modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFF7F8FA))
                .border(1.dp, DocBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                "▢ " + b.text,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = DocQuoteInk,
            )
        }
    }
}

@Composable
private fun TableCell(text: String, bold: Boolean) {
    Box(
        Modifier
            .widthIn(min = 64.dp, max = 340.dp)
            .background(Color.White)
            .border(1.dp, DocBorder, RoundedCornerShape(0.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text.ifBlank { " " },
            fontSize = 13.5.sp,
            lineHeight = 21.sp,
            fontWeight = if (bold) FontWeight.SemiBold else null,
            color = DocInk,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
