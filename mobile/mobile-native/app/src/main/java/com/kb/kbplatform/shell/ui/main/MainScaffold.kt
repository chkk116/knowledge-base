/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.kb.kbplatform.shell.ui.docs.DocsScreen
import com.kb.kbplatform.shell.ui.home.HomeScreen
import com.kb.kbplatform.shell.ui.messages.MessagesScreen
import com.kb.kbplatform.shell.ui.profile.ProfileScreen

/**
 * 主框架：底部四 Tab，信息架构与 Capacitor 版一致（首页 / 文档库 / 消息 / 我的），
 * 用户在两版之间切换零学习成本。
 */
@Composable
fun MainScaffold(openWeb: (path: String, title: String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val tabs = listOf(
        TabSpec("首页", Icons.Filled.Home, Icons.Outlined.Home),
        TabSpec("文档库", Icons.Filled.List, Icons.Filled.List),
        TabSpec("消息", Icons.Filled.Notifications, Icons.Outlined.Notifications),
        TabSpec("我的", Icons.Filled.Person, Icons.Outlined.Person)
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(if (tab == i) t.selected else t.unselected, contentDescription = t.label) },
                        label = { Text(t.label) }
                    )
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
                0 -> HomeScreen(openWeb)
                1 -> DocsScreen(openWeb)
                2 -> MessagesScreen()
                else -> ProfileScreen(openWeb)
            }
        }
    }
}

private data class TabSpec(val label: String, val selected: ImageVector, val unselected: ImageVector)
