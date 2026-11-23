/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 骨架屏（2026-10-26）。
 *
 * 原来所有页面加载态都是「转圈 + 加载中…」，首屏看起来像卡住了。
 * 骨架屏先把**版式画出来**，数据到了再填内容，感知等待时间明显更短，
 * 也避免「转圈 → 内容跳一下」的位移。
 *
 * shimmer 用 `drawWithContent` 画一条移动的高光带，**不引第三方库**
 * （CoolMallKotlin 用的是 valentinilk/shimmer，功能一样，多一个依赖不划算）。
 */

/** 骨架底色：浅色用黑 6%，深色用白 8%（深色下黑底会看不见） */
@Composable
fun kbSkeletonColor(): Color =
    // 套壳 .m-skel 的底色是 var(--m-border)（#e5e6eb / 深色 #2c2d33），不是半透明白黑
    MaterialTheme.colorScheme.outline

/** 高光色：套壳用 var(--m-card-2)，即 surfaceVariant */
@Composable
private fun kbSkeletonHighlight(): Color = MaterialTheme.colorScheme.surfaceVariant

/**
 * 骨架块：圆角矩形 + 流动高光。
 *
 * @param shimmer 关闭后只显示静态底色（`prefers-reduced-motion` 类场景或低端机可关）
 */
@Composable
fun KbSkeleton(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    shimmer: Boolean = true
) {
    val base = kbSkeletonColor()
    val highlight = kbSkeletonHighlight()
    if (!shimmer) {
        Box(modifier.clip(shape).background(base))
        return
    }
    val transition = rememberInfiniteTransition(label = "kbShimmer")
    val p by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1150, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "kbShimmerProgress"
    )
    Box(
        modifier
            .clip(shape)
            .background(base)
            .drawWithContent {
                drawContent()
                // 高光带宽度取 60% 屏宽，斜 45° 扫过，比竖直扫更接近真实反光
                val band = size.width * 0.6f
                val x = -band + (size.width + band * 2f) * p
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, highlight, Color.Transparent),
                        start = Offset(x, 0f),
                        end = Offset(x + band, size.height)
                    )
                )
            }
    )
}

/** 一行文字骨架 */
@Composable
fun KbSkeletonLine(
    modifier: Modifier = Modifier,
    widthFraction: Float = 1f,
    height: Dp = 13.dp,
    shimmer: Boolean = true
) {
    KbSkeleton(
        modifier = modifier
            .fillMaxWidth(widthFraction.coerceIn(0.05f, 1f))
            .height(height),
        shape = RoundedCornerShape(6.dp),
        shimmer = shimmer
    )
}

/** 圆形骨架（头像 / 图标瓦片） */
@Composable
fun KbSkeletonCircle(size: Dp = 40.dp, shimmer: Boolean = true) {
    KbSkeleton(Modifier.size(size), shape = CircleShape, shimmer = shimmer)
}

/**
 * 列表骨架：卡片 + 头像 + 三行文字。
 * 与 `KbCard` 的实际高度接近，数据到达时几乎不跳动。
 */
@Composable
fun KbSkeletonList(
    modifier: Modifier = Modifier,
    count: Int = 4,
    avatar: Boolean = true,
    shimmer: Boolean = true
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(count) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(14.dp)
            ) {
                if (avatar) {
                    KbSkeletonCircle(38.dp, shimmer)
                    Spacer(Modifier.size(12.dp))
                }
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KbSkeletonLine(widthFraction = 0.62f, height = 14.dp, shimmer = shimmer)
                    KbSkeletonLine(widthFraction = 1f, height = 11.dp, shimmer = shimmer)
                    KbSkeletonLine(widthFraction = 0.44f, height = 11.dp, shimmer = shimmer)
                }
            }
        }
    }
}

/**
 * 首页骨架：大标题 + 九宫格 + 统计条 + 最近文档。
 * 与 `HomeScreen` 的版式对齐，首屏不会「先白一下再跳出来」。
 */
@Composable
fun KbSkeletonHome(modifier: Modifier = Modifier, shimmer: Boolean = true) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 问候语 + 头像
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KbSkeletonLine(widthFraction = 0.42f, height = 18.dp, shimmer = shimmer)
                KbSkeletonLine(widthFraction = 0.62f, height = 12.dp, shimmer = shimmer)
            }
            KbSkeletonCircle(40.dp, shimmer)
        }
        Spacer(Modifier.height(2.dp))
        // 金刚区（与 HomeScreen 一致：不套卡片、52dp 瓦片、间距 10）
        Column(
            Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            repeat(3) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(4) {
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            KbSkeleton(Modifier.size(52.dp), RoundedCornerShape(16.dp), shimmer)
                            KbSkeleton(Modifier.size(width = 44.dp, height = 10.dp), RoundedCornerShape(5.dp), shimmer)
                        }
                    }
                }
            }
        }
        // 统计条
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                repeat(4) {
                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        KbSkeleton(Modifier.size(width = 34.dp, height = 16.dp), RoundedCornerShape(5.dp), shimmer)
                        KbSkeleton(Modifier.size(width = 44.dp, height = 9.dp), RoundedCornerShape(5.dp), shimmer)
                    }
                }
            }
        }
    }
}

/**
 * 页面级骨架屏：放进不可滚动的页面 Column 里用（管理台 / 考试中心 / 邮箱列表）。
 *
 * 直接写 KbSkeletonList 有两个问题：页面 Column 本身不滚，6 张卡在 640dp 的机器上会被屏幕
 * 裁掉最后两张；平板宽屏上列表按 widthIn(720) 居中，骨架屏却铺满全宽，替换瞬间会跳一下。
 * 这里统一 weight + 滚动 + 限宽。
 */
@Composable
fun ColumnScope.KbSkeletonPage(count: Int = 6, maxWidth: androidx.compose.ui.unit.Dp = 720.dp) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Box(Modifier.fillMaxWidth().widthIn(max = maxWidth)) {
            KbSkeletonList(count = count)
        }
    }
}
