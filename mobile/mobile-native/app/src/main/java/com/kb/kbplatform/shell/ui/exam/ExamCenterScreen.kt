/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.exam

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.CenterHint
import com.kb.kbplatform.shell.ui.common.KbSkeletonPage
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.formatDateTime
import org.json.JSONObject

/**
 * 考试中心（原生）：已发布试卷列表 + 我的考试成绩。
 * 点试卷进入原生作答页（倒计时/交卷/成绩）。
 */
@Composable
fun ExamCenterScreen(openExam: (Long, String) -> Unit, openWeb: (String, String) -> Unit, openRoute: (String) -> Unit = {}) {
    var papers by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var records by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        error = ""
        try {
            papers = KbApi.exams().objects()
            records = runCatching { KbApi.myExams().objects() }.getOrDefault(emptyList())
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "考试中心")
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.OutlinedButton(onClick = { openRoute("wrong-book") }, modifier = Modifier.weight(1f)) {
                Text("错题本", fontSize = 13.sp)
            }
            androidx.compose.material3.OutlinedButton(onClick = { openRoute("exam-mine") }, modifier = Modifier.weight(1f)) {
                Text("我的考试", fontSize = 13.sp)
            }
        }

        if (loading) {
            KbSkeletonPage(count = 6)
        } else if (error.isNotEmpty()) {
            CenterHint(error)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (papers.isEmpty() && records.isEmpty()) {
                    item { CenterHint("暂无考试") }
                }
                if (papers.isNotEmpty()) {
                    item {
                        Text("可用试卷", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                    items(papers, key = { "p" + it.optLong("id", 0) }) { p ->
                        KbCard(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openExam(p.optLong("id", 0), p.optString("title")) }
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(p.optString("title").ifBlank { "未命名试卷" },
                                    fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    listOf(
                                        "${p.optInt("questionCount", 0)} 题",
                                        "总分 ${p.optInt("totalScore", 100)}",
                                        "及格 ${p.optInt("passScore", 60)}",
                                        if (p.optInt("durationMin", 0) > 0) "限时 ${p.optInt("durationMin")} 分钟" else "不限时"
                                    ).joinToString(" · "),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (records.isNotEmpty()) {
                    item {
                        Text("我的成绩", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                    items(records, key = { "r" + it.optLong("id", 0) }) { r ->
                        val passed = r.optBoolean("passed", false)
                        KbCard(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.optString("paperTitle").ifBlank { "考试" },
                                        fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        formatDateTime(r.optString("submittedAt").ifBlank { r.optString("startedAt") }),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "${r.optInt("score", 0)} 分",
                                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                    color = if (passed) androidx.compose.ui.graphics.Color(0xFF22C55E)
                                    else MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.padding(start = 6.dp))
                                Text(
                                    if (passed) "通过" else "未过",
                                    fontSize = 11.sp,
                                    color = if (passed) androidx.compose.ui.graphics.Color(0xFF22C55E)
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
