/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.kb.kbplatform.shell.AppConfig
import com.kb.kbplatform.shell.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * 应用内更新（替代 Capacitor 版 ApkInstallerPlugin 的核心职责）：
 * GET /api/public/app-version → versionCode 比本地高 → 提示 → OkHttp 下载 APK（进度）→
 * FileProvider + ACTION_VIEW 拉起系统安装器（需 REQUEST_INSTALL_PACKAGES 授权）。
 * versionCode=0 表示后台未配置，静默跳过不打扰。
 */
object AppUpdater {

    sealed class State {
        data object Idle : State()
        data class Available(
            val versionName: String,
            val notes: String,
            val url: String,
            val force: Boolean
        ) : State()

        data class Downloading(val progress: Int) : State()
        data class Ready(val file: File) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun apkFile(ctx: Context): File = File(ctx.filesDir, "update.apk")

    /** 拉取版本信息并与本地 versionCode 比对；无可更新时回落 Idle */
    suspend fun check(ctx: Context) {
        try {
            val v: JSONObject = KbApi.appVersion()
            val remoteCode = v.optLong("versionCode", 0)
            if (remoteCode <= 0) {
                _state.value = State.Idle
                return
            }
            val localCode = try {
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            } catch (e: Exception) {
                0L
            }
            _state.value = if (remoteCode > localCode) State.Available(
                versionName = v.optString("versionName").ifBlank { remoteCode.toString() },
                notes = v.optString("notes"),
                url = v.optString("url"),
                force = v.optBoolean("force", false)
            ) else State.Idle
        } catch (e: Exception) {
            // 版本接口失败不打扰用户（可能离线）
            _state.value = State.Idle
        }
    }

    /** 下载 APK 到 filesDir，支持断点续传（Range 续传 .part，服务器不支持 206 时整包重来）；完成后进入 Ready */
    suspend fun download(ctx: Context) {
        val url = (_state.value as? State.Available)?.url
        if (url.isNullOrBlank()) {
            _state.value = State.Error("下载地址无效")
            return
        }
        _state.value = State.Downloading(0)
        try {
            withContext(Dispatchers.IO) {
                val full = if (url.startsWith("http")) url else AppConfig.effectiveBaseUrl + url
                // 🔒 只允许从「与后端同源」的域名下载 APK（外加构建期白名单 KB_ALLOWED_HOSTS）。
                // 版本信息是网络返回的：接口被篡改 / 明文 HTTP 被中间人改写时，没有这层校验
                // 就会把任意 APK 下载下来并拉起安装器 —— 等于远程执行代码。
                if (!allowedHost(full)) {
                    throw IllegalStateException("更新地址不在允许的域名内，已阻止下载")
                }
                val f = apkFile(ctx)
                val tmp = File(f.absolutePath + ".part")
                val existing = if (tmp.exists()) tmp.length() else 0L

                val reqBuilder = Request.Builder()
                    .url(full)
                    .header("User-Agent", ApiClient.USER_AGENT)
                if (existing > 0) reqBuilder.header("Range", "bytes=$existing-")

                ApiClient.http.newCall(reqBuilder.build()).execute().use { r ->
                    val resume = r.code == 206   // Partial Content：续传成功
                    if (!resume && !r.isSuccessful) {
                        throw IllegalStateException("下载失败(${r.code})")
                    }
                    val body = r.body ?: throw IllegalStateException("空响应")
                    val total = if (resume) existing + body.contentLength() else body.contentLength()
                    val base = if (resume) existing else 0L

                    body.byteStream().use { input ->
                        java.io.FileOutputStream(tmp, resume).use { output ->
                            val buf = ByteArray(64 * 1024)
                            var read: Int
                            var done = base
                            var lastPct = -1
                            while (input.read(buf).also { read = it } > 0) {
                                output.write(buf, 0, read)
                                done += read
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        _state.value = State.Downloading(pct)
                                    }
                                }
                            }
                        }
                    }
                    if (f.exists()) f.delete()
                    if (!tmp.renameTo(f)) throw IllegalStateException("保存更新包失败")
                }
            }
            _state.value = State.Ready(apkFile(ctx))
        } catch (e: Exception) {
            _state.value = State.Error(e.message ?: "下载失败")
        }
    }

    /**
     * 下载域名白名单：后端同源域名 + 构建期注入的 KB_ALLOWED_HOSTS。
     * 与 Capacitor 版 ApkInstallerPlugin 的第二道防线同思路（那边叫 kb_allowed_hosts）。
     *
     * `www.` 前缀视为同一站点：知识库主站在 你的域名，而后端生成的下载地址历史上是
     * www.你的域名，两者必须互相认可，否则正常更新会被误拦。
     */
    private fun allowedHost(url: String): Boolean {
        val host = try { java.net.URI(url).host?.lowercase() } catch (e: Exception) { null } ?: return false
        val baseHost = try { java.net.URI(AppConfig.effectiveBaseUrl).host?.lowercase() } catch (e: Exception) { null }
        if (baseHost != null && bareHost(host) == bareHost(baseHost)) return true
        return BuildConfig.KB_ALLOWED_HOSTS.split(',')
            .map { bareHost(it.trim().lowercase()) }
            .filter { it.isNotEmpty() }
            .contains(bareHost(host))
    }

    private fun bareHost(h: String): String = h.removePrefix("www.")

    /** 拉起系统安装器；未授权「安装未知应用」时系统会引导用户去开 */
    fun install(ctx: Context): Boolean {
        val f = apkFile(ctx)
        if (!f.exists()) return false
        return try {
            val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android-package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
