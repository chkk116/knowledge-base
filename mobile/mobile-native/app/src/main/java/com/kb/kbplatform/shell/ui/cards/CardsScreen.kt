/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.cards

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

/**
 * 复习卡片（原生）：SM-2 到期队列。
 * 正面 → 点卡片翻面 → 四档评分（again/hard/good/easy）→ POST review → 下一张；
 * 完成后给本轮小结，与 Web 版 MobileFlashcards 的流程一致。
 */
@Composable
fun CardsScreen() {
    val scope = rememberCoroutineScope()

    var queue by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var pos by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var grading by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var reviewed by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        try {
            queue = KbApi.dueCards().objects()
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    fun grade(id: Long, g: String) {
        if (grading) return
        grading = true
        scope.launch {
            try {
                KbApi.reviewCard(id, g)
                reviewed += 1
                revealed = false
                if (pos + 1 >= queue.size) done = true else pos += 1
            } catch (e: Exception) {
                error = e.message ?: "提交失败"
            } finally {
                grading = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "复习卡片")

        when {
            loading -> com.kb.kbplatform.shell.ui.common.KbSkeleton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(220.dp),
                shape = RoundedCornerShape(16.dp)
            )
            error.isNotEmpty() -> CenterHint(error)
            done -> CenterHint("本轮复习完成，共 $reviewed 张\n稍后再来看看新的到期卡片")
            queue.isEmpty() -> CenterHint("当前没有到期卡片 🎉")
            else -> {
                val card = queue[pos]
                KbCard(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .weight(1f)
                        .animateContentSize()
                        .clickable { revealed = !revealed }
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "${pos + 1} / ${queue.size}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Start)
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            card.optString("front"),
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (revealed) {
                            Spacer(Modifier.height(20.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            Spacer(Modifier.height(20.dp))
                            Text(
                                card.optString("back"),
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (!revealed) {
                            Text(
                                "点击卡片查看答案",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }

                Text(
                    if (revealed) "还记得吗？选择回忆质量" else "先回忆，再翻面评分",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GradeButton("忘了", Color(0xFFEF4444), grading) { grade(card.optLong("id", 0), "again") }
                    GradeButton("困难", Color(0xFFF59E0B), grading) { grade(card.optLong("id", 0), "hard") }
                    GradeButton("良好", MaterialTheme.colorScheme.primary, grading, primary = true) {
                        grade(card.optLong("id", 0), "good")
                    }
                    GradeButton("简单", Color(0xFF22C55E), grading) { grade(card.optLong("id", 0), "easy") }
                }
            }
        }
    }
}

@Composable
private fun RowScope.GradeButton(
    label: String,
    color: Color,
    enabled: Boolean,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = if (primary) ButtonDefaults.buttonColors(containerColor = color)
        else ButtonDefaults.outlinedButtonColors(contentColor = color),
        modifier = Modifier.weight(1f)
    ) {
        Text(label, fontSize = 14.sp, maxLines = 1)
    }
}
