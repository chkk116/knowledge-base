/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import com.kb.kbplatform.shell.TokenStore
import com.kb.kbplatform.shell.data.ApiClient
import java.io.ByteArrayInputStream
import java.io.File

/**
 * WebView 承载共用层：令牌桥接拦截 + ChromeClient（文件选择/拍照/JS 弹窗）。
 * 被 WebScreen（单页承载）与 ShellScreen（全屏套壳 UI）共用。
 */
object WebSupport {

    fun isShellHtml(base: String, request: WebResourceRequest): Boolean {
        val target = request.url.toString()
        if (!request.method.equals("GET", true)) return false
        // 限定 /m 与 /m/：startsWith("$base/m") 会误伤 /metrics、/mobile（审查 r14）
        val mobilePrefix = "$base/m/"
        if (!target.startsWith(mobilePrefix) && target != "$base/m") return false
        val accept = request.requestHeaders?.get("Accept") ?: return false
        return accept.contains("text/html")
    }

    /** 拦截 /m/ HTML 文档 → 有令牌时注入 window.__KB_NATIVE_TOKEN（与 main-mobile.js bootstrap 配对）。
     *  ⚠️ 无令牌时不注入任何东西：登录流程在 Web 内完成（JsBridge 回传令牌），
     *  若注入 null 会在 Web 登录成功跳转时把令牌清掉，造成登录死循环。 */
    fun interceptHtml(base: String, request: WebResourceRequest): WebResourceResponse? {
        if (!isShellHtml(base, request)) return null
        val target = request.url.toString()
        val accept = request.requestHeaders?.get("Accept") ?: return null
        val token = TokenStore.token.value ?: return null   // 未登录：放行，让 Web 自己出登录页
        return try {
            val resp = ApiClient.http.newCall(
                okhttp3.Request.Builder()
                    .url(target)
                    .header("User-Agent", ApiClient.USER_AGENT)
                    .header("Accept", accept)
                    .build()
            ).execute()
            resp.use { r ->
                if (!r.isSuccessful) return null
                var html = r.body?.string() ?: return null
                // 除引号/反斜杠外还要挡 </script>：否则令牌里出现该串会提前闭合注入的 script 块（审查 r14）
                val safe = token.replace("\\", "\\\\").replace("\"", "\\\"").replace("</", "<\\/")
                val js = "<script>window.__KB_NATIVE_TOKEN=\"$safe\";</script>"
                html = Regex("<head([^>]*)>", RegexOption.IGNORE_CASE)
                    .replaceFirst(html, "<head$1>$js")
                WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * JS 桥：Web 登录/登出后把令牌变化回传原生（TokenStore 保管，供原生分享上传等使用）。
     * 安全：只接受当前 WebView 已加载的同源基址 URL 的调用（ShellScreen 注册时校验）。
     */
    class TokenBridge(private val allow: () -> Boolean, private val onChange: (String?) -> Unit) {
        @android.webkit.JavascriptInterface
        fun onTokenChanged(token: String) {
            if (!allow()) return
            onChange(token.ifBlank { null })
        }
    }

    class Hooks(
        val onPageStarted: (String) -> Unit = {},
        val onPageFinished: (String) -> Unit = {},
        val onProgress: (Int) -> Unit = {},
        val onFileChooser: (android.webkit.ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit = { cb, _ -> cb.onReceiveValue(null) },
        /** 请求拦截：本地资产承载（ShellScreen）或远程 HTML 令牌注入（WebScreen）由调用方决定 */
        val onInterceptRequest: (WebResourceRequest) -> WebResourceResponse? = { null }
    )

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(
        activity: Activity,
        base: String,
        hooks: Hooks,
        tokenBridge: TokenBridge? = null,
        /** 视为「站内」的来源：ShellScreen 要额外放行 https://localhost（本地资产承载域） */
        inAppOrigins: List<String> = listOf(base)
    ): WebView =
        WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            if (tokenBridge != null) {
                addJavascriptInterface(tokenBridge, "KbNativeBridge")
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    hooks.onInterceptRequest(request)

                /**
                 * 站内留在 WebView，站外交给系统浏览器。
                 * 不加这段时 WebView 能被导航到任意站点：文档里的外链会把用户带进一个没有地址栏的
                 * 「假页面」，既没法返回也分辨不出域名（钓鱼面）。ShellScreen 之前漏了这段。
                 */
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val scheme = uri.scheme?.lowercase() ?: return true
                    if (scheme != "http" && scheme != "https") {
                        openExternal(view, uri)          // mailto / tel / 微信 scheme 等
                        return true
                    }
                    return if (inAppOrigins.any { sameOrigin(it, uri) }) {
                        false                            // 站内：WebView 自己加载
                    } else {
                        openExternal(view, uri)
                        true
                    }
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = hooks.onPageStarted(url)
                override fun onPageFinished(view: WebView, url: String) = hooks.onPageFinished(url)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) = hooks.onProgress(newProgress)

                override fun onShowFileChooser(
                    view: WebView,
                    callback: android.webkit.ValueCallback<Array<Uri>>,
                    params: WebChromeClient.FileChooserParams
                ): Boolean {
                    hooks.onFileChooser(callback, params)
                    return true
                }

                override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    android.app.AlertDialog.Builder(activity)
                        .setMessage(message)
                        .setPositiveButton("确定") { _, _ -> result.confirm() }
                        .setOnCancelListener { result.cancel() }
                        .show()
                    return true
                }

                override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    android.app.AlertDialog.Builder(activity)
                        .setMessage(message)
                        .setPositiveButton("确定") { _, _ -> result.confirm() }
                        .setNegativeButton("取消") { _, _ -> result.cancel() }
                        .setOnCancelListener { result.cancel() }
                        .show()
                    return true
                }
            }
        }

    /** 拍照/选文件的 launcher intent：capture 走相机 + FileProvider，否则系统选择器 */
    fun fileChooserIntent(activity: Activity, params: WebChromeClient.FileChooserParams): Pair<Intent, Uri?> {
        return if (params.isCaptureEnabled) {
            val dir = File(activity.cacheDir, "web_capture").apply { mkdirs() }
            val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            Pair(
                Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                uri
            )
        } else {
            Pair(params.createIntent(), null)
        }
    }

    fun isChooserNotFound(e: Exception): Boolean = e is ActivityNotFoundException
    /** 同源判定：协议 + 主机 + 端口三者一致（端口缺省按协议补 80/443） */
    private fun sameOrigin(base: String, target: Uri): Boolean {
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
        } catch (e: ActivityNotFoundException) {
            /* 无浏览器 / 无对应 scheme 处理者：忽略 */
        } catch (e: Exception) {
            /* 同上 */
        }
    }
}
