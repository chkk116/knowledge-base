/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest

/**
 * 应用锁（替代 Web 版 utils/appLock.js 的原生职责）：
 * PIN（4-6 位数字）+ 随机 salt + SHA-256 哈希落本地，与后端密码哈希同套路；
 * 冷启动必锁；退后台超过宽限期（60s，对齐 Web 版 timeout 默认值）回前台加锁。
 * 解锁只改内存态，重进后台仍会按规则重新上锁。
 */
object AppLock {
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "hash"
    private const val BACKGROUND_GRACE_MS = 60_000L

    private lateinit var prefs: SharedPreferences

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked

    private var backgroundedAt = 0L

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("kb_shell_lock", Context.MODE_PRIVATE)
        _locked.value = enabled()
    }

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    /** 4-6 位数字 PIN 合法 */
    fun validPin(pin: String): Boolean = pin.length in 4..6 && pin.all { it.isDigit() }

    fun setup(pin: String) {
        // salt 用 SecureRandom：kotlin.random 是可预测的 PRNG，做盐没有意义
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val salt = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, sha256(salt + pin))
            .apply()
    }

    fun disable(pin: String): Boolean {
        if (!verify(pin)) return false
        prefs.edit().clear().apply()
        _locked.value = false
        return true
    }

    fun verify(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        val hash = prefs.getString(KEY_HASH, null) ?: return false
        return sha256(salt + pin) == hash
    }

    fun unlock(pin: String): Boolean {
        if (verify(pin)) {
            _locked.value = false
            return true
        }
        return false
    }

    // ---------- 生命周期钩子（MainActivity 调用） ----------

    fun onAppBackgrounded() {
        backgroundedAt = System.currentTimeMillis()
    }

    fun onAppForegrounded() {
        if (!enabled()) return
        val away = System.currentTimeMillis() - backgroundedAt
        // 冷启动（init 时已锁）或离开超过宽限期 → 上锁
        if (backgroundedAt == 0L || away > BACKGROUND_GRACE_MS) _locked.value = true
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
