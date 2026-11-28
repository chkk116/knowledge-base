package com.kb.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box as LBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 管理：分类标签（2026-11-16）
//   分类是树（/api/categories 直接回递归 children），标签是扁平带文章计数（/api/tags）。
//   增删改走 /api/admin/categories|tags（TAXONOMY_MANAGE）；删除分类时后端会拒绝「有子分类」的情况，文案原样透出。

@Composable
internal fun TaxonomyTab(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()
    var tree by remember { mutableStateOf<List<CatItem>>(emptyList()) }
    var tags by remember { mutableStateOf<List<TagItem>>(emptyList()) }
    var sel by remember { mutableStateOf<Long?>(null) }
    var catName by remember { mutableStateOf("") }
    var tagName by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmingCat by remember { mutableStateOf<Long?>(null) }
    var confirmingTag by remember { mutableStateOf<Long?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 切换服务器/账号：分类/标签与选中项都属于上一个会话，清掉后再拉，
    // 避免旧选中分类配新数据继续执行改名/删除/排序
    LaunchedEffect(session.server, session.token) {
        tree = emptyList()
        tags = emptyList()
        sel = null
        confirmingCat = null
        confirmingTag = null
    }

    LaunchedEffect(session.server, session.token, tick) {
        loading = true
        err = null
        try {
            tree = api.categoryTree(session.token)
            tags = api.tagList(session.token)
            if (sel != null && findCat(tree, sel!!) == null) sel = null
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    val rows = remember(tree) { flattenCats(tree) }
    val selName = remember(tree, sel) { sel?.let { findCat(tree, it)?.name } }
    val selNode = remember(tree, sel) { sel?.let { findCat(tree, it) } }
    val siblings = remember(tree, selNode) {
        selNode.let { n ->
            if (n == null) emptyList()
            else if (n.parentId == null) tree
            else findCat(tree, n.parentId)?.children.orEmpty()
        }
    }
    val selIdx = remember(siblings, sel) { siblings.indexOfFirst { it.id == sel } }
    val shownTags = remember(tags, q) {
        val kw = q.trim()
        if (kw.isEmpty()) tags else tags.filter { it.name.contains(kw, ignoreCase = true) }
    }

    fun run(block: suspend () -> Unit, ok: String? = null) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                block()
                catName = ""
                tagName = ""
                toast = ok ?: "已保存"
                tick++
            } catch (e: Exception) {
                toast = e.message ?: "操作失败"
            } finally {
                busy = false
            }
        }
    }

    /** 同级上移/下移：交换 sortOrder；两者相同则按方向错开一位（后端按 sortOrder asc, id asc 稳定排序） */
    fun moveCat(delta: Int) {
        val n = selNode ?: return
        val other = siblings.getOrNull(selIdx + delta) ?: return
        if (busy) return
        scope.launch {
            busy = true
            try {
                val same = n.sortOrder == other.sortOrder
                val nOrder = if (same) other.sortOrder + delta else other.sortOrder
                val oOrder = if (same) n.sortOrder - delta else n.sortOrder
                api.updateCategoryOrder(session.token, n.id, n.parentId, nOrder)
                api.updateCategoryOrder(session.token, other.id, other.parentId, oOrder)
                toast = if (delta < 0) "已上移" else "已下移"
                tick++
            } catch (e: Exception) {
                toast = e.message ?: "排序调整失败"
            } finally {
                busy = false
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        // 左：分类树
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            AdminHeader("分类", "${rows.size} 个", onRefresh = { tick++ })
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = catName,
                    onValueChange = { catName = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(if (sel == null) "新分类名称（加为根）" else "分类名称", fontSize = 11.5.sp, color = KbTextSub) },
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KbPrimary, unfocusedBorderColor = KbBorder),
                )
                Spacer(Modifier.width(6.dp))
                AdminButton("加根", enabled = !busy) {
                    val n = catName.trim()
                    if (n.isEmpty()) { toast = "请填写分类名称"; return@AdminButton }
                    run({ api.createCategory(session.token, n, null) }, "已添加根分类")
                }
                if (sel != null) {
                    Spacer(Modifier.width(6.dp))
                    AdminButton("加子类", enabled = !busy) {
                        val n = catName.trim()
                        val pid = sel ?: return@AdminButton
                        if (n.isEmpty()) { toast = "请填写分类名称"; return@AdminButton }
                        run({ api.createCategory(session.token, n, pid) }, "已添加子分类")
                    }
                }
            }
            if (sel != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("选中：${selName ?: "#$sel"}", fontSize = 10.5.sp, color = KbTextSub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (selIdx > 0) {
                        AdminButton("↑", enabled = !busy) { moveCat(-1) }
                    }
                    if (selIdx >= 0 && selIdx < siblings.lastIndex) {
                        AdminButton("↓", enabled = !busy) { moveCat(1) }
                    }
                    AdminButton("重命名", enabled = !busy) {
                        val n = catName.trim()
                        val id = sel ?: return@AdminButton
                        if (n.isEmpty()) { toast = "请填写新名称"; return@AdminButton }
                        run({ api.renameCategory(session.token, id, n) }, "已重命名")
                    }
                    if (confirmingCat == sel) {
                        AdminButton("确认删除", danger = true, enabled = !busy) {
                            val id = sel ?: return@AdminButton
                            run({ api.deleteCategory(session.token, id); sel = null }, "已删除分类")
                            confirmingCat = null
                        }
                        AdminButton("取消", enabled = !busy) { confirmingCat = null }
                    } else {
                        AdminButton("删除", danger = true, enabled = !busy) { confirmingCat = sel }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = KbBorder)
            Box(Modifier.weight(1f)) {
                when {
                    loading -> KbSkeletonList()
                    err != null -> AdminError(err ?: "", { tick++ })
                    rows.isEmpty() -> AdminHint("还没有分类")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(rows.size) { i ->
                            val (c, depth) = rows[i]
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { sel = c.id }
                                    .background(if (c.id == sel) KbSidebarSelected else Color.Transparent)
                                    .padding(start = 12.dp + 16.dp * depth, end = 12.dp, top = 7.dp, bottom = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LBox(
                                    Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(
                                        if (depth == 0) KbPrimary else Color(0xFFCBD5E1),
                                    ),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    c.name.ifBlank { "（未命名）" },
                                    fontSize = if (depth == 0) 13.sp else 12.5.sp,
                                    fontWeight = if (depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                                    color = KbTextMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(KbBorder))

        // 右：标签
        Column(Modifier.weight(1f).fillMaxHeight()) {
            AdminHeader("标签", "共 ${tags.size} 个", onRefresh = { tick++ })
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchBox(q, { q = it }, Modifier.weight(1f), "搜索标签")
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(
                    value = tagName,
                    onValueChange = { tagName = it },
                    modifier = Modifier.width(200.dp),
                    singleLine = true,
                    placeholder = { Text("新标签名称", fontSize = 11.5.sp, color = KbTextSub) },
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = KbPrimary, unfocusedBorderColor = KbBorder),
                )
                Spacer(Modifier.width(6.dp))
                AdminButton("新增", enabled = !busy) {
                    val n = tagName.trim()
                    if (n.isEmpty()) { toast = "请填写标签名称"; return@AdminButton }
                    run({ api.createTag(session.token, n) }, "已添加标签")
                }
            }
            // 标签可能上百条：本地切片分页，与后端列表保持同一套分页条
            var page by remember(shownTags) { mutableStateOf(0) }
            val pageSize = 40
            val pageTags = remember(shownTags, page) { shownTags.drop(page * pageSize).take(pageSize) }
            Spacer(Modifier.height(6.dp))
            KbTableHead(listOf("标签" to 4f, "篇数" to 1f, "操作" to 1.2f))
            Box(Modifier.weight(1f)) {
                when {
                    loading -> KbSkeletonList()
                    shownTags.isEmpty() -> AdminHint(if (q.isNotBlank()) "没有匹配的标签" else "还没有标签")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(pageTags, key = { it.id }) { t ->
                            KbTableRow(vertical = 6) {
                                Text(
                                    t.name,
                                    fontSize = 12.5.sp,
                                    color = KbTextMain,
                                    modifier = Modifier.weight(4f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${t.count}",
                                    fontSize = 11.sp,
                                    color = KbTextSub,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                Row(Modifier.weight(1.2f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (confirmingTag == t.id) {
                                        AdminButton("确认删除", danger = true, enabled = !busy) {
                                            run({ api.deleteTag(session.token, t.id) }, "已删除标签")
                                            confirmingTag = null
                                        }
                                        AdminButton("取消", enabled = !busy) { confirmingTag = null }
                                    } else {
                                        AdminButton("删除", danger = true, enabled = !busy) { confirmingTag = t.id }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (shownTags.isNotEmpty()) {
                HorizontalDivider(color = KbBorder)
                KbPager(total = shownTags.size.toLong(), page = page, size = pageSize, onPage = { page = it })
            }
            Text(
                "标签只影响筛选与统计，不会改动文章正文里的原始标签文本",
                fontSize = 10.sp,
                color = KbTextSub,
                maxLines = 2,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            )
        }
    }
    AdminToast(toast)
}

private fun flattenCats(list: List<CatItem>, depth: Int = 0): List<Pair<CatItem, Int>> {
    val out = ArrayList<Pair<CatItem, Int>>()
    fun walk(items: List<CatItem>, d: Int) {
        if (d > 20) return
        for (c in items) {
            out.add(c to d)
            walk(c.children, d + 1)
        }
    }
    walk(list, depth)
    return out
}

private fun findCat(list: List<CatItem>, id: Long): CatItem? {
    for (c in list) {
        if (c.id == id) return c
        findCat(c.children, id)?.let { return it }
    }
    return null
}