/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.exam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.CenterHint
import com.kb.kbplatform.shell.ui.common.TopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private val C_OK = Color(0xFF22C55E)
private val C_BAD = Color(0xFFEF4444)

/**
 * 考试作答（原生）：start（recordId/deadlineAt）→ 取卷（paper+questions）→
 * 倒计时答题（到时自动交卷）→ 交卷 → 成绩（score/passed）。
 * 与 Web 版 MobileExamTake 协议一致：items=[{questionId, answer:[label 数组]}]。
 */
@Composable
fun ExamTakeScreen(paperId: Long, titleHint: String, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()

    var paper by remember { mutableStateOf<JSONObject?>(null) }
    var questions by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var recordId by remember { mutableStateOf<Long?>(null) }
    var idx by remember { mutableIntStateOf(0) }
    var picks by remember { mutableStateOf(mapOf<Long, Set<String>>()) }
    var texts by remember { mutableStateOf(mapOf<Long, String>()) }
    var remainSec by remember { mutableIntStateOf(-1) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var confirmSubmit by remember { mutableStateOf(false) }

    fun submit(auto: Boolean) {
        val rid = recordId ?: return
        if (submitting || result != null) return
        submitting = true
        scope.launch {
            try {
                val items = questions.map { q ->
                    val qid = q.optLong("id")
                    val a: List<String> = when (q.optString("type")) {
                        "MULTI" -> picks[qid]?.sorted() ?: emptyList()
                        "FILL", "ESSAY" -> listOf((texts[qid] ?: "").trim()).filter { it.isNotEmpty() }
                        else -> picks[qid]?.toList() ?: emptyList()
                    }
                    qid to a
                }
                result = KbApi.submitExam(paperId, rid, items)
            } catch (e: Exception) {
                error = (if (auto) "自动交卷失败：" else "") + (e.message ?: "交卷失败")
            } finally {
                submitting = false
            }
        }
    }

    LaunchedEffect(paperId) {
        loading = true
        error = ""
        try {
            val rec = KbApi.startExam(paperId)
            recordId = rec.optLong("id", 0).takeIf { it > 0 }
            val detail = KbApi.examDetail(paperId)
            paper = detail.optJSONObject("paper")
            questions = detail.optJSONArray("questions")?.objects() ?: emptyList()
            val dur = paper?.optInt("durationMin", 0) ?: 0
            remainSec = if (dur > 0) dur * 60 else -1
        } catch (e: Exception) {
            error = e.message ?: "无法开始考试（可能需要先完成课程）"
        } finally {
            loading = false
        }
    }

    // 倒计时：到点自动交卷
    LaunchedEffect(remainSec) {
        if (remainSec > 0 && result == null) {
            delay(1000)
            remainSec -= 1
            if (remainSec == 0) submit(true)
        }
    }
    DisposableEffect(Unit) { onDispose { } }

    val mmss = remember(remainSec) {
        if (remainSec < 0) "" else "%02d:%02d".format(remainSec / 60, remainSec % 60)
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = titleHint, onBack = onExit)

        val r = result
        if (loading) {
            CenterHint("开考中…", loading = true)
        } else if (r != null) {
            ResultPanel(r, onExit)
        } else if (error.isNotEmpty() && questions.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                CenterHint(error)
                TextButton(onClick = onExit) { Text("返回") }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${idx + 1} / ${questions.size}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (mmss.isNotEmpty()) {
                    Text(
                        "⏱ $mmss",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (remainSec in 0..300) C_BAD else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    val q = questions[idx]
                    Text(
                        mapOf("SINGLE" to "单选", "MULTI" to "多选", "JUDGE" to "判断",
                            "FILL" to "填空", "ESSAY" to "简答")
                            .getOrDefault(q.optString("type"), q.optString("type")),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(q.optString("content"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                val q = questions[idx]
                val qid = q.optLong("id")
                val type = q.optString("type")
                if (type == "FILL" || type == "ESSAY") {
                    item {
                        OutlinedTextField(
                            value = texts[qid] ?: "",
                            onValueChange = { texts = texts + (qid to it) },
                            placeholder = { Text("输入答案") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    val opts = q.optJSONArray("options")?.objects() ?: emptyList()
                    items(opts.size) { i ->
                        val opt = opts[i]
                        val label = opt.optString("label")
                        val picked = picks[qid]?.contains(label) == true
                        KbCard(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (picked) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                else MaterialTheme.colorScheme.surface
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                if (picked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val cur = picks[qid] ?: emptySet()
                                    picks = picks + (qid to if (type == "MULTI") {
                                        if (label in cur) cur - label else cur + label
                                    } else setOf(label))
                                }
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(24.dp)
                                        .background(
                                            if (picked) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                            if (type == "MULTI") RoundedCornerShape(6.dp) else CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, fontSize = 12.sp, color = Color.White,
                                        fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(opt.optString("content"), fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            // 答题卡
            LazyVerticalGrid(
                columns = GridCells.Fixed(10),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(questions.size) { i ->
                    val qid = questions[i].optLong("id")
                    val answered = picks[qid]?.isNotEmpty() == true ||
                        (texts[qid] ?: "").trim().isNotEmpty()
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(
                                if (answered) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape
                            )
                            .clickable { idx = i },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${i + 1}", fontSize = 11.sp, color = Color.White)
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { if (idx > 0) idx -= 1 },
                    enabled = idx > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("上一题") }
                if (idx + 1 < questions.size) {
                    Button(onClick = { idx += 1 }, modifier = Modifier.weight(1f)) { Text("下一题") }
                } else {
                    Button(onClick = { confirmSubmit = true }, modifier = Modifier.weight(1f)) { Text("交 卷") }
                }
            }
        }
    }

    if (confirmSubmit) {
        AlertDialog(
            onDismissRequest = { confirmSubmit = false },
            title = { Text("确认交卷？") },
            text = {
                val unanswered = questions.count { q ->
                    val qid = q.optLong("id")
                    picks[qid].isNullOrEmpty() && (texts[qid] ?: "").trim().isEmpty()
                }
                Text(if (unanswered > 0) "还有 $unanswered 题未作答，交卷后不可修改。" else "交卷后不可修改。")
            },
            confirmButton = {
                Button(onClick = { confirmSubmit = false; submit(false) }) {
                    Text(if (submitting) "交卷中…" else "确认交卷")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSubmit = false }) { Text("再检查") }
            }
        )
    }
}

@Composable
private fun ResultPanel(r: JSONObject, onExit: () -> Unit) {
    val passed = r.optBoolean("passed", false)
    val score = r.optInt("score", 0)
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(50.dp))
        Text(if (passed) "🎉" else "😥", fontSize = 44.sp)
        Spacer(Modifier.height(10.dp))
        Text("考试完成", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "$score 分 · ${if (passed) "通过" else "未通过"}",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (passed) C_OK else C_BAD
        )
        Spacer(Modifier.height(30.dp))
        Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("返回考试中心") }
    }
}
