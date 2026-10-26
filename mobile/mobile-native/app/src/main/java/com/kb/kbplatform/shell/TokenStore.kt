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
    private const val KEY_USERNAME = "kb_username"

    private lateinit var prefs: SharedPreferences
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token
    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("kb_shell_session", Context.MODE_PRIVATE)
        _token.value = prefs.getString(KEY_TOKEN, null)
        _username.value = prefs.getString(KEY_USERNAME, null).orEmpty()
    }

    /** 传 null 即登出 */
    fun set(value: String?) {
        if (value == null) {
            prefs.edit().remove(KEY_TOKEN).remove(KEY_USERNAME).apply()
            _username.value = ""
        } else {
            prefs.edit().putString(KEY_TOKEN, value).apply()
        }
        _token.value = value
    }

    /** 登录成功后记录用户名：离线缓存按账号隔离的键 */
    fun setUsername(v: String) {
        prefs.edit().putString(KEY_USERNAME, v).apply()
        _username.value = v
    }

    // ---------- 身份缓存（权限/角色/昵称）：登录响应与 /auth/me 写入，后台管理入口显隐用 ----------

    private val _info = MutableStateFlow<org.json.JSONObject?>(null)
    val info: StateFlow<org.json.JSONObject?> = _info

    fun setInfo(json: org.json.JSONObject?) {
        _info.value = json
    }

    /** 是否拥有某权限码（ADMIN 角色隐含全权，与后端 TokenClaims.hasPerm 语义对齐） */
    fun hasPerm(perm: String): Boolean {
        val i = _info.value ?: return false
        if (i.optString("role") == "ADMIN") return true
        val perms = i.optString("permissions")
        if (perms.split(',').any { it.trim().equals("*", ignoreCase = true) }) return true
        return perms.split(',').any { it.trim().equals(perm, ignoreCase = true) }
    }
}
