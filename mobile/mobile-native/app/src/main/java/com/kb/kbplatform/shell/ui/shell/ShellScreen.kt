/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.ui.shell

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.kb.kbplatform.shell.AppConfig
import com.kb.kbplatform.shell.TokenStore
import com.kb.kbplatform.shell.data.ShareInbox
import com.kb.kbplatform.shell.ui.web.WebSupport
import java.io.ByteArrayInputStream

/**
 * 套壳 UI 承载层（终态架构）：前端构建产物打进 APK（assets/web/），
 * 经 WebViewAssetLoader 以 **https://localhost**（与 Capacitor 套壳同域，后端 CORS 白名单同款）本地承载——
 * UI = 打包时的最新前端，断网不白屏；API/uploads 按前端 runtimeBase 的原生模式走云端绝对地址。
 *
 * HTML 响应注入：__KB_NATIVE='1'（runtimeBase 原生模式开关）、__KB_NATIVE_ORIGIN（后端地址，单一来源=
 * gradle 的 KB_BASE_URL）、__KB_NATIVE_TOKEN（有令牌才注入；登录/登出由 Web 完成后经 JsBridge 回传）。
 *
 * 兜底：assets/web 未同步时自动回落远程加载（base + /m/）。
 * 返回键：WebView 可退则退，否则退后台（回切走应用锁）。
 */
private const val LOADER_ORIGIN = "https://localhost"

@Composable
fun ShellScreen(onShareImport: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val base = AppConfig.effectiveBaseUrl
    var progress by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }

    val webView = remember { MutableWebViewHolder() }
    var currentUrl by remember { mutableStateOf("") }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var captureUri by remember { mutableStateOf<Uri?>(null) }

    val chooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileCallback
        fileCallback = null
        if (cb == null) return@rememberLauncherForActivityResult
        val uris = when {
            result.resultCode != Activity.RESULT_OK -> null
            captureUri != null -> arrayOf(captureUri!!)
            else -> {
                val list = mutableListOf<Uri>()
                result.data?.data?.let { list.add(it) }
                result.data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) list.add(clip.getItemAt(i).uri)
                }
                if (list.isEmpty()) null else list.toTypedArray()
            }
        }
        captureUri = null
        cb.onReceiveValue(uris)
    }

    // Web 登录/登出 → 令牌回传原生保管（原生分享导入等能力依赖）
    val tokenBridge = remember {
        WebSupport.TokenBridge(
            allow = { currentUrl.startsWith(LOADER_ORIGIN) || currentUrl.startsWith(base) },
            onChange = { t -> TokenStore.set(t) }
        )
    }

    val sharePending by ShareInbox.pending.collectAsState()
    LaunchedEffect(sharePending) {
        if (sharePending.isNotEmpty()) onShareImport()
    }

    BackHandler(enabled = true) {
        val wv = webView.view
        if (wv != null && wv.canGoBack()) wv.goBack()
        else activity?.moveTaskToBack(true)
    }

    Box(Modifier.fillMaxSize()) {
        if (loading && progress < 100) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxSize()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                val activityCtx = c as Activity
                val localAssets = hasLocalWebAssets(c)
                val loader = if (localAssets) assetLoader(c, base) else null
                WebSupport.createWebView(activityCtx, base, WebSupport.Hooks(
                    onPageStarted = {
                        loading = true
                        currentUrl = it
                    },
                    onPageFinished = {
                        loading = false
                        canGoBack = webView.view?.canGoBack() ?: false
                    },
                    onProgress = { progress = it },
                    onFileChooser = { cb, params ->
                        fileCallback?.onReceiveValue(null)
                        fileCallback = cb
                        try {
                            val (intent, cap) = WebSupport.fileChooserIntent(activityCtx, params)
                            captureUri = cap
                            chooserLauncher.launch(intent)
                        } catch (e: Exception) {
                            if (WebSupport.isChooserNotFound(e)) {
                                android.widget.Toast.makeText(
                                    activityCtx, "设备上没有可用的相机或文件管理器", android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            fileCallback = null
                            cb.onReceiveValue(null)
                        }
                    },
                    onInterceptRequest = { request ->
                        loader?.shouldInterceptRequest(request.url)
                            ?: WebSupport.interceptHtml(base, request)
                    }
                ), tokenBridge,
                    // 本地资产域（https://localhost）与后端域都算站内，其余外链交系统浏览器
                    inAppOrigins = listOf(LOADER_ORIGIN, base)
                ).apply {
                    loadUrl(if (localAssets) "$LOADER_ORIGIN/m/" else "$base/m/")
                }.also { webView.view = it }
            },
            onRelease = { it.destroy() }
        )
    }
}

fun hasLocalWebAssets(ctx: Context): Boolean = try {
    ctx.assets.open("web/mobile.html").use { true }
} catch (e: Exception) {
    false
}

/** 本地资源解析：精确文件 → SPA 回退（/m* → mobile.html，其余 → index.html）+ HTML 注入 */
private fun assetLoader(ctx: Context, base: String): WebViewAssetLoader =
    WebViewAssetLoader.Builder()
        .setDomain("localhost")
        .addPathHandler("/", KbAssetHandler(ctx, base))
        .build()

private class KbAssetHandler(private val ctx: Context, private val base: String) :
    WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val clean = path.trimStart('/').takeIf { it.isNotEmpty() } ?: "mobile.html"
        // 路径穿越防护：拒绝任何 .. 段（URL 编码的 ../ 由 WebView 归一化，这里兜底）
        if (clean.split('/').any { it == ".." }) return null
        // 精确命中
        openAsset("web/$clean")?.let { pair ->
            return respond(clean, pair.first, pair.second)
        }
        // SPA 回退：仅对无扩展名路径（路由段），静态资源缺失一律 404
        val last = clean.substringAfterLast('/')
        if (!last.contains('.')) {
            val fallback = if (clean == "m" || clean.startsWith("m/")) "mobile.html" else "index.html"
            openAsset("web/$fallback")?.let { pair ->
                return respond(fallback, pair.first, pair.second)
            }
        }
        return null
    }

    private fun openAsset(path: String): Pair<ByteArray, Boolean>? = try {
        val bytes = ctx.assets.open(path).use { it.readBytes() }
        Pair(bytes, path.endsWith(".html"))
    } catch (e: Exception) {
        null
    }

    private fun respond(name: String, bytes: ByteArray, isHtml: Boolean): WebResourceResponse {
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', ""))
            ?: if (isHtml) "text/html" else "application/octet-stream"
        if (!isHtml) {
            return WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
        }
        var html = String(bytes, Charsets.UTF_8)
        val token = TokenStore.token.value
        val js = buildString {
            append("<script>")
            append("window.__KB_NATIVE='1';")
            append("window.__KB_NATIVE_ORIGIN=\"$base\";")
            if (token != null) append("window.__KB_NATIVE_TOKEN=\"$token\";")
            append("</script>")
        }
        html = Regex("<head([^>]*)>", RegexOption.IGNORE_CASE)
            .replaceFirst(html, "<head$1>$js")
        return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
    }
}

private class MutableWebViewHolder {
    var view: WebView? = null
}
