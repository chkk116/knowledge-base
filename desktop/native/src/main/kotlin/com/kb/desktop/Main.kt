package com.kb.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

fun main() = application {
    // 冒烟模式：KB_SMOKE=1 时启动 5 秒后自动退出（供构建/运行验证，不弹窗等人点）
    val smoke = System.getenv("KB_SMOKE") == "1"

    var session by remember { mutableStateOf<Session?>(null) }
    val windowState = rememberWindowState(
        size = DpSize(400.dp, 520.dp),
        position = WindowPosition.Aligned(Alignment.Center),
    )

    LaunchedEffect(smoke) {
        if (smoke) {
            delay(5000)
            exitProcess(0)
        }
    }

    LaunchedEffect(session) {
        if (session != null) {
            windowState.size = DpSize(1280.dp, 800.dp)
        } else {
            windowState.size = DpSize(400.dp, 520.dp)
        }
        windowState.position = WindowPosition.Aligned(Alignment.Center)
    }

    val apiServer = remember { ConfigStore.read()["server"]?.takeIf { it.isNotBlank() } ?: ConfigStore.defaultServer }

    Window(
        onCloseRequest = ::exitApplication,
        title = "知识库",
        state = windowState,
    ) {
        KbTheme {
            val s = session
            if (s == null) {
                LoginScreen(
                    initialServer = apiServer,
                    onLogin = { session = it },
                )
            } else {
                MainScreen(
                    session = s,
                    onLogout = { session = null },
                )
            }
        }
    }
}
