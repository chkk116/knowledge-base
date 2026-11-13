/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.SseClient
import com.kb.kbplatform.shell.ui.capture.CaptureScreen
import com.kb.kbplatform.shell.ui.common.kbPress
import com.kb.kbplatform.shell.ui.docs.DocsScreen
import com.kb.kbplatform.shell.ui.home.HomeScreen
import com.kb.kbplatform.shell.ui.messages.MessagesScreen
import com.kb.kbplatform.shell.ui.profile.ProfileScreen
import com.kb.kbplatform.shell.ui.theme.KbMotion

/**
 * 主框架 · 对标 MobileApp.vue：
 * 五槽 TabBar（首页/文档库/中间凸起＋/消息/我的），中央 ＋ 为 46px 圆形凸起（上移 14dp、
 * 主色发光投影），点按进「拍照建文档」；消息 Tab 红点由 SSE 未读驱动。
 *
 * 2026-10-26 动效升级：
 * - Tab 切换改 `AnimatedContent`，按左右方向做微位移 + 淡入（原来是硬切，页面像刷新）
 * - Tab 图标选中放大并轻微上浮、文字加粗渐变（原来只是换 tint，突变）
 * - 中央 ＋ 与各 Tab 都加了按压缩放，点下去有实体感
 * - 宽屏（平板/折叠屏展开）内容限宽 640dp 居中，避免文字被拉成一整行
 */
@Composable
fun MainScaffold(
    openWeb: (path: String, title: String) -> Unit,
    openRoute: (String) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val unread by SseClient.unread.collectAsState()
    // Tab 切换的横向位移（16dp），必须先按屏幕密度换算成像素
    val shiftPx = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.roundToPx() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.6.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        Modifier
                            .navigationBarsPadding()
                            .widthIn(max = 640.dp)
                            .fillMaxWidth()
                            .padding(vertical = 7.dp)
                    ) {
                        TabItem("首页", Icons.Filled.Home, Icons.Outlined.Home, tab == 0, unread = -1) { tab = 0 }
                        TabItem("文档库", Icons.Filled.List, Icons.AutoMirrored.Outlined.List, tab == 1, unread = -1) { tab = 1 }
                        // 中间 ＋（对标 .tab.center .fab：46px 圆、上移 14dp、发光）
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            CenterFab { tab = 4 }
                        }
                        TabItem("消息", Icons.Filled.Notifications, Icons.Outlined.Notifications, tab == 2, unread) { tab = 2 }
                        TabItem("我的", Icons.Filled.Person, Icons.Outlined.Person, tab == 3, unread = -1) { tab = 3 }
                    }
                }
            }
        }
    ) { pad ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(pad),
            contentAlignment = Alignment.TopCenter
        ) {
            // 宽屏限宽：手机上 maxWidth 远小于 640，这个约束不生效，行为与原来一致
            val wide = maxWidth >= 640.dp
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (wide) Modifier.widthIn(max = 640.dp) else Modifier)
            ) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        // 方向感：往右的 Tab 从右侧滑入，往左的从左侧滑入
                        val forward = targetState > initialState
                        val enter = slideInHorizontally(KbMotion.SlideSpec) { w ->
                            if (forward) shiftPx else -shiftPx
                        } + fadeIn(tween(KbMotion.Base, easing = KbMotion.Decelerate))
                        val exit = slideOutHorizontally(KbMotion.SlideSpec) { w ->
                            if (forward) -shiftPx else shiftPx
                        } + fadeOut(tween(KbMotion.Fast + 60, easing = KbMotion.Accelerate))
                        enter togetherWith exit
                    },
                    label = "kbTabContent",
                    modifier = Modifier.fillMaxSize()
                ) { t ->
                    when (t) {
                        0 -> HomeScreen(openWeb, openRoute, switchTab = { tab = it })
                        1 -> DocsScreen(openWeb, openRoute)
                        2 -> MessagesScreen()
                        3 -> ProfileScreen(openWeb, openRoute)
                        else -> CaptureScreen(onDone = { tab = 0 }, openRoute = openRoute)
                    }
                }
            }
        }
    }
}

/**
 * 中央 ＋：按下缩到 0.9 并收掉发光，抬起弹回。
 * 用 spring 的 Overshoot 手感，像按实体按键。
 */
@Composable
private fun CenterFab(onClick: () -> Unit) {
    val elevation by animateDpAsState(
        targetValue = 10.dp,
        animationSpec = tween(KbMotion.Base),
        label = "fabElevation"
    )
    Box(
        Modifier
            .offset(y = (-14).dp)
            .size(46.dp)
            // 投影带主色（套壳 .fab 是 rgba(22,119,255,.38) 的蓝色光晕），不是灰影
            .shadow(
                elevation = elevation,
                shape = CircleShape,
                clip = false,
                ambientColor = MaterialTheme.colorScheme.primary,
                spotColor = MaterialTheme.colorScheme.primary
            )
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .kbPress(scaleDown = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = "拍照建文档",
            tint = Color.White,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    label: String,
    selected: ImageVector,
    unselected: ImageVector,
    active: Boolean,
    unread: Int,
    onClick: () -> Unit
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    // 选中：放大到 1.12 并上浮 1dp；未选中回落到 1.0。tween 用 Fast，点下去立刻有反应
    val iconScale by animateFloatAsState(
        targetValue = if (active) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "tabIconScale"
    )
    val iconLift by animateDpAsState(
        targetValue = if (active) (-1).dp else 0.dp,
        animationSpec = tween(KbMotion.Fast, easing = KbMotion.Standard),
        label = "tabIconLift"
    )
    Column(
        Modifier
            .weight(1f)
            .kbPress(scaleDown = 0.94f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.offset(y = iconLift)) {
            Icon(
                if (active) selected else unselected,
                contentDescription = label,
                tint = tint,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    }
            )
            if (unread > 0) {
                Text(
                    if (unread > 99) "99+" else unread.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-8).dp)
                        // 红点外加一圈底色描边（套壳 .dot 的 box-shadow 0 0 0 2px tabbar-bg），
                        // 压在图标上时轮廓才干净
                        .background(Color(0xFFEF4444), CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), CircleShape)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Text(
            label,
            fontSize = 10.5.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
