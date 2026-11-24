package com.kb.desktop

import java.awt.Frame
import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 桌面系统通知（Windows 托盘气泡 → 通知中心 Toast）。
 *
 * 为什么不用 Compose 的 Tray：这里只需要「发一条通知」，不需要托盘菜单或常驻图标交互；
 * AWT `SystemTray + TrayIcon.displayMessage` 在 Windows 10/11 上会进通知中心，
 * 且零额外依赖。系统禁用托盘 / 无桌面会话时静默降级，不影响主流程。
 */
object KbNotify {
    private var tray: TrayIcon? = null
    private var lastTitle = ""
    private var lastAtMs = 0L

    /** 主窗口是否在前台：前台时页面角标已经够显眼，再弹系统通知就是打扰 */
    fun windowActive(): Boolean = runCatching {
        Window.getWindows().filterIsInstance<Frame>().firstOrNull { it.isVisible }?.isActive ?: true
    }.getOrDefault(true)

    fun notify(title: String, body: String) {
        val now = System.currentTimeMillis()
        // 未读轮询 60s 一次，同一个数字会连续出现；同标题 20s 内只弹一次，避免刷屏
        if (title == lastTitle && now - lastAtMs < 20_000) return
        lastTitle = title
        lastAtMs = now
        val t = ensure() ?: return
        runCatching { t.displayMessage(title, body, TrayIcon.MessageType.NONE) }
    }

    private fun ensure(): TrayIcon? {
        tray?.let { return it }
        return runCatching {
            if (!SystemTray.isSupported()) return null
            val icon = TrayIcon(loadImage(), "知识库")
            icon.isImageAutoSize = true
            SystemTray.getSystemTray().add(icon)
            tray = icon
            icon
        }.getOrNull()
    }

    private fun loadImage(): Image {
        val fallback = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val stream = runCatching {
            object {}.javaClass.classLoader.getResourceAsStream("kb-icon.png")
        }.getOrNull() ?: return fallback
        return stream.use { runCatching { ImageIO.read(it) }.getOrNull() } ?: fallback
    }
}