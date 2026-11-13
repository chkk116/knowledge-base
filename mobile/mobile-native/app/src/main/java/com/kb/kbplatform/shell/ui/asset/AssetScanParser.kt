/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.asset

/**
 * 扫码结果 → 资产编号，规则与网页版 MobileAssetScan.vue#parseScanned 一致：
 * 新标签是本站链接（编号在 hash 的 code= 里），旧标签是纯编号。
 *
 * 单独成文件且刻意只用 java.net.URI：这里是纯字符串解析，不依赖 Android 运行时，
 * 可以直接对编译产物跑 JVM 校验，也不会撞上 minSdk 24 下的平台版本差异。
 */
fun parseScanned(raw: String?): String {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return s
    return try {
        val u = java.net.URI(s)
        val h = u.rawFragment.orEmpty()
        if (h.isNotEmpty()) {
            if (h.contains("=")) {
                val v = queryValue(h, "code")
                if (!v.isNullOrEmpty()) return v
            } else {
                return decode(h)
            }
        }
        queryValue(u.rawQuery.orEmpty(), "code") ?: s
    } catch (e: Exception) {
        s
    }
}

/** 拆 "a=1&b=2" 形式的串并解码；用 rawXxx 取原始百分号编码，只解一次 */
private fun queryValue(src: String, key: String): String? {
    if (src.isEmpty()) return null
    for (pair in src.split('&')) {
        val i = pair.indexOf('=')
        if (i <= 0) continue
        if (pair.substring(0, i) == key) return decode(pair.substring(i + 1))
    }
    return null
}

/** 对齐前端 decodeURIComponent：URLDecoder 会把 + 当空格，先转义掉再解 */
private fun decode(v: String): String = try {
    java.net.URLDecoder.decode(v.replace("+", "%2B"), "UTF-8")
} catch (e: Exception) {
    v
}
