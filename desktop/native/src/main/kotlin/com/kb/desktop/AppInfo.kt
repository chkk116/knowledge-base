package com.kb.desktop

/**
 * 桌面端自身版本信息。
 *
 * 与 Web/安卓独立：桌面端版本号只在这里维护，发版时同步改
 * [versionCode]/[versionName]（以及 build.gradle.kts 的 packageVersion）。
 * 更新检查拿 [channel] 向 `/api/public/app-version?channel=desktop` 取最新版本，
 * 用 [versionCode] 与本地比较决定是否提示升级。
 */
object AppInfo {
    const val versionCode: Int = 2
    const val versionName: String = "1.0.1"
    const val channel: String = "desktop"
}
