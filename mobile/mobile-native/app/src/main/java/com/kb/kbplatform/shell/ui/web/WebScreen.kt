/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.web

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.kb.kbplatform.shell.AppConfig
import com.kb.kbplatform.shell.TokenStore
import com.kb.kbplatform.shell.data.ApiClient
import com.kb.kbplatform.shell.ui.common.TopBar
import java.io.ByteArrayInputStream
import java.io.File

/**
 * WebView 承载页：原生壳里运行移动 Web 页面（/m/ 路径族）。
 *
 * 令牌桥接（与 frontend/src/main-mobile.js 的 bootstrap 配对）：
 *  1. 拦截 /m/ 的 HTML 文档请求，由 OkHttp 取回响应体
 *  2. 在 <head> 后注入 `window.__KB_NATIVE_TOKEN = "<token>"`
 *  3. main-mobile.js 入口最先执行：发现该变量即写入 localStorage.kb_token —— 无竞态
 *
 * P1 桥接补全：
 *  - 文件选择：onShowFileChooser → 系统选择器 / 相册；
 *    capture（拍照页 <input capture>）→ ACTION_IMAGE_CAPTURE + FileProvider 落盘 cache
 *  - JS alert/confirm：自绘系统弹窗（默认实现会静默丢弃）
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(path: String, title: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val base = AppConfig.effectiveBaseUrl
    var progress by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }

    val webView = remember(path) { MutableWebViewHolder() }
    var fileCallback by remember {
        mutableStateOf<android.webkit.ValueCallback<Array<Uri>>?>(null)
    }
    var captureUri by remember { mutableStateOf<Uri?>(null) }

    // 资产扫码页用 getUserMedia 调后置摄像头：运行时授权 + WebView 放行，缺一不可
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(ctx, "未授予相机权限，扫码不可用", Toast.LENGTH_SHORT).show()
    }

    val chooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileCallback
        fileCallback = null
        if (cb == null) return@rememberLauncherForActivityResult
        val uris = when {
            result.resultCode != android.app.Activity.RESULT_OK -> null
            // capture 拍照：结果落在我们传入的 FileProvider URI
            captureUri != null -> arrayOf(captureUri!!)
            else -> {
                val data = result.data
                val list = mutableListOf<Uri>()
                data?.data?.let { list.add(it) }
                val clip = data?.clipData
                if (clip != null) for (i in 0 until clip.itemCount) list.add(clip.getItemAt(i).uri)
                if (list.isEmpty()) null else list.toTypedArray()
            }
        }
        captureUri = null
        cb.onReceiveValue(uris)
    }

    BackHandler(enabled = canGoBack) {
        webView.view?.goBack()
    }

    // 进扫码页就先申请，避免点「开始扫码」时才弹系统框导致首次失败。
    // 必须放 LaunchedEffect（组合完成后执行）：launch() 若在组合阶段（如 AndroidView factory）
    // 调用，launcher 尚未注册，会抛 IllegalStateException: Launcher has not been initialized
    LaunchedEffect(path) {
        if (path.contains("/m/asset") &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = title, onBack = {
            if (webView.view?.canGoBack() == true) webView.view?.goBack() else onBack()
        })
        if (loading && progress < 100) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? = interceptHtml(base, request)

                            /**
                             * 站内跳转留在 WebView，站外一律交给系统浏览器。
                             *
                             * 不加这段时 WebView 可以被导航到任意站点：文档正文里的外链会把用户
                             * 带进一个没有地址栏的「假页面」，既没法返回也没法分辨域名（钓鱼面）。
                             * 站内页面需要继续注入令牌，必须留在 WebView 内。
                             */
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                val uri = request.url
                                val scheme = uri.scheme?.lowercase() ?: return true
                                if (scheme != "http" && scheme != "https") {
                                    openExternal(view, uri)      // mailto / tel / 微信 scheme 等
                                    return true
                                }
                                return if (isSameOrigin(base, uri)) {
                                    false                        // 站内：WebView 自己加载（含令牌注入）
                                } else {
                                    openExternal(view, uri)
                                    true
                                }
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                canGoBack = view.canGoBack()
                                loading = true
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                canGoBack = view.canGoBack()
                                loading = false
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            /** 只放行扫码页的摄像头请求，其余一律拒绝，别给任意网页开相机 */
                            override fun onPermissionRequest(request: PermissionRequest) {
                                val wantsCamera = request.resources.any {
                                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                                }
                                if (!wantsCamera || !path.contains("/m/asset")) {
                                    request.deny(); return
                                }
                                if (ContextCompat.checkSelfPermission(c, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    request.deny()
                                }
                            }

                            /** 文件选择桥：<input type=file> 与 capture 拍照 */
                            override fun onShowFileChooser(
                                view: WebView,
                                callback: android.webkit.ValueCallback<Array<Uri>>,
                                params: WebChromeClient.FileChooserParams
                            ): Boolean {
                                fileCallback?.onReceiveValue(null)   // 上一轮未消费的回调先作废
                                fileCallback = callback
                                try {
                                    val intent: Intent = if (params.isCaptureEnabled) {
                                        // 拍照：写进 cache 临时文件，经 FileProvider 授权给相机应用
                                        val dir = File(c.cacheDir, "web_capture").apply { mkdirs() }
                                        val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
                                        val uri = FileProvider.getUriForFile(
                                            c, "${c.packageName}.fileprovider", file
                                        )
                                        captureUri = uri
                                        Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                                            putExtra(MediaStore.EXTRA_OUTPUT, uri)
                                            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                    } else {
                                        params.createIntent()
                                    }
                                    chooserLauncher.launch(intent)
                                } catch (e: android.content.ActivityNotFoundException) {
                                    Toast.makeText(c, "设备上没有可用的相机或文件管理器", Toast.LENGTH_SHORT).show()
                                    fileCallback = null
                                    callback.onReceiveValue(null)
                                }
                                return true
                            }

                            override fun onJsAlert(
                                view: WebView, url: String, message: String, result: JsResult
                            ): Boolean {
                                android.app.AlertDialog.Builder(c)
                                    .setMessage(message)
                                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                                    .setOnCancelListener { result.cancel() }
                                    .show()
                                return true
                            }

                            override fun onJsConfirm(
                                view: WebView, url: String, message: String, result: JsResult
                            ): Boolean {
                                android.app.AlertDialog.Builder(c)
                                    .setMessage(message)
                                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                                    .setOnCancelListener { result.cancel() }
                                    .show()
                                return true
                            }
                        }
                        loadUrl(base + path)
                    }.also { webView.view = it }
                },
                onRelease = { it.destroy() }
            )
        }
    }
}

/** remember(path) 需要一个稳定持有者，避免重组期间 WebView 被意外重建 */
private class MutableWebViewHolder {
    var view: WebView? = null
}

/** 同源判定：协议 + 主机 + 端口三者一致才算站内（端口缺省按协议补 80/443） */
private fun isSameOrigin(base: String, target: Uri): Boolean {
    val b = try { Uri.parse(base) } catch (e: Exception) { return false }
    val bs = b.scheme?.lowercase() ?: return false
    val ts = target.scheme?.lowercase() ?: return false
    if (bs != ts) return false
    if (!b.host.equals(target.host, ignoreCase = true)) return false
    val bp = if (b.port > 0) b.port else if (bs == "https") 443 else 80
    val tp = if (target.port > 0) target.port else if (ts == "https") 443 else 80
    return bp == tp
}

/** 用系统浏览器打开站外链接；无可用应用时静默忽略（不能因为点了外链就崩） */
private fun openExternal(view: WebView, uri: Uri) {
    try {
        view.context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        /* 无浏览器 / 无对应 scheme 处理者：忽略 */
    }
}

private fun interceptHtml(base: String, request: WebResourceRequest): WebResourceResponse? {
    val url = request.url
    val target = url.toString()
    if (!request.method.equals("GET", true)) return null
    // 必须限定在 /m 与 /m/ 路径族：startsWith("$base/m") 会把 /metrics、/mobile 也算进来（审查 r14）
    val mobilePrefix = "$base/m/"
    if (!target.startsWith(mobilePrefix) && target != "$base/m") return null
    // 只拦文档请求（Accept 含 text/html），静态资源与 XHR 放行
    val accept = request.requestHeaders?.get("Accept") ?: return null
    if (!accept.contains("text/html")) return null

    return try {
        val resp = ApiClient.http.newCall(
            okhttp3.Request.Builder()
                .url(target)
                .header("User-Agent", ApiClient.USER_AGENT)
                .header("Accept", accept)
                .build()
        ).execute()
        resp.use { r ->
            if (!r.isSuccessful) return null   // 交回 WebView 默认处理
            var html = r.body?.string() ?: return null
            val token = TokenStore.token.value
            val js = if (token != null) {
                // 除引号/反斜杠外还要挡 </script>：否则令牌里出现该串会提前闭合注入的 script 块（审查 r14）
                val safe = token.replace("\\", "\\\\").replace("\"", "\\\"").replace("</", "<\\/")
                "<script>window.__KB_NATIVE_TOKEN=\"$safe\";</script>"
            } else {
                "<script>window.__KB_NATIVE_TOKEN=null;</script>"
            }
            // 手工拼接而不是 replaceFirst 的替换串：替换串里的 $ 和 \ 会被当成组引用解析，
            // 令牌一旦含这类字符就会被悄悄改坏（注入失败但页面不报错）
            val headMatch = Regex("<head([^>]*)>", RegexOption.IGNORE_CASE).find(html)
            if (headMatch != null) {
                val at = headMatch.range.last + 1
                html = html.substring(0, at) + js + html.substring(at)
            }
            WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
        }
    } catch (e: Exception) {
        null
    }
}
