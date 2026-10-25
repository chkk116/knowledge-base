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
 *
 * 线程与初始化（审查 r13）：
 * - prefs 可为 null（init 之前），此时 set() 只改内存值并记 pending，init 时再合并落盘，
 *   避免 lateinit 未初始化直接抛 UninitializedPropertyAccessException
 * - 读改写全部 synchronized：SharedPreferences 写入与 StateFlow 更新保持原子
 */
object TokenStore {
    private const val KEY_TOKEN = "kb_token"

    private var prefs: SharedPreferences? = null
    /** init 之前设置的令牌，等 init 时再落盘 */
    private var pending: String? = null

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token

    fun init(ctx: Context) {
        synchronized(this) {
            if (prefs != null) return   // Activity 重建重复调用：只生效一次
            val p = ctx.applicationContext.getSharedPreferences("kb_shell_session", Context.MODE_PRIVATE)
            prefs = p
            val value = pending ?: p.getString(KEY_TOKEN, null)
            pending = null
            _token.value = value
            if (value != null) p.edit().putString(KEY_TOKEN, value).apply()
        }
    }

    /** 传 null 即登出 */
    fun set(value: String?) {
        synchronized(this) {
            _token.value = value
            val p = prefs
            if (p == null) { pending = value; return }
            if (value == null) {
                // 登出必须立即落盘：apply() 异步，进程被杀会残留 token
                p.edit().remove(KEY_TOKEN).commit()
            } else {
                p.edit().putString(KEY_TOKEN, value).apply()
            }
        }
    }
}
