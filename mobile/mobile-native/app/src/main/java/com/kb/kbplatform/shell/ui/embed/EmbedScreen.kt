/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.embed

import androidx.compose.runtime.Composable
import com.kb.kbplatform.shell.ui.web.WebScreen

/**
 * 功能嵌入页（对标 MobileEmbed /embed/:kind）：
 * 套壳中此类页面本来就是「嵌入桌面后台表单」——数据看板 / 页脚设置 / 功能开关 /
 * 外观设置 / 水印设置 / 登录页二维码。这些桌面管理表单形态复杂，直接承载对应
 * 桌面路由（桌面页自带 1024/640 响应式断点）。
 */
private val KIND_MAP = mapOf(
    "adminstats" to ("/admin/stats" to "数据看板"),
    "adminfooter" to ("/admin/site-footer" to "页脚设置"),
    "adminmaintain" to ("/admin/maintain" to "功能开关"),
    "adminappearance" to ("/admin/appearance" to "外观设置"),
    "adminwatermark" to ("/admin/watermark" to "水印设置"),
    "adminloginqr" to ("/admin/login-qr" to "登录页二维码")
)

@Composable
fun EmbedScreen(kind: String, onBack: () -> Unit) {
    val mapped = KIND_MAP[kind] ?: ("/admin/stats" to "数据看板")
    WebScreen(path = mapped.first, title = mapped.second, onBack = onBack)
}
