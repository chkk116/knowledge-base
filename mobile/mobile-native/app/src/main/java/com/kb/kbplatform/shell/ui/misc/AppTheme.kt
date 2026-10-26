/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.misc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 外观设置（对标 MobileAppearance）：跟随系统 / 浅色 / 深色，KbTheme 读取 */
object AppThemeState {
    private val _mode = MutableStateFlow("system")   // system | light | dark
    val mode: StateFlow<String> = _mode

    fun set(m: String) { _mode.value = m }

    fun isDark(systemDark: Boolean): Boolean = when (_mode.value) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
}
