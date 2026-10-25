/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kb.kbplatform.shell.TokenStore
import com.kb.kbplatform.shell.ui.login.LoginScreen
import com.kb.kbplatform.shell.ui.main.MainScaffold
import com.kb.kbplatform.shell.ui.web.WebScreen

/**
 * 根导航：未登录 → 登录页；已登录 → 主框架（四 Tab）+ WebView 承载页。
 * 令牌被 401 清空时自动退回登录页（TokenStore 驱动，无需手动跳转）。
 */
@Composable
fun AppRoot() {
    val token by TokenStore.token.collectAsState()
    val nav = rememberNavController()

    if (token == null) {
        LoginScreen()
    } else {
        NavHost(navController = nav, startDestination = "main") {
            composable("main") {
                MainScaffold(openWeb = { path, title ->
                    nav.navigate("web?path=${Uri.encode(path)}&title=${Uri.encode(title)}")
                })
            }
            composable(
                route = "web?path={path}&title={title}",
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType; defaultValue = "/m/" },
                    navArgument("title") { type = NavType.StringType; defaultValue = "页面" }
                )
            ) { entry ->
                WebScreen(
                    path = entry.arguments?.getString("path") ?: "/m/",
                    title = entry.arguments?.getString("title").orEmpty().ifBlank { "页面" },
                    onBack = { nav.popBackStack() }
                )
            }
        }
    }
}
