/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.KbApi
import com.kb.kbplatform.shell.data.objects
import com.kb.kbplatform.shell.ui.common.CenterHint
import com.kb.kbplatform.shell.ui.common.KbCard
import com.kb.kbplatform.shell.ui.common.KbSkeleton
import com.kb.kbplatform.shell.ui.common.TopBar
import com.kb.kbplatform.shell.ui.common.kbItemEnter
import com.kb.kbplatform.shell.ui.common.kbPress
import com.kb.kbplatform.shell.ui.theme.KbGreen
import com.kb.kbplatform.shell.ui.theme.KbRed
import com.kb.kbplatform.shell.ui.theme.KbWarn
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 复习卡片（原生）：SM-2 到期队列。
 * 正面 → 点卡片 3D 翻面 → 翻面后才解锁四档评分（again/hard/good/easy，与 Anki 一致：
 * 先回忆再打分，提前亮出按钮会诱导「看了答案才想起来选简单」）→ POST review → 下一张。
 *
 * 2026-11-03 修复与重排：
 * - 🚨 原 GradeButton 把 `enabled` 传成了 `grading`（只在提交中可点，平时全禁用）→
 *   表现为「只能翻面不能打分」。正确语义是 `!grading`，且翻面前不亮出评分。
 * - UI 重排：顶部进度条、卡片 3D 翻面动画、问题/答案标签、软底评分按钮（去掉
 *   outlinedButtonColors 套 Button 的错配透明底）、入场/按压动效复用全局 motion 令牌。
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
            loading -> KbSkeleton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(260.dp),
                shape = RoundedCornerShape(20.dp)
            )
            error.isNotEmpty() -> CenterHint(error)
            done -> CenterHint("本轮复习完成，共 $reviewed 张\n稍后再来看看新的到期卡片")
            queue.isEmpty() -> CenterHint("当前没有到期卡片 🎉")
            else -> {
                val card = queue[pos]

                // 顶部进度条 + 计数（轻量、不抢戏）
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .kbItemEnter(0)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "第 ${pos + 1} 张 / 共 ${queue.size} 张",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "已复习 $reviewed",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { pos.toFloat() / queue.size },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                // 卡片：3D 翻面（rotationY 0↔180，90° 处换面）
                val flip by animateFloatAsState(
                    targetValue = if (revealed) 180f else 0f,
                    animationSpec = tween(360),
                    label = "cardFlip"
                )
                KbCard(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .kbPress { revealed = !revealed }
                ) {
                    Box(Modifier.fillMaxSize().padding(20.dp)) {
                        // 正面
                        CardFace(
                            visible = flip <= 90f,
                            rotation = flip,
                            tag = "问题",
                            tagColor = MaterialTheme.colorScheme.primary
                        ) {
                            FaceContent(card.optString("front"), 20.sp, FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "点击卡片查看答案",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        // 背面（rotation + 180，视觉上与正面镜像衔接）
                        CardFace(
                            visible = flip > 90f,
                            rotation = flip + 180f,
                            tag = "答案",
                            tagColor = KbGreen
                        ) {
                            FaceContent(card.optString("back"), 17.sp, FontWeight.Normal)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "回忆质量如何？在下方评分",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 评分区：翻面后才出现（先回忆再打分），提交中整体禁用
                AnimatedVisibility(
                    visible = revealed,
                    enter = fadeIn(tween(200)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(180)),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GradeButton("忘了", KbRed, solid = false, enabled = !grading) { grade(card.optLong("id", 0), "again") }
                        GradeButton("困难", KbWarn, solid = false, enabled = !grading) { grade(card.optLong("id", 0), "hard") }
                        GradeButton("良好", MaterialTheme.colorScheme.primary, solid = true, enabled = !grading) {
                            grade(card.optLong("id", 0), "good")
                        }
                        GradeButton("简单", KbGreen, solid = false, enabled = !grading) { grade(card.optLong("id", 0), "easy") }
                    }
                }
                if (!revealed) {
                    Text(
                        "先自己回忆，再翻面评分",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

/** 单面：卡片整面 统一 的包装（标签 + 居中内容），超出屏幕的 3D 旋转由 graphicsLayer 完成 */
@Composable
private fun CardFace(
    visible: Boolean,
    rotation: Float,
    tag: String,
    tagColor: Color,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    // cameraDistance 拉大避免大角度透视畸变；90° 附近切 alpha 防止镜像帧闪现
    val face = Modifier
        .fillMaxSize()
        .graphicsLayer {
            rotationY = rotation
            cameraDistance = 16f * density
            alpha = if (rotation % 360f in 90f..270f) 0f else 1f
        }
    if (!visible) return
    Column(
        face
            .background(Color.Transparent)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(7.dp),
            color = tagColor.copy(alpha = 0.12f),
        ) {
            Text(
                tag,
                fontSize = 11.sp,
                color = tagColor,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        content()
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.FaceContent(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight
) {
    Text(
        text,
        fontSize = fontSize,
        fontWeight = weight,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
        lineHeight = if (fontSize.value > 18) 30.sp else 26.sp,
    )
}

@Composable
private fun RowScope.GradeButton(
    label: String,
    color: Color,
    solid: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = if (solid) {
            ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)
        } else {
            // 软底彩字：14% 同色底（比 outlinedButtonColors 透明底的「套壳感」干净）
            ButtonDefaults.buttonColors(
                containerColor = color.copy(alpha = 0.14f),
                contentColor = color
            )
        },
        modifier = Modifier.weight(1f).height(48.dp)
    ) {
        Text(label, fontSize = 14.sp, maxLines = 1, fontWeight = FontWeight.Medium)
    }
}
