package com.kb.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// 品牌主色：深蓝紫（与登录卡片/图标渐变一致）
val KbPrimary = Color(0xFF4F46E5)
val KbPrimaryLight = Color(0xFF6366F1)
val KbPrimaryDeep = Color(0xFF8B5CF6)

/** 图标瓦片渐变（WPS 风：白底卡 + 彩色渐变图标） */
fun KbIconGradient(from: Color, to: Color): Brush = Brush.linearGradient(listOf(from, to))

val KbTileBlue = Color(0xFF3B82F6) to Color(0xFF6366F1)
val KbTileViolet = Color(0xFF6366F1) to Color(0xFF8B5CF6)
val KbTileGreen = Color(0xFF10B981) to Color(0xFF059669)
val KbTileAmber = Color(0xFFF59E0B) to Color(0xFFD97706)
val KbTileTeal = Color(0xFF0EA5E9) to Color(0xFF0284C7)
val KbTileRose = Color(0xFFF43F5E) to Color(0xFFBE123C)

// 语义色板瓦（方案二：浏览=蓝 学习=绿 社交=琥珀 数据=紫 管理=石板）
val KbTileSemBlue = Color(0xFF5A9BFF) to Color(0xFF2B7DE9)
val KbTileSemGreen = Color(0xFF3DCCA0) to Color(0xFF0E9F6E)
val KbTileSemAmber = Color(0xFFFFB13D) to Color(0xFFE08E0B)
val KbTileSemViolet = Color(0xFF9B8CFF) to Color(0xFF7C6AF2)
val KbTileSemSlate = Color(0xFF8A97A8) to Color(0xFF5B6B7C)

// 页面骨架色
val KbBg = Color(0xFFF5F6F8)        // 内容区背景
val KbBorder = Color(0xFFE5E7EB)    // 细边框
val KbTextMain = Color(0xFF111827)
val KbTextSub = Color(0xFF6B7280)
val KbSidebarSelected = Color(0xFFEEF2FF)

@Composable
fun KbTheme(content: @Composable () -> Unit) {
    // 仅浅色主题（桌面端产品按浅色设计，深色后续再做）
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = KbPrimary,
            surface = Color.White,
            background = KbBg,
        ),
        content = content,
    )
}
