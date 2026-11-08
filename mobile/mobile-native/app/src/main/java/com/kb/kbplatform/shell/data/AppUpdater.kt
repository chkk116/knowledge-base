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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用内更新（替代 Capacitor 版 ApkInstallerPlugin 的核心职责）：
 * GET /api/public/app-version → versionCode 比本地高 → 提示 → OkHttp 下载 APK（进度）→
 * FileProvider + ACTION_VIEW 拉起系统安装器（需 REQUEST_INSTALL_PACKAGES 授权）。
 * versionCode=0 表示后台未配置，静默跳过不打扰。
 */
object AppUpdater {

    /** 大文件下载失败重试次数（每次都会 Range 续传已下载部分） */
    private const val MAX_DOWNLOAD_ATTEMPTS = 3

    /** 不可重试的错误（域名白名单、校验失败、4xx）：重试也没意义，直接告诉用户 */
    private class NonRetryableException(message: String) : Exception(message)

    /**
     * 下载专用 OkHttpClient：共享 client 的 15s 读超时是给普通接口的。
     * 22MB APK 在弱网下只要停顿超过 15s 就会 SocketTimeout / connection abort，
     * 所以读超时放宽到 60s（OkHttp 是「两次读之间的最大间隔」，不是总时长）。
     */
    private val downloadClient: OkHttpClient by lazy {
        ApiClient.http.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    sealed class State {
        data object Idle : State()
        data class Available(
            val versionName: String,
            val notes: String,
            val url: String,
            val force: Boolean,
            /** 服务端给的 SHA-256，下载完校验用；空串表示没给，跳过校验 */
            val checksum: String = ""
        ) : State()

        data class Downloading(val progress: Int) : State()
        data class Ready(val file: File) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /**
     * 最近一次「有可用更新」的元数据。下载失败后状态会变成 Error（不再持有 url/checksum），
     * 但用户点「重试」必须还能续传同一个包，所以这里留一份。
     */
    private var lastAvailable: State.Available? = null

    fun apkFile(ctx: Context): File = File(ctx.filesDir, "update.apk")

    /**
     * 语义化版本比较：1.3.4 > 0.9.11（按数字段比，不是字符串比）。
     * 任一侧解析不出数字段就返回 false，避免把畸形版本号当更新推给用户。
     */
    private fun semverGreater(remote: String, local: String): Boolean {
        fun parts(v: String): List<Int>? {
            val raw = v.trim().removePrefix("v").removePrefix("V")
                .substringBefore('-').substringBefore('+')
            if (raw.isBlank()) return null
            val out = ArrayList<Int>()
            for (seg in raw.split('.')) out.add(seg.toIntOrNull() ?: return null)
            return out
        }
        val r = parts(remote) ?: return false
        val l = parts(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** 拉取版本信息并与本地 versionCode 比对；无可更新时回落 Idle */
    suspend fun check(ctx: Context) {
        try {
            val v: JSONObject = KbApi.appVersion()
            val remoteCode = v.optLong("versionCode", 0)
            if (remoteCode <= 0) {
                _state.value = State.Idle
                return
            }
            var localCode = 0L
            var localName = ""
            try {
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                localCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
                localName = pi.versionName ?: ""
            } catch (e: Exception) {
                // 取不到就按 0 / 空处理，下面会走「只比远程 code」的兜底
            }
            // 历史版本用的是 0.9.x（code 三位数），现在换到 1.3.x（code 30x）：
            // 光比 code 会得出「远程更旧」的结论，用户永远收不到更新。所以 name 也要比一次。
            val remoteName = v.optString("versionName")
            val newer = remoteCode > localCode || semverGreater(remoteName, localName)
            if (newer) {
                val a = State.Available(
                    versionName = v.optString("versionName").ifBlank { remoteCode.toString() },
                    notes = v.optString("notes"),
                    url = v.optString("url"),
                    force = v.optBoolean("force", false),
                    checksum = v.optString("checksum")
                )
                lastAvailable = a
                _state.value = a
            } else {
                lastAvailable = null
                _state.value = State.Idle
            }
        } catch (e: Exception) {
            // 版本接口失败不打扰用户（可能离线）；保留 lastAvailable，让「重试下载」仍可用
            _state.value = State.Idle
        }
    }

    /** 下载 APK 到 filesDir，支持断点续传（Range 续传 .part，服务器不支持 206 时整包重来）；完成后进入 Ready */
    suspend fun download(ctx: Context) {
        // 失败后状态是 Error，此时只能从 lastAvailable 拿下载元数据（支持「重试」续传）
        val avail = (_state.value as? State.Available) ?: lastAvailable
        if (avail == null || avail.url.isBlank()) {
            _state.value = State.Error("下载地址无效")
            return
        }
        _state.value = State.Downloading(0)
        var lastPct = 0
        var lastError: Exception? = null
        // 弱网/切网/运营商掐断会让大文件下载中途 abort（SocketException: Software caused connection abort）。
        // 22MB 单次失败就前功尽弃太脆，这里最多试 3 次；.part 会保留，重试自动 Range 续传。
        for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
            try {
                val file = withContext(Dispatchers.IO) {
                    downloadOnce(ctx, avail) { pct ->
                        lastPct = pct
                        _state.value = State.Downloading(pct)
                        UpdateNotify.progress(ctx, pct)
                    }
                }
                _state.value = State.Ready(file)
                // 通知栏留一条「点击安装」：用户切走后不用再回 App 找入口
                UpdateNotify.done(ctx, file)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e      // 取消要原样抛出，否则结构化并发被破坏、状态还停在 Error
            } catch (e: NonRetryableException) {
                fail(ctx, e.message ?: "下载失败")
                return
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    // 保持已下载进度可见，别让用户以为从 0 重来
                    _state.value = State.Downloading(lastPct)
                    delay(1200L * attempt)
                }
            }
        }
        fail(ctx, friendlyDownloadError(lastError))
    }

    private fun fail(ctx: Context, message: String) {
        _state.value = State.Error(message)
        UpdateNotify.fail(ctx, message)
    }

    /**
     * 单次下载尝试（内部会读 .part 续传）。成功返回最终文件，失败抛异常由外层决定是否重试。
     * @param onProgress 0~100
     */
    private suspend fun downloadOnce(
        ctx: Context,
        avail: State.Available,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val url = avail.url
        val full = if (url.startsWith("http")) url else AppConfig.effectiveBaseUrl + url
        // 🔒 只允许从「与后端同源」的域名下载 APK（外加构建期白名单 KB_ALLOWED_HOSTS）。
        // 版本信息是网络返回的：接口被篡改 / 明文 HTTP 被中间人改写时，没有这层校验
        // 就会把任意 APK 下载下来并拉起安装器 —— 等于远程执行代码。
        if (!allowedHost(full)) {
            throw NonRetryableException("更新地址不在允许的域名内，已阻止下载")
        }
        val f = apkFile(ctx)
        val tmp = File(f.absolutePath + ".part")
        // 残片必须与本次下载地址一致才能续传：换版本/换域名后残留的 .part 拼进来会是损坏包
        val meta = File(f.absolutePath + ".part.meta")
        val oldStamp = if (meta.exists()) runCatching { meta.readText() }.getOrDefault("") else ""
        var existing = if (tmp.exists()) tmp.length() else 0L
        if (existing > 0 && oldStamp != full) {
            tmp.delete()
            meta.delete()
            existing = 0L
        }
        runCatching { meta.writeText(full) }

        val reqBuilder = Request.Builder()
            .url(full)
            .header("User-Agent", ApiClient.USER_AGENT)
        if (existing > 0) reqBuilder.header("Range", "bytes=$existing-")

        // 下载专用 client：共享 client 的 15s 读超时是给普通接口的，
        // 22MB 弱网下载只要停顿超过 15s 就会 SocketTimeout / connection abort。
        downloadClient.newCall(reqBuilder.build()).execute().use { r ->
            val resume = r.code == 206   // Partial Content：续传成功
            if (r.code == 416) {
                // 残片比远端还大 / 远端已换包：续传永远失败，清掉残片，交给外层重试整包
                tmp.delete()
                meta.delete()
                throw IllegalStateException("断点数据已失效")
            }
            if (!resume && !r.isSuccessful) {
                // 5xx / 429 属服务端临时状态，可重试；4xx（除 408）重试也没用
                if (r.code >= 500 || r.code == 429 || r.code == 408) {
                    throw IllegalStateException("下载失败(${r.code})")
                }
                throw NonRetryableException("下载失败(${r.code})")
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
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) throw IllegalStateException("保存更新包失败")
        }

        // 校验安装包完整性：版本接口被中间人改写 / 下载被截断时，宁可报错也不要装上去。
        // 服务端没给 checksum（老数据）就跳过，保持向后兼容。
        val expect = avail.checksum
        if (expect.isNotBlank()) {
            val actual = sha256Of(apkFile(ctx))
            if (!actual.equals(expect, ignoreCase = true)) {
                apkFile(ctx).delete()
                throw NonRetryableException("安装包校验失败，请稍后重试")
            }
        }
        apkFile(ctx)
    }

    /** 把底层网络异常翻译成用户能看懂的话（不再把 Software caused connection abort 直接甩给用户） */
    private fun friendlyDownloadError(e: Exception?): String {
        val m = (e?.message ?: "").lowercase()
        return when {
            m.contains("connection abort") || m.contains("software caused") || m.contains("econnaborted") ->
                "网络连接被中断，请切换网络后重试（已下载部分会继续）"
            m.contains("timeout") || m.contains("timed out") ->
                "网络超时，请检查网络后重试（已下载部分会继续）"
            m.contains("unable to resolve host") || m.contains("unknownhost") ->
                "无法连接服务器，请检查网络或稍后重试"
            m.contains("ssl") || m.contains("certificate") ->
                "安全连接失败，请检查系统时间或网络代理"
            m.contains("no space") || m.contains("enospc") ->
                "存储空间不足，请清理后重试"
            m.contains("下载失败(") || m.contains("断点") -> "服务器暂时不可用，请稍后重试"
            else -> e?.message?.takeIf { it.isNotBlank() } ?: "下载失败，请稍后重试"
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

    enum class InstallResult { STARTED, NEED_PERMISSION, FAILED }

    /**
     * 拉起系统安装器。
     *
     * 两处容易踩的坑：
     * 1. MIME 必须写 `application/vnd.android.package-archive`（**package** 不是 pack），
     *    写错时多数 ROM 会提示「无法打开文件」，看起来像安装包坏了；
     * 2. Android 8+ 需要「安装未知应用」授权，未授权时 ACTION_VIEW 会静默失败。
     *    这里显式判断并跳设置页，用户至少知道该点哪个开关。
     */
    fun install(ctx: Context): InstallResult {
        val f = apkFile(ctx)
        if (!f.exists()) return InstallResult.FAILED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !ctx.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                ctx.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${ctx.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return InstallResult.NEED_PERMISSION
        }
        return try {
            val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            InstallResult.STARTED
        } catch (e: Exception) {
            // 别再静默吞掉：安装器拉不起来时，只有这条日志能看出是 FileProvider 还是 Intent 的问题
            android.util.Log.e("KbUpdater", "拉起安装器失败: ${e.javaClass.simpleName}: ${e.message}", e)
            InstallResult.FAILED
        }
    }

    /** 用户点「稍后」时清掉状态，避免对话框反复弹 */
    fun reset() {
        _state.value = State.Idle
    }

    private fun sha256Of(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
