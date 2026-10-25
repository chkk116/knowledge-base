/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.web

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kb.kbplatform.shell.AppConfig
import com.kb.kbplatform.shell.TokenStore
import com.kb.kbplatform.shell.data.ApiClient
import com.kb.kbplatform.shell.ui.common.TopBar
import java.io.ByteArrayInputStream

/**
 * WebView 承载页：原生壳里运行移动 Web 页面（/m/ 路径族）。
 *
 * 令牌桥接（关键机制，与 frontend/src/main-mobile.js 的 bootstrap 配对）：
 *  1. 拦截 /m/ 路径族的 HTML 文档请求，由 OkHttp 取回响应体
 *  2. 在 <head> 后注入 `window.__KB_NATIVE_TOKEN = "<token>"`
 *  3. main-mobile.js 入口最先执行：发现该变量即写入 localStorage.kb_token
 *     —— 在 Vue 应用任何路由守卫读到令牌之前完成，无竞态
 *  4. 未登录（原生端已登出）时注入 null，Web 侧同步清掉旧令牌
 *
 * 静态资源（js/css/img）与接口 XHR 不拦截，走 WebView 原生网络栈。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(path: String, title: String, onBack: () -> Unit) {
    val base = AppConfig.effectiveBaseUrl
    var progress by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }

    // 不按 path 重建：holder 变了但 WebView 实例不变，返回键/前进状态会指向旧 holder（审查 r13）
    val webView = remember { MutableWebViewHolder() }

    BackHandler(enabled = canGoBack) {
        webView.view?.goBack()
    }

    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
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
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? = interceptHtml(base, request)

                            /**
                             * 站内跳转留在 WebView，站外一律交给系统浏览器（审查 r14）。
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
                                // 非 http(s)：mailto / tel / 微信 scheme 等，交给系统处理
                                if (scheme != "http" && scheme != "https") {
                                    openExternal(view, uri)
                                    return true
                                }
                                return if (isSameOrigin(base, uri)) {
                                    false                       // 站内：WebView 自己加载（含令牌注入）
                                } else {
                                    openExternal(view, uri)
                                    true                        // 站外：系统浏览器，WebView 不导航
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
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        tag = base + path
                        loadUrl(base + path)
                    }.also { webView.view = it }
                },
                // AndroidView 没有 update 时 factory 只跑一次，path 变了也不会重新加载（审查 r13 High）
                update = { wv ->
                    val want = base + path
                    if (wv.tag != want) {
                        wv.tag = want
                        wv.loadUrl(want)
                    }
                },
                onRelease = { it.destroy() }
            )
        }
    }
}

/** remember(path) 需要一个稳定持有者，避免重组期间 WebView 被意外重建 */
/** 文档 <head> 标签（每次请求重新编译太浪费，提到顶层） */
private val HEAD_TAG_RE = Regex("<head([^>]*)>", RegexOption.IGNORE_CASE)

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

private class MutableWebViewHolder {
    var view: WebView? = null
}

private fun interceptHtml(base: String, request: WebResourceRequest): WebResourceResponse? {
    val url = request.url
    val target = url.toString()
    if (!request.method.equals("GET", true)) return null
    if (!target.startsWith("$base/m") && target != "$base/m/") return null
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
            val safeToken = token?.replace("\\", "\\\\")?.replace("\"", "\\\"")
            val js = if (safeToken != null) {
                "<script>window.__KB_NATIVE_TOKEN=\"$safeToken\";</script>"
            } else {
                "<script>window.__KB_NATIVE_TOKEN=null;</script>"
            }
            // 手工拼接而不是 replaceFirst 替换串：替换串里的 $ 和 \ 会被当引用解析（审查 r13 High）
            HEAD_TAG_RE.find(html)?.let { m ->
                val at = m.range.last + 1
                html = html.substring(0, at) + js + html.substring(at)
            }
            // 保留原响应头（CSP / X-Content-Type-Options / Set-Cookie…），只剔除与 body 冲突的
            val headers = LinkedHashMap<String, String>()
            for (name in r.headers.names()) {
                when (name.lowercase()) {
                    "content-length", "content-encoding", "transfer-encoding", "content-type" -> {}
                    else -> r.header(name)?.let { headers[name] = it }
                }
            }
            val mime = (r.header("Content-Type") ?: "text/html").substringBefore(';').ifBlank { "text/html" }
            WebResourceResponse(
                mime, "utf-8", r.code, r.message.ifBlank { "OK" }, headers,
                ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
            )
        }
    } catch (e: Exception) {
        null
    }
}
