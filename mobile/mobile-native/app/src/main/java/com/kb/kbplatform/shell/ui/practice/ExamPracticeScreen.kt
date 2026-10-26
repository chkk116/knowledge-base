/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.practice

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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
import org.json.JSONObject

private val C_OK = Color(0xFF22C55E)
private val C_BAD = Color(0xFFEF4444)

private data class QResult(
    val correct: Boolean,
    val manual: Boolean,
    val correctAnswer: List<String>,   // "A. 选项内容"
    val analysis: String
)

/**
 * 考试练习（原生）：POST /exams/practice/session 开会话（随机抽题），
 * 逐题作答 POST /exams/practice/check 立即判分看解析；答题卡自由跳题；
 * 结束给正确率与错题回顾——与 Web 版 practiceSession 的流程一致。
 */
@Composable
fun ExamPracticeScreen() {
    val scope = rememberCoroutineScope()

    var questions by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var sessionId by remember { mutableStateOf<Long?>(null) }
    var idx by remember { mutableIntStateOf(0) }
    var picks by remember { mutableStateOf(mapOf<Long, Set<String>>()) }     // 选择题作答
    var texts by remember { mutableStateOf(mapOf<Long, String>()) }          // 填空/简答作答
    var results by remember { mutableStateOf(mapOf<Long, QResult>()) }
    var checking by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var finished by remember { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf(0L) }

    fun load() {
        loading = true
        error = ""
        scope.launch {
            try {
                val r = KbApi.startPractice(10)
                sessionId = r.optLong("sessionId", 0).takeIf { it > 0 }
                questions = r.optJSONArray("questions")?.objects() ?: emptyList()
                idx = 0; picks = emptyMap(); texts = emptyMap(); results = emptyMap()
                finished = questions.isEmpty()
                startedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    fun submit() {
        val sid = sessionId ?: return
        val q = questions.getOrNull(idx) ?: return
        if (checking || results.containsKey(q.optLong("id"))) return
        val type = q.optString("type")
        val answer: List<String> = when {
            type == "MULTI" -> picks[q.optLong("id")]?.sorted() ?: emptyList()
            listOf("FILL", "ESSAY").contains(type) ->
                listOf((texts[q.optLong("id")] ?: "").trim()).filter { it.isNotEmpty() }
            else -> picks[q.optLong("id")]?.toList() ?: emptyList()
        }
        if (answer.isEmpty()) return
        checking = true
        scope.launch {
            try {
                val r = KbApi.practiceCheck(sid, q.optLong("id"), answer)
                val ca = r.optJSONArray("correctAnswer")?.objects()?.map {
                    "${it.optString("label")}. ${it.optString("content")}"
                } ?: emptyList()
                results = results + (q.optLong("id") to QResult(
                    correct = r.optBoolean("correct"),
                    manual = r.optBoolean("manual"),
                    correctAnswer = ca,
                    analysis = r.optString("analysis")
                ))
            } catch (e: Exception) {
                error = e.message ?: "判分失败"
            } finally {
                checking = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "模拟练习")

        if (loading) {
            CenterHint("出题中…", loading = true)
        } else if (error.isNotEmpty() && questions.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                CenterHint(error)
                TextButton(onClick = { load() }) { Text("重试") }
            }
        } else if (finished) {
            FinishSummary(
                total = questions.size,
                results = results,
                wrongIds = questions.filter { results[it.optLong("id")]?.correct == false }.map { it },
                onRestart = { finished = false; load() }
            )
        } else {
            val q = questions[idx]
            val qid = q.optLong("id")
            val type = q.optString("type")
            val isMulti = type == "MULTI"
            val isText = listOf("FILL", "ESSAY").contains(type)
            val result = results[qid]

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${idx + 1} / ${questions.size}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                val judged = results.values.count { !it.manual }
                val correct = results.values.count { !it.manual && it.correct }
                Text(
                    if (judged > 0) "已答 ${results.size} · 正确率 ${correct * 100 / judged}%" else "已答 ${results.size}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(q.optString("content"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                if (!isText) {
                    val opts = q.optJSONArray("options")?.objects() ?: emptyList()
                    items(opts.size) { i ->
                        val opt = opts[i]
                        val label = opt.optString("label")
                        val picked = picks[qid]?.contains(label) == true
                        val state = when {
                            result == null -> if (picked) "pick" else "idle"
                            result.correctAnswer.any { it.startsWith("$label.") } -> "ok"
                            picked -> "bad"
                            else -> "idle"
                        }
                        OptionRow(
                            label = label,
                            content = opt.optString("content"),
                            state = state,
                            enabled = result == null,
                            multi = isMulti,
                            onClick = {
                                val cur = picks[qid] ?: emptySet()
                                picks = picks + (qid to if (isMulti) {
                                    if (label in cur) cur - label else cur + label
                                } else setOf(label))
                            }
                        )
                    }
                } else {
                    item {
                        OutlinedTextField(
                            value = texts[qid] ?: "",
                            onValueChange = { texts = texts + (qid to it) },
                            placeholder = { Text("输入答案") },
                            enabled = result == null,
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                result?.let { r ->
                    item {
                        KbCard(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (r.correct) C_OK.copy(alpha = 0.10f) else C_BAD.copy(alpha = 0.10f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    when {
                                        r.manual -> "⏳ 主观题，暂不自动判分"
                                        r.correct -> "✓ 回答正确"
                                        else -> "✗ 回答错误"
                                    },
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (r.correct) C_OK else C_BAD
                                )
                                if (r.correctAnswer.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text("正确答案：${r.correctAnswer.joinToString("　")}", fontSize = 13.sp)
                                }
                                if (r.analysis.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text("解析：${r.analysis}", fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
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
                    val st = results[questions[i].optLong("id")]
                    val bg = when {
                        st == null -> MaterialTheme.colorScheme.surfaceVariant
                        st.manual -> Color(0xFFF59E0B)
                        st.correct -> C_OK
                        else -> C_BAD
                    }
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(bg, CircleShape)
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
                if (result == null) {
                    Button(
                        onClick = { submit() },
                        enabled = !checking && (
                            if (isText) !(texts[qid] ?: "").trim().isNullOrEmpty()
                            else picks[qid]?.isNotEmpty() == true
                            ),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (checking) "判分中…" else "确 认") }
                } else if (idx + 1 < questions.size) {
                    Button(onClick = { idx += 1 }, modifier = Modifier.weight(1f)) { Text("下一题") }
                } else {
                    Button(onClick = { finished = true }, modifier = Modifier.weight(1f)) { Text("完成练习") }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    content: String,
    state: String,
    enabled: Boolean,
    multi: Boolean,
    onClick: () -> Unit
) {
    val borderColor = when (state) {
        "ok" -> C_OK
        "bad" -> C_BAD
        "pick" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    KbCard(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(borderColor, if (multi) RoundedCornerShape(6.dp) else CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (multi && state == "pick") "✓" else label,
                    fontSize = 12.sp,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(content, fontSize = 14.sp)
        }
    }
}

@Composable
private fun FinishSummary(
    total: Int,
    results: Map<Long, QResult>,
    wrongIds: List<JSONObject>,
    onRestart: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("练习完成", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        val judged = results.values.count { !it.manual }
        val correct = results.values.count { !it.manual && it.correct }
        Text(
            "共 $total 题 · 已作答 ${results.size} · 判分 $judged · 正确 $correct" +
                (if (judged > 0) " · 正确率 ${correct * 100 / judged}%" else ""),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("再来一轮") }

        if (wrongIds.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("错题回顾（${wrongIds.size}）", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            wrongIds.forEach { q ->
                val r = results[q.optLong("id")]
                KbCard(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(q.optString("content"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        r?.correctAnswer?.takeIf { it.isNotEmpty() }?.let {
                            Spacer(Modifier.height(4.dp))
                            Text("正确：${it.joinToString("　")}", fontSize = 12.sp, color = C_OK)
                        }
                        r?.analysis?.takeIf { it.isNotEmpty() }?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
