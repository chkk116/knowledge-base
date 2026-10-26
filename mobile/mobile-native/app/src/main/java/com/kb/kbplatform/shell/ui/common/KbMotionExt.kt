/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.kb.kbplatform.shell.ui.theme.KbMotion
import kotlinx.coroutines.delay

/**
 * 交互动效扩展（2026-10-26）。
 *
 * 两件事：**按下去要有反应**、**内容出现要有层次**。
 * 之前全 App 只有 ripple（有些地方连 ripple 都关了 `indication = null`），
 * 点一下几乎没反馈，列表也是一瞬间「啪」地出现。
 */

/**
 * 可按压：按下缩到 [scaleDown]，抬起弹回，同时保留 ripple。
 *
 * 用 spring 而不是 tween：手指按下的时间是随机的，弹簧能自动适配，
 * 无论按多久，抬起时的手感都一致。
 */
@Composable
fun Modifier.kbPress(
    enabled: Boolean = true,
    scaleDown: Float = 0.965f,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleDown else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 900f),
        label = "kbPressScale"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * 列表项入场：淡入 + 从下方轻推上来，按 [index] 错开。
 *
 * 只动 `graphicsLayer`（合成层），不触发重排，长列表也不会掉帧；
 * 延迟封顶 [KbMotion.StaggerMax] 项，末尾不会等太久。
 */
@Composable
fun Modifier.kbItemEnter(index: Int = 0, fromBottom: Int = 16): Modifier {
    val delayMs = index.coerceAtMost(KbMotion.StaggerMax) * KbMotion.Stagger
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (delayMs > 0) delay(delayMs.toLong())
        shown = true
    }
    val t by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(KbMotion.Base + 80, easing = KbMotion.Decelerate),
        label = "kbItemEnter"
    )
    return this.graphicsLayer {
        alpha = t
        translationY = (1f - t) * fromBottom
    }
}
