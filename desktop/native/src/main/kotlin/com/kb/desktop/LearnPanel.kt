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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import java.awt.Desktop
import java.net.URI

// 培训（课程/章节）与考试（试卷/我的成绩）—— 2026-11-16 新增
//   ⚠️ 在线作答/练习会话涉及限时、服务端会话与判分（MATERIAL 见 exam 相关规则），
//   桌面端第一版只做「浏览 + 章节完成标记 + 成绩回看」，作答入口一律回 Web 端。

private val LearnRailBg = Color(0xFFFAFBFC)
private val LearnErrRed = Color(0xFFDC2626)

// ---------------- 培训 ----------------

@Composable
fun CoursesPanel(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    val scope = rememberCoroutineScope()

    var cats by remember { mutableStateOf<List<String>>(emptyList()) }
    var cat by remember { mutableStateOf<String?>(null) }
    var courses by remember { mutableStateOf<List<CourseItem>>(emptyList()) }
    var courseId by remember { mutableStateOf<Long?>(null) }
    var detail by remember { mutableStateOf<CourseDetail?>(null) }
    var progress by remember { mutableStateOf<List<ChapterProgress>>(emptyList()) }
    var chapterId by remember { mutableStateOf<Long?>(null) }

    var listLoading by remember { mutableStateOf(true) }
    var detailLoading by remember { mutableStateOf(false) }
    var listErr by remember { mutableStateOf<String?>(null) }
    var detailErr by remember { mutableStateOf<String?>(null) }
    var listTick by remember { mutableStateOf(0) }
    var detailTick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        cats = runCatching { api.courseCategories(session.token) }.getOrDefault(emptyList())
    }

    LaunchedEffect(cat, listTick) {
        listLoading = true
        listErr = null
        try {
            courses = api.courses(session.token, cat)
            if (courseId == null || courses.none { it.id == courseId }) courseId = courses.firstOrNull()?.id
        } catch (e: Exception) {
            listErr = e.message ?: "加载失败"
        } finally {
            listLoading = false
        }
    }

    LaunchedEffect(courseId, detailTick) {
        val cid = courseId ?: return@LaunchedEffect
        detailLoading = true
        detailErr = null
        detail = null
        progress = emptyList()
        chapterId = null
        try {
            detail = api.courseDetail(session.token, cid)
            progress = runCatching { api.courseProgress(session.token, cid) }.getOrDefault(emptyList())
            chapterId = detail?.chapters?.firstOrNull()?.id
        } catch (e: Exception) {
            detailErr = e.message ?: "加载失败"
        } finally {
            detailLoading = false
        }
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2600)
            toast = null
        }
    }

    val finished = progress.filter { it.finished }.map { it.chapterId }.toSet()
    val total = detail?.chapters?.size ?: 0
    val done = detail?.chapters?.count { it.id in finished } ?: 0
    val current = detail?.chapters?.firstOrNull { it.id == chapterId }

    fun mark(finish: Boolean) {
        val cid = courseId ?: return
        val chid = chapterId ?: return
        if (busy) return
        scope.launch {
            busy = true
            try {
                api.setChapterFinished(session.token, cid, chid, finish)
                progress = api.courseProgress(session.token, cid)
                toast = if (finish) "已标记完成" else "已重置本章进度"
            } catch (e: Exception) {
                toast = e.message ?: "操作失败"
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            // ---- 左：分类 + 课程 ----
            Column(Modifier.width(232.dp).fillMaxHeight().background(LearnRailBg)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("培训课程", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { listTick++ }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("刷新", fontSize = 11.sp)
                    }
                }
                if (cats.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item { LearnChip("全部", cat == null) { cat = null } }
                        items(cats) { c -> LearnChip(c, cat == c) { cat = c } }
                    }
                }
                HorizontalDivider(color = KbBorder)
                Box(Modifier.weight(1f)) {
                    when {
                        listLoading -> KbSkeletonList(rows = 6)
                        listErr != null -> LearnError(listErr ?: "", onRetry = { listTick++ })
                        courses.isEmpty() -> LearnHint("还没有课程\n可在 Web 端创建课程")
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(courses, key = { it.id }) { c ->
                                CourseRow(c, sel = c.id == courseId) { courseId = c.id }
                            }
                        }
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(KbBorder))

            // ---- 中：章节 ----
            Column(Modifier.width(228.dp).fillMaxHeight()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    val c = detail?.course
                    Text(
                        c?.title ?: "课程章节",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = KbTextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (total > 0) {
                        Spacer(Modifier.height(5.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("已完成 $done / $total", fontSize = 10.5.sp, color = KbTextSub)
                            Spacer(Modifier.width(8.dp))
                            ProgressBar(
                                fraction = if (total == 0) 0f else done.toFloat() / total,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                HorizontalDivider(color = KbBorder)
                Box(Modifier.weight(1f)) {
                    val chs = detail?.chapters.orEmpty()
                    when {
                        courseId == null -> LearnHint("先在左侧选择课程")
                        detailLoading -> KbSkeletonList(rows = 6)
                        detailErr != null -> LearnError(detailErr ?: "", onRetry = { detailTick++ })
                        chs.isEmpty() -> LearnHint("这门课程还没有章节")
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(chs, key = { it.id }) { ch ->
                                ChapterRow(
                                    index = chs.indexOf(ch) + 1,
                                    title = ch.title,
                                    finished = ch.id in finished,
                                    sel = ch.id == chapterId,
                                ) { chapterId = ch.id }
                            }
                        }
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(KbBorder))

            // ---- 右：章节正文 ----
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val ch = current
                when {
                    courseId == null -> LearnHint("选择课程与章节后在这里阅读")
                    chapterId == null -> LearnHint("在中间列表选择章节")
                    detailLoading -> KbSkeletonList(rows = 6)
                    ch == null -> KbSkeletonList(rows = 6)
                    else -> CourseChapterReader(
                        chapter = ch,
                        finished = ch.id in finished,
                        busy = busy,
                        onFinish = { mark(true) },
                        onReset = { mark(false) },
                        server = session.server,
                        onOpenWeb = { openLearnInBrowser(session.server, "/courses/${courseId}") },
                        onSave = { html -> api.updateCourseChapter(session.token, ch.id, html) },
                        onSaved = { detailTick++ },
                    )
                }
            }
        }

        val t = toast
        if (t != null) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF323232))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text(t, fontSize = 12.sp, color = Color.White, maxLines = 2) }
        }
    }
}

@Composable
private fun CourseRow(c: CourseItem, sel: Boolean, onClick: () -> Unit) {
    // 课程行补 hover：主从布局的左栏如果只有选中态，鼠标扫过毫无反馈
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .background(
                when {
                    sel -> KbSidebarSelected
                    hovered -> KbHoverBg
                    else -> Color.Transparent
                }
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            c.title.ifBlank { "（无标题）" },
            fontSize = 12.5.sp,
            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
            color = KbTextMain,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            listOfNotNull(
                c.category?.takeIf { it.isNotBlank() },
                c.ownerName?.takeIf { it.isNotBlank() },
                if (c.examPaperId != null) "含考试" else null,
            ).joinToString(" · ").ifBlank { "课程" },
            fontSize = 10.5.sp,
            color = KbTextSub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChapterRow(index: Int, title: String, finished: Boolean, sel: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (sel) KbSidebarSelected else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (finished) Color(0xFFD1FAE5) else Color(0xFFF1F5F9)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (finished) "✓" else "$index",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (finished) Color(0xFF059669) else KbTextSub,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            title.ifBlank { "未命名章节" },
            fontSize = 12.5.sp,
            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
            color = KbTextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CourseChapterReader(
    chapter: ChapterItem,
    finished: Boolean,
    busy: Boolean,
    onFinish: () -> Unit,
    onReset: () -> Unit,
    server: String,
    onOpenWeb: () -> Unit,
    onSave: suspend (String) -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var editing by remember(chapter.id) { mutableStateOf(false) }
    var draft by remember(chapter.id) { mutableStateOf(chapter.content) }
    // 按章节 id 记忆：中途切到别的章节时，旧章节的「保存中…」与错误提示不会串到新章节上
    // （在途请求写回的是已被丢弃的那份状态，无副作用）
    var saving by remember(chapter.id) { mutableStateOf(false) }
    var editNote by remember(chapter.id) { mutableStateOf("") }

    if (editing) {
        // 章节正文编辑：管理员直接在桌面改，不必再开 Web（后端 PUT /admin/courses/chapters/{id}）
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
            Text(
                "编辑章节正文 · ${chapter.title.ifBlank { "未命名章节" }}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = KbTextMain,
            )
            Text(
                "与 Web 端同格式（简单 HTML）；其余字段不动",
                fontSize = 10.5.sp,
                color = KbTextSub,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (editNote.isNotEmpty()) {
                Text(editNote, fontSize = 11.sp, color = Color(0xFFB45309), modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = KbPrimary,
                    unfocusedBorderColor = KbBorder,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LearnButton(if (saving) "保存中…" else "保存", primary = true) {
                    if (saving) return@LearnButton
                    saving = true
                    editNote = ""
                    scope.launch {
                        try {
                            onSave(draft)
                            editing = false
                            onSaved()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // 离开页面/切课程会取消这个 scope：取消不是失败，继续向上抛，
                            // 否则会被当成「保存失败」写到已经销毁的状态里
                            throw e
                        } catch (e: Exception) {
                            editNote = e.message ?: "保存失败（需要「课程管理」权限）"
                        } finally {
                            saving = false
                        }
                    }
                }
                LearnButton("取消", primary = false) {
                    if (!saving) {
                        draft = chapter.content
                        editing = false
                        editNote = ""
                    }
                }
            }
        }
        return
    }

    val blocks = remember(chapter.id, chapter.content) { DocHtml.parse(chapter.content) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 24.dp, top = 16.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        chapter.title.ifBlank { "未命名章节" },
                        fontSize = 20.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = KbTextMain,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenWeb, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("在 Web 端打开", fontSize = 11.5.sp)
                    }
                    TextButton(
                        onClick = { draft = chapter.content; editNote = ""; editing = true },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("编辑正文", fontSize = 11.5.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (finished) {
                        // 状态与操作分开：标签只表示状态，重置是明确的按钮（避免把状态标签当按钮）
                        LearnChip("✓ 已完成", true, onClick = {})
                        LearnButton(if (busy) "提交中…" else "重置进度", primary = false) {
                            if (!busy) onReset()
                        }
                    } else {
                        LearnButton(if (busy) "提交中…" else "标记完成", primary = true) {
                            if (!busy) onFinish()
                        }
                    }
                    if (chapter.articleId != null) {
                        Text("关联文章 #${chapter.articleId}", fontSize = 10.5.sp, color = KbTextSub)
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = KbBorder)
                Spacer(Modifier.height(14.dp))
            }
        }
        if (blocks.isEmpty()) {
            item {
                Text(
                    if (chapter.content.isBlank()) "本章暂无正文，可在 Web 端编辑。" else "正文暂不可解析，请在 Web 端查看。",
                    fontSize = 12.5.sp,
                    color = KbTextSub,
                )
            }
        } else {
            items(blocks.size) { i ->
                DocBlockView(blocks[i], server = server, Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------------- 考试 ----------------

@Composable
fun ExamsPanel(session: Session) {
    val api = remember(session) { ApiClient(session.server) }
    var papers by remember { mutableStateOf<List<ExamPaperItem>>(emptyList()) }
    var records by remember { mutableStateOf<List<ExamRecordItem>>(emptyList()) }
    var paperId by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        loading = true
        err = null
        try {
            papers = api.examPapers(session.token)
            records = runCatching { api.examRecords(session.token) }.getOrDefault(emptyList())
            if (paperId == null || papers.none { it.id == paperId }) paperId = papers.firstOrNull()?.id
        } catch (e: Exception) {
            err = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    val paper = papers.firstOrNull { it.id == paperId }
    val mine = records.filter { it.paperId == paperId }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(258.dp).fillMaxHeight().background(LearnRailBg)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("考试", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = KbTextMain)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { tick++ }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("刷新", fontSize = 11.sp)
                }
            }
            HorizontalDivider(color = KbBorder)
            Box(Modifier.weight(1f)) {
                when {
                    loading -> KbSkeletonList(rows = 6)
                    err != null -> LearnError(err ?: "", onRetry = { tick++ })
                    papers.isEmpty() -> LearnHint("暂无已发布的试卷")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(papers, key = { it.id }) { p ->
                            ExamRow(p, sel = p.id == paperId) { paperId = p.id }
                        }
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(KbBorder))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val p = paper
            when {
                paperId == null -> LearnHint("选择一份试卷查看信息")
                loading -> KbSkeletonList(rows = 6)
                p == null -> LearnHint("试卷不存在")
                else -> ExamDetail(
                    paper = p,
                    records = mine,
                    onOpenWeb = { openLearnInBrowser(session.server, "/exams/${p.id}") },
                    onOpenPractice = { openLearnInBrowser(session.server, "/exams/${p.id}/practice") },
                )
            }
        }
    }
}

@Composable
private fun ExamRow(p: ExamPaperItem, sel: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (sel) KbSidebarSelected else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            p.title.ifBlank { "考试 #${p.id}" },
            fontSize = 12.5.sp,
            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
            color = KbTextMain,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            listOf(
                "${p.questionCount} 题",
                if (p.durationMin > 0) "${p.durationMin} 分钟" else "不限时",
                "及格 ${p.passScore}",
            ).joinToString(" · "),
            fontSize = 10.5.sp,
            color = KbTextSub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ExamDetail(
    paper: ExamPaperItem,
    records: List<ExamRecordItem>,
    onOpenWeb: () -> Unit,
    onOpenPractice: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Text(paper.title.ifBlank { "考试 #${paper.id}" }, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
        if (paper.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                paper.description.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim(),
                fontSize = 12.5.sp,
                color = KbTextSub,
                lineHeight = 19.sp,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            ExamMetric("题数", "${paper.questionCount}", Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            ExamMetric("总分", "${paper.totalScore}", Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            ExamMetric("及格", "${paper.passScore}", Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            ExamMetric("限时", if (paper.durationMin > 0) "${paper.durationMin} 分钟" else "不限时", Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            LearnButton("在 Web 端参加考试", primary = true, onClick = onOpenWeb)
            if (paper.practiceEnabled) {
                LearnButton("在 Web 端练习", primary = false, onClick = onOpenPractice)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "桌面端暂不提供在线作答（限时与判分在服务端会话中）",
            fontSize = 10.5.sp,
            color = KbTextSub,
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = KbBorder)
        Spacer(Modifier.height(12.dp))
        Text("我的成绩", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
        Spacer(Modifier.height(6.dp))
        if (records.isEmpty()) {
            Text("还没有参加记录", fontSize = 12.sp, color = KbTextSub)
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(records, key = { it.id }) { r ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${r.score} 分",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (r.passed) Color(0xFF059669) else LearnErrRed,
                            modifier = Modifier.width(58.dp),
                        )
                        Text(
                            if (r.passed) "通过" else "未通过",
                            fontSize = 11.5.sp,
                            color = if (r.passed) Color(0xFF059669) else LearnErrRed,
                            modifier = Modifier.width(52.dp),
                        )
                        if (r.pendingCount > 0) {
                            Text("待阅卷 ${r.pendingCount} 题", fontSize = 11.sp, color = Color(0xFFB45309))
                            Spacer(Modifier.width(8.dp))
                        }
                        // 时间与题数合并成可省略文本 + weight，避免整行撑出右边界
                        Text(
                            listOf(fmtLearnTime(r.submittedAt), "${r.questionCount} 题").joinToString(" · "),
                            fontSize = 11.sp,
                            color = KbTextSub,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExamMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(1.dp, KbBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 10.5.sp, color = KbTextSub)
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = KbTextMain)
    }
}

@Composable
private fun LearnButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = if (primary) Color.White else KbPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) KbPrimary else Color.White)
            .border(1.dp, if (primary) KbPrimary else KbPrimary.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

// ---------------- 通用小件 ----------------

@Composable
private fun LearnChip(label: String, sel: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 10.5.sp,
        color = if (sel) KbPrimary else KbTextSub,
        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (sel) KbSidebarSelected else Color(0xFFF1F5F9))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFE5E7EB))) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(KbPrimary),
        )
    }
}

@Composable
private fun LearnHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = KbTextSub, textAlign = TextAlign.Center, lineHeight = 19.sp)
    }
}

@Composable
private fun LearnError(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, fontSize = 12.sp, color = LearnErrRed, textAlign = TextAlign.Center, maxLines = 4)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRetry) { Text("重试", fontSize = 12.sp) }
    }
}

private fun fmtLearnTime(raw: String?): String {
    if (raw.isNullOrBlank()) return "—"
    return raw.replace('T', ' ').take(16)
}

private fun openLearnInBrowser(server: String, path: String) {
    val base = server.trimEnd('/')
    val url = if (path.startsWith("http")) path else "$base/${path.trimStart('/')}"
    runCatching { Desktop.getDesktop().browse(URI.create(url)) }
}