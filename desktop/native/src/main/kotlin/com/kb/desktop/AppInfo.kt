package com.kb.desktop

/**
 * 桌面端自身版本信息。
 *
 * 与 Web/安卓独立：桌面端版本号只在这里维护，发版时只改
 * [versionCode]/[versionName]（build.gradle.kts 从本文件读取，安装包版本号不再单独维护）。
 * 更新检查拿 [channel] 向 `/api/public/app-version?channel=desktop` 取最新版本，
 * 用 [versionCode] 与本地比较决定是否提示升级。
 */
object AppInfo {
    const val versionCode: Int = 9
    const val versionName: String = "1.0.8"
    const val channel: String = "desktop"
}
