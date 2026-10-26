/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset

/**
 * 动效令牌（2026-10-26 全面动效升级，对标 CoolMallKotlin 的 NAV_ANIMATION_DURATION=300
 * + Material3 emphasized 曲线）。
 *
 * 约定：**所有动画时长与曲线只从这里取**，页面里不要再写裸 `tween(250)`，
 * 否则各页面节奏不一致，观感会「有的快有的慢」。
 */
object KbMotion {
    /** 120ms：按下/抬起、开关、图标着色这类即时反馈 */
    const val Fast = 120

    /** 200ms：淡入淡出、尺寸变化、状态切换 */
    const val Base = 200

    /** 300ms：页面进出、Tab 切换、抽屉 */
    const val Page = 300

    /** 420ms：首屏/内容块入场（略慢，显得从容） */
    const val Slow = 420

    /** 列表逐项入场的间隔（毫秒） */
    const val Stagger = 26

    /** 入场延迟最多累加到第 N 项，超过后不再递增（否则长列表末尾等太久） */
    const val StaggerMax = 9

    /** M3 emphasized：进出场默认曲线，起步快、收尾稳 */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 只减速：元素「飞入就位」，用于入场 */
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)

    /** 只加速：元素「离开视线」，用于出场 */
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /** 轻微回弹：用于「＋」按钮、选中指示器等需要一点弹性的场景 */
    val Overshoot: Easing = CubicBezierEasing(0.34f, 1.25f, 0.64f, 1f)

    /** 滑动偏移用的 spec（IntOffset 不能复用 Float 的 tween） */
    val SlideSpec: FiniteAnimationSpec<IntOffset> = tween(Page, easing = Standard)
}

/**
 * 前进（进入下一级）：新页从右侧约 1/5 屏滑入并淡入，旧页轻微左移淡出。
 * 位移只给 1/5 而不是整屏，避免长列表页面「横扫」带来的眩晕。
 */
fun kbEnterForward(): EnterTransition =
    slideInHorizontally(KbMotion.SlideSpec) { it / 5 } +
        fadeIn(tween(KbMotion.Base, easing = KbMotion.Decelerate))

fun kbExitForward(): ExitTransition =
    slideOutHorizontally(KbMotion.SlideSpec) { -it / 10 } +
        fadeOut(tween(KbMotion.Base, easing = KbMotion.Accelerate))

/** 返回：方向镜像，旧页右滑离开，上一级从左微移归位 */
fun kbEnterBackward(): EnterTransition =
    slideInHorizontally(KbMotion.SlideSpec) { -it / 10 } +
        fadeIn(tween(KbMotion.Base, easing = KbMotion.Decelerate))

fun kbExitBackward(): ExitTransition =
    slideOutHorizontally(KbMotion.SlideSpec) { it / 5 } +
        fadeOut(tween(KbMotion.Base, easing = KbMotion.Accelerate))
