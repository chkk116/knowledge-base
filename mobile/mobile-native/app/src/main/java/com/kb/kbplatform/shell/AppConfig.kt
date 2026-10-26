/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局配置：后端根地址。
 *
 * **地址由构建期决定，App 内不提供修改入口**（2026-10-25 定）：
 * 让普通用户改服务器地址，既容易被诱导指到钓鱼站点，也会出现「填了旧域名 → 打开别的站」这类事故。
 * 换服务器时重新出包即可。
 *
 * - 正式包：`-PKB_BASE_URL=https://你的域名` 注入 BuildConfig，仓库里不存公网地址；
 * - 本地 debug：未注入则回落 `http://10.0.2.2:8080`（模拟器访问宿主机的保留地址）。
 */
object AppConfig {
    /** 老版本存过用户自定义地址的 key，启动时清掉，避免旧值继续生效 */
    private const val LEGACY_KEY_BASE_URL = "kb_base_url"

    private val FIXED_BASE_URL: String =
        BuildConfig.KB_BASE_URL.ifBlank { "http://10.0.2.2:8080" }

    private val _baseUrl = MutableStateFlow(FIXED_BASE_URL)
    val baseUrl: StateFlow<String> = _baseUrl

    /** 去掉末尾斜杠的基址，供拼 URL 使用 */
    val effectiveBaseUrl: String
        get() = FIXED_BASE_URL.trimEnd('/')

    /** 构建时是否注入了正式地址（未注入说明是本地调试包） */
    fun isInjected(): Boolean = BuildConfig.KB_BASE_URL.isNotBlank()

    fun init(ctx: Context) {
        _baseUrl.value = FIXED_BASE_URL
        // 清掉历史遗留的自定义地址（含指向旧域名 www.你的域名 的值）
        try {
            ctx.getSharedPreferences("kb_shell_config", Context.MODE_PRIVATE)
                .edit().remove(LEGACY_KEY_BASE_URL).apply()
        } catch (e: Exception) {
            /* 清理失败不影响使用 */
        }
    }
}
