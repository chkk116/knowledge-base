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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

/**
 * 设计令牌与 Web 套壳 mobile.css 的 --m-* 家族逐一对齐（2026-10-25 UI 对标）：
 * 浅色 --m-primary #1677FF / --m-bg #F5F6F7 / --m-card #FFFFFF / --m-card-2 #F7F8FA
 *      --m-border #E5E6EB / --m-text #1D2129 / --m-text-sub #4E5969 / --m-text-dim #86909C
 * 深色 --m-primary #4C8DFF / --m-bg #17181C / --m-card #1F2024 / --m-card-2 #26272C
 */
val KbBlue = Color(0xFF1677FF)
val KbBlueSoft = Color(0xFFE8F3FF)
val KbBlueDeep = Color(0xFF0958D9)
val KbGreen = Color(0xFF00B42A)
val KbRed = Color(0xFFF53F3F)
val KbWarn = Color(0xFFFF7D00)
val KbTextDim = Color(0xFF86909C)

private val LightColors = lightColorScheme(
    primary = KbBlue,
    onPrimary = Color.White,
    secondary = KbBlueDeep,
    background = Color(0xFFF5F6F7),
    onBackground = Color(0xFF1D2129),
    surface = Color.White,
    onSurface = Color(0xFF1D2129),
    surfaceVariant = Color(0xFFF7F8FA),
    onSurfaceVariant = Color(0xFF4E5969),
    outline = Color(0xFFE5E6EB),
    outlineVariant = Color(0xFFE5E6EB),
    error = KbRed
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4C8DFF),
    onPrimary = Color.White,
    secondary = Color(0xFF2B5AA8),
    background = Color(0xFF17181C),
    onBackground = Color(0xFFE5E6EB),
    surface = Color(0xFF1F2024),
    onSurface = Color(0xFFE5E6EB),
    surfaceVariant = Color(0xFF26272C),
    onSurfaceVariant = Color(0xFFA9AEB8),
    outline = Color(0xFF2A2C33),
    outlineVariant = Color(0xFF2A2C33),
    error = Color(0xFFF76964)
)

@Composable
fun KbTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // 外观设置（我的 → 外观）：跟随系统 / 浅色 / 深色（AppThemeState）
    val mode = com.kb.kbplatform.shell.ui.misc.AppThemeState.mode.collectAsState().value
    val effectiveDark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> darkTheme
    }
    MaterialTheme(
        colorScheme = if (effectiveDark) DarkColors else LightColors,
        content = content
    )
}
