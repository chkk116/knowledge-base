/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 登录令牌的唯一存放处（后端 HMAC 令牌，默认 24h）。
 *
 * - StateFlow 驱动 AppRoot 在 登录页 / 主界面 之间切换
 * - 任意请求收到 401 时清空 → 全局退回登录页（与 Web 端 request.js 行为对齐）
 * - 明文存 SharedPreferences：与 Capacitor 版 localStorage 同级；后续接
 *   EncryptedSharedPreferences 只改这里，不影响调用方
 */
object TokenStore {
    private const val KEY_TOKEN = "kb_token"

    private lateinit var prefs: SharedPreferences
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("kb_shell_session", Context.MODE_PRIVATE)
        _token.value = prefs.getString(KEY_TOKEN, null)
    }

    /** 传 null 即登出 */
    fun set(value: String?) {
        if (value == null) prefs.edit().remove(KEY_TOKEN).apply()
        else prefs.edit().putString(KEY_TOKEN, value).apply()
        _token.value = value
    }
}
