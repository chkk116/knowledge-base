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
 * 全局配置：后端根地址（用户可在「我的 → 设置」里改，改完立即生效）。
 *
 * 默认 http://10.0.2.2:8080 是 Android 模拟器访问宿主机 8080 的保留地址；
 * 真机联调改成电脑局域网 IP，生产改成 https://kb.example.com。
 * 与 Web 端 VITE_NATIVE_ORIGIN 的语义一致：仓库里不存任何公网地址。
 */
object AppConfig {
    private const val KEY_BASE_URL = "kb_base_url"
    private const val DEFAULT_BASE_URL = "http://10.0.2.2:8080"

    private lateinit var prefs: SharedPreferences

    private val _baseUrl = MutableStateFlow(DEFAULT_BASE_URL)
    val baseUrl: StateFlow<String> = _baseUrl

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("kb_shell_config", Context.MODE_PRIVATE)
        _baseUrl.value = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
    }

    /** 去掉末尾斜杠的当前基址，供拼 URL 使用 */
    val effectiveBaseUrl: String
        get() = _baseUrl.value.trim().trimEnd('/')

    fun setBaseUrl(url: String) {
        val v = url.trim().trimEnd('/')
        prefs.edit().putString(KEY_BASE_URL, v).apply()
        _baseUrl.value = v
    }

    fun isDefaultBaseUrl(): Boolean = _baseUrl.value == DEFAULT_BASE_URL
}
