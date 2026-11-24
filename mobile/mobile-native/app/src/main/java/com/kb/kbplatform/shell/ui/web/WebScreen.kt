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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
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
 * 令牌桥接（不依赖前端是否实现接收端，宿主侧直接落 localStorage）：
 *  1. 拦截**站内** HTML 文档请求（`/m/` 移动页与 `EmbedScreen` 加载的 `/admin/` 路径 PC 表单都算），
 *     由 OkHttp 取回响应体
 *  2. 在 <head> 后注入 `<script>`：挂 `window.__KB_NATIVE_TOKEN` 并**立刻** `localStorage.setItem('kb_token', …)`
 *  3. 前端 `main-mobile.js` / `main.js` 若将来补了接收端，变量那条路照常可用；没补也不影响登录
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
    // 白屏兜底：加载失败或页面渲染为空时给出可见提示 + 重试，而不是一片空白
    var pageError by remember { mutableStateOf<String?>(null) }
    fun markPageError(msg: String) { if (pageError == null) pageError = msg }

    val webView = remember(path) { MutableWebViewHolder() }
    var fileCallback by remember {
        mutableStateOf<android.webkit.ValueCallback<Array<Uri>>?>(null)
    }
    var captureUri by remember { mutableStateOf<Uri?>(null) }

    // 只有用户主动点「改用实时预览取景」时才会走到 onPermissionRequest，届时按需申请。
    // 扫码页默认走 <input capture> 的系统相机（不需要 CAMERA 运行时权限），进页面就弹权限框
    // 既多余又会因为用户拒绝而误报「扫码不可用」。
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(ctx, "未授予相机权限，实时预览不可用；可用「拍照识别」", Toast.LENGTH_SHORT).show()
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
                                pageError = null
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                canGoBack = view.canGoBack()
                                loading = false
                                // 页面挂载是异步的：8 秒后仍没有任何可见文本 → 判定白屏并给兜底 UI。
                                // 8s 长于前端路由守卫的 1.5s 登录态等待，避免正常慢页面被误判。
                                view.postDelayed({
                                    try {
                                        view.evaluateJavascript(
                                            "(function(){var b=document.body;return b?String((b.innerText||'').trim().length):'0'})()"
                                        ) { v ->
                                            val n = v?.trim('"')?.toIntOrNull() ?: 0
                                            if (n == 0) markPageError("页面没有渲染出内容（脚本或资源加载失败）")
                                        }
                                    } catch (e: Exception) { /* WebView 已销毁 */ }
                                }, 8000)
                            }

                            /** 主文档加载失败（断网 / DNS / 证书）：给出可见错误，不再留一片白 */
                            override fun onReceivedError(
                                view: WebView, request: WebResourceRequest, error: WebResourceError
                            ) {
                                if (request.isForMainFrame) {
                                    markPageError("加载失败：${error.description}")
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
                            ) {
                                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                                    markPageError("服务器返回 ${errorResponse.statusCode}")
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            /** 只放行扫码页「实时预览」的摄像头请求，其余一律拒绝；默认拍照识别不走这里 */
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
            // 白屏兜底：加载失败 / 渲染为空时盖一层可见提示，可重试或强制继续
            if (pageError != null) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("页面没打开", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        pageError ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        pageError = null
                        webView.view?.reload()
                    }) { Text("重试") }
                    TextButton(onClick = { pageError = null }) { Text("仍然继续") }
                }
            }
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
    // 站内 HTML 文档一律接管，不能再只限 /m/：
    // EmbedScreen 里「数据看板 / 页脚 / 功能开关 / 外观 / 水印 / 登录页二维码」故意加载的是
    // **PC 路由**（/admin/stats 等，桌面页自带响应式断点）。只拦 /m/ 的话这些页面拿不到令牌，
    // 路由守卫会把用户弹回登录页——整块嵌入功能不可用。
    // 仍限定在本站 origin，外部页面一律不代理（避免把任意站点内容塞进 WebView）。
    val origin = base.trimEnd('/')
    if (target != origin && !target.startsWith("$origin/")) return null
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
                // 直接落 localStorage，而不是只挂 window 变量等前端来读：
                // 前端 main-mobile.js 当前**没有** __KB_NATIVE_TOKEN 的接收端，只挂变量等于没注入，
                // WebView 页面会停在登录页。写盘之后仍挂变量，前端将来补了接收端也照常工作。
                "<script>window.__KB_NATIVE_TOKEN=\"$safe\";" +
                    "try{localStorage.setItem('kb_token',window.__KB_NATIVE_TOKEN)}catch(e){}</script>"
            } else {
                // 已登出：连 localStorage 一起清，避免 WebView 里残留旧会话与用户缓存
                "<script>window.__KB_NATIVE_TOKEN=null;" +
                    "try{localStorage.removeItem('kb_token');localStorage.removeItem('kb_user_cache')}catch(e){}</script>"
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
