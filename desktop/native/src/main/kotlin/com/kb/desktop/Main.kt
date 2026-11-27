package com.kb.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.LocalAwtWindow
import kotlinx.coroutines.delay
import java.awt.Dimension
import kotlin.system.exitProcess

fun main() {
    val smoke = System.getenv("KB_SMOKE") == "1"
    // 单实例：第二个进程会和第一个抢 WebView2 数据目录与 config.json，直接退出。
    // 静默退出会让用户以为"双击没反应"，所以给一个原生提示框（无桌面会话时忽略失败）。
    // 冒烟 / CI 模式跳过：上一次被强杀的进程可能仍持有锁，这里若弹模态框，进程永远不退出，构建会卡死。
    if (!smoke && !SingleInstance.acquire()) {
        System.err.println("[kb] 已有实例在运行，本次启动退出")
        runCatching {
            javax.swing.JOptionPane.showMessageDialog(
                null,
                "知识库已经在运行，请切换到已打开的窗口。",
                "知识库",
                javax.swing.JOptionPane.INFORMATION_MESSAGE,
            )
        }
        exitProcess(0)
    }
    runApp()
}

@OptIn(ExperimentalComposeUiApi::class)
private fun runApp() = application {
    // 冒烟模式：KB_SMOKE=1 时启动 5 秒后自动退出（供构建/运行验证，不弹窗等人点）
    val smoke = System.getenv("KB_SMOKE") == "1"

    var session by remember { mutableStateOf<Session?>(null) }
    val windowState = rememberWindowState(
        size = DpSize(960.dp, 640.dp),
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
            windowState.size = DpSize(960.dp, 640.dp)
        }
        windowState.position = WindowPosition.Aligned(Alignment.Center)
    }

    val apiServer = remember { ConfigStore.read()["server"]?.takeIf { it.isNotBlank() } ?: ConfigStore.defaultServer }
    // 窗口/任务栏图标（品牌 icon-192；加载失败保持插件默认）
    val appIcon = remember { loadWindowIcon() }

    Window(
        onCloseRequest = ::exitApplication,
        title = "知识库",
        state = windowState,
        icon = appIcon,
    ) {
        // 窗口最小尺寸随状态切换：登录 640×540（保住居中卡构图），主界面 960×620，防止用户缩太小挤破布局
        val awtWindow = LocalAwtWindow.current
        LaunchedEffect(session) {
            awtWindow?.minimumSize = if (session == null) Dimension(640, 540) else Dimension(960, 620)
        }

        // 调试截图（KB_SHOT=路径）：UI 验收用，抓窗口区域存 PNG 后退出；不设该变量时完全不影响正常使用
        val shotPath = System.getenv("KB_SHOT")
        LaunchedEffect(shotPath) {
            if (!shotPath.isNullOrBlank()) {
                delay(1600)
                runCatching {
                    awtWindow?.isAlwaysOnTop = true
                    awtWindow?.setLocation(0, 0)
                    awtWindow?.toFront()
                    awtWindow?.requestFocus()
                    delay(900)
                    val b = awtWindow!!.bounds
                    val img = java.awt.Robot().createScreenCapture(b)
                    javax.imageio.ImageIO.write(img, "png", java.io.File(shotPath))
                }
                exitProcess(0)
            }
        }

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
