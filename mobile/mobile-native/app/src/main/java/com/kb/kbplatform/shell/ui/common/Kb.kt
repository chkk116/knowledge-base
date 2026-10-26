/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.ui.theme.KbBlueSoft
import com.kb.kbplatform.shell.ui.theme.KbTextDim

/**
 * 与套壳 mobile.css 对齐的共享组件库（2026-10-25 UI 统一）：
 * 卡片 r14 双层软影、按钮 48h/r14、chip r7、大标题页头、空态、底部操作条。
 * 各页面只允许用这套组件搭 UI，保证与 Web 壳观感一致。
 */

/** 卡片：--m-card r14 + 软阴影（兼容传入 shape/colors/border 的调用点） */
@Composable
fun KbCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    colors: androidx.compose.material3.CardColors = androidx.compose.material3.CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface
    ),
    border: androidx.compose.foundation.BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    // 可点的卡片给按压缩放：整块卡片只靠 ripple 反馈太弱，尤其宽卡片点击点常常落在文字外
    val m = if (onClick != null) modifier.kbPress(scaleDown = 0.985f, onClick = onClick) else modifier
    if (border != null) {
        Surface(
            shape = shape,
            color = colors.containerColor,
            border = border,
            shadowElevation = 1.dp,
            tonalElevation = 0.dp,
            modifier = m
        ) {
            Box { content() }
        }
    } else {
        Surface(
            shape = shape,
            color = colors.containerColor,
            shadowElevation = 1.dp,
            tonalElevation = 0.dp,
            modifier = m
        ) {
            Box { content() }
        }
    }
}

/** 主按钮：--m-btn 48h / r14 / 15.5sp 600 */
@Composable
fun KbButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    ghost: Boolean = false,
    danger: Boolean = false,
    small: Boolean = false
) {
    val shape = RoundedCornerShape(if (small) 11.dp else 14.dp)
    val bgColor = when {
        danger -> MaterialTheme.colorScheme.error
        ghost -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.primary
    }
    val fgColor = when {
        ghost -> MaterialTheme.colorScheme.onSurface
        else -> Color.White
    }
    // 按钮按压缩放（Material3 Button 自带 elevation/ripple，但按下时形状不变，加一点缩放更「有实体」）
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 900f),
        label = "kbBtnScale"
    )
    val scaled = modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
    if (ghost) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            interactionSource = interaction,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = scaled.height(if (small) 38.dp else 48.dp)
        ) {
            Text(text, fontSize = if (small) 14.sp else 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            interactionSource = interaction,
            colors = ButtonDefaults.buttonColors(containerColor = bgColor, contentColor = fgColor),
            modifier = scaled.height(if (small) 38.dp else 48.dp)
        ) {
            Text(text, fontSize = if (small) 14.sp else 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** 小圆签：--m-chip（选中=主色软底，灰=中性） */
@Composable
fun KbChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    gray: Boolean = false,
    tint: Color? = null,
    onClick: (() -> Unit)? = null
) {
    val bg = when {
        tint != null -> tint.copy(alpha = 0.12f)
        selected -> KbBlueSoft
        gray -> MaterialTheme.colorScheme.surfaceVariant
        else -> KbBlueSoft
    }
    val fg = when {
        tint != null -> tint
        selected || !gray -> MaterialTheme.colorScheme.primary
        else -> Color(0xFF667085)
    }
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = fg,
        modifier = modifier
            .background(bg, RoundedCornerShape(7.dp))
            .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m }
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** Tab 页大标题页头（透明底 + 20px 粗标题，对标 .m-header 非 solid 形态） */
@Composable
fun KbPageHeader(
    title: String,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = KbTextDim)
            }
        }
        actions()
    }
}

/** 空态：64 圆角图标瓦片 + 灰字（对标 .m-empty） */
@Composable
fun KbEmpty(emoji: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 60.dp, bottom = 24.dp)
            .kbItemEnter(fromBottom = 10),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(emoji, fontSize = 26.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text,
            fontSize = 13.sp,
            color = KbTextDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

/** 加载态（保持与空态同构） */
@Composable
fun KbLoading(text: String = "加载中…") {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            Modifier.size(26.dp),
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(10.dp))
        Text(text, fontSize = 13.sp, color = KbTextDim)
    }
}

/** 底部操作条：--m-actionbar（毛玻璃白底 + 上分割线） */
@Composable
fun KbActionBar(content: @Composable RowScope.() -> Unit) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.6.dp)
                .background(MaterialTheme.colorScheme.outline)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}
