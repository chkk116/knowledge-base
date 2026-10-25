/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.URI

/**
 * 全局配置：后端根地址（用户可在「我的 → 设置」里改，改完立即生效）。
 *
 * 默认 http://10.0.2.2:8080 是 Android 模拟器访问宿主机 8080 的保留地址；
 * 真机联调改成电脑局域网 IP，生产改成 https://kb.example.com。
 * 与 Web 端 VITE_NATIVE_ORIGIN 的语义一致：仓库里不存任何公网地址。
 *
 * 校验（审查 r13）：用户输入可能是 "   "、"/"、"192.168.1.5:8080"，直接存会把所有请求
 * 拼成非法 URL。这里统一收敛：空 → 回落默认；缺协议 → 自动补 http://；非法 → 抛错由 UI 提示。
 */
object AppConfig {
    private const val KEY_BASE_URL = "kb_base_url"

    /**
     * 出厂默认地址。
     * - 正式包：构建时 `-PKB_BASE_URL=https://www.你的域名` 注入到 BuildConfig，
     *   装完即可直接登录，不需要用户先手填地址。
     * - 未注入（本地 debug / 仓库默认）：回落 `http://10.0.2.2:8080`，即 Android 模拟器
     *   访问宿主机 8080 的保留地址，真机联调改成电脑局域网 IP 即可。
     * 仓库里始终不存公网地址，与 Web 端 VITE_NATIVE_ORIGIN 同一策略。
     */
    private val DEFAULT_BASE_URL: String =
        BuildConfig.KB_DEFAULT_BASE_URL.ifBlank { "http://10.0.2.2:8080" }

    private var prefs: SharedPreferences? = null

    private val _baseUrl = MutableStateFlow(DEFAULT_BASE_URL)
    val baseUrl: StateFlow<String> = _baseUrl

    fun init(ctx: Context) {
        synchronized(this) {
            if (prefs != null) return
            val p = ctx.applicationContext.getSharedPreferences("kb_shell_config", Context.MODE_PRIVATE)
            prefs = p
            _baseUrl.value = normalize(p.getString(KEY_BASE_URL, null) ?: DEFAULT_BASE_URL)
        }
    }

    /** 去掉末尾斜杠的当前基址，供拼 URL 使用 */
    val effectiveBaseUrl: String
        get() = _baseUrl.value.trim().trimEnd('/')

    /**
     * 保存基址；空串/纯斜杠 = 恢复默认。
     * @throws IllegalArgumentException 协议或主机非法（由调用方提示用户）
     */
    fun setBaseUrl(url: String) {
        val v = normalize(url)
        synchronized(this) {
            prefs?.edit()?.putString(KEY_BASE_URL, v)?.apply()
            _baseUrl.value = v
        }
    }

    fun isDefaultBaseUrl(): Boolean = _baseUrl.value == DEFAULT_BASE_URL

    /** 收敛成一个可用的根地址：补协议、去尾斜杠、校验 host */
    private fun normalize(input: String): String {
        var v = input.trim()
        if (v.isEmpty() || v == "/") return DEFAULT_BASE_URL
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://$v"
        v = v.trimEnd('/')
        val host = try { URI(v).host } catch (e: Exception) { null }
        require(!host.isNullOrBlank()) { "地址格式不正确，示例：http://192.168.1.10:8080" }
        return v
    }
}
