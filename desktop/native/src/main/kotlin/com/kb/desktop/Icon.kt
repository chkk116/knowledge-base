package com.kb.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.IntSize

/**
 * 运行时窗口/任务栏图标：源为 classpath 内 kb-icon.png
 * （frontend/public/icons/icon-192.png 的副本，与安装器 kb-desktop.ico 同一品牌图）。
 * 加载失败返回 null，调用方不传 icon 参数保持默认——图标缺失不阻塞启动。
 */
fun loadWindowIcon(): Painter? {
    val bmp: ImageBitmap = runCatching {
        object {}.javaClass.classLoader
            .getResourceAsStream("kb-icon.png")!!.use { loadImageBitmap(it) }
    }.getOrNull() ?: return null
    return object : Painter() {
        override val intrinsicSize = Size(bmp.width.toFloat(), bmp.height.toFloat())

        override fun DrawScope.onDraw() {
            // 目标尺寸随调用方（任务栏/标题栏 16~32px）缩放，最小 1px 防御空尺寸
            drawImage(
                bmp,
                dstSize = IntSize(
                    size.width.toInt().coerceAtLeast(1),
                    size.height.toInt().coerceAtLeast(1),
                ),
            )
        }
    }
}
