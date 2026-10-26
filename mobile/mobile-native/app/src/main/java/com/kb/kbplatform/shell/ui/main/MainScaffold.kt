/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kb.kbplatform.shell.data.SseClient
import com.kb.kbplatform.shell.ui.capture.CaptureScreen
import com.kb.kbplatform.shell.ui.docs.DocsScreen
import com.kb.kbplatform.shell.ui.home.HomeScreen
import com.kb.kbplatform.shell.ui.messages.MessagesScreen
import com.kb.kbplatform.shell.ui.profile.ProfileScreen

/**
 * 主框架 · 对标 MobileApp.vue：
 * 五槽 TabBar（首页/文档库/中间凸起＋/消息/我的），中央 ＋ 为 46px 圆形凸起（上移 14dp、
 * 主色发光投影），点按进「拍照建文档」；消息 Tab 红点由 SSE 未读驱动。
 */
@Composable
fun MainScaffold(
    openWeb: (path: String, title: String) -> Unit,
    openRoute: (String) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val unread by SseClient.unread.collectAsState()

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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                        .padding(vertical = 7.dp)
                ) {
                    TabItem("首页", Icons.Filled.Home, Icons.Outlined.Home, tab == 0, 0, unread = -1) { tab = 0 }
                    TabItem("文档库", Icons.Filled.List, Icons.Filled.List, tab == 1, 1, unread = -1) { tab = 1 }
                    // 中间 ＋（对标 .tab.center .fab：46px 圆、上移 14dp、发光）
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .offset(y = (-14).dp)
                                .size(46.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    CircleShape
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { tab = 4 },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Add, contentDescription = "拍照建文档",
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    TabItem("消息", Icons.Filled.Notifications, Icons.Outlined.Notifications, tab == 2, 2, unread) { tab = 2 }
                    TabItem("我的", Icons.Filled.Person, Icons.Outlined.Person, tab == 3, 3, unread = -1) { tab = 3 }
                }
            }
        }
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            when (tab) {
                0 -> HomeScreen(openWeb, openRoute)
                1 -> DocsScreen(openWeb, openRoute)
                2 -> MessagesScreen()
                3 -> ProfileScreen(openWeb, openRoute)
                4 -> CaptureScreen(onDone = { tab = 0 }, openRoute = openRoute)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    label: String,
    selected: ImageVector,
    unselected: ImageVector,
    active: Boolean,
    index: Int,
    unread: Int,
    onClick: () -> Unit
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .weight(1f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Icon(
                if (active) selected else unselected,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
            if (unread > 0) {
                Text(
                    if (unread > 99) "99+" else unread.toString(),
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-6).dp)
                        .background(androidx.compose.ui.graphics.Color(0xFFEF4444), CircleShape)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
