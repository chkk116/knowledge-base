/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 与 Web 端 theme.css 对齐：保持 Element Plus 天空蓝主色，不强行换品牌色
val KbBlue = Color(0xFF409EFF)
val KbBlueDark = Color(0xFF66B1FF)
val KbGreen = Color(0xFF22C55E)
val KbRed = Color(0xFFEF4444)

private val LightColors = lightColorScheme(
    primary = KbBlue,
    secondary = KbBlue,
    background = Color(0xFFF5F6F7),
    surface = Color.White,
    surfaceVariant = Color(0xFFEFF3F6),
    error = KbRed
)

private val DarkColors = darkColorScheme(
    primary = KbBlueDark,
    secondary = KbBlueDark,
    background = Color(0xFF101418),
    surface = Color(0xFF191D22),
    surfaceVariant = Color(0xFF23282F),
    error = KbRed
)

@Composable
fun KbTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
