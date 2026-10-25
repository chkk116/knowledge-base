/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.data

import com.kb.kbplatform.shell.AppConfig
import com.kb.kbplatform.shell.TokenStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 业务/HTTP 错误统一走这里；message 即可直接展示给用户（后端 error 字段已是中文） */
class ApiError(val httpCode: Int, message: String) : Exception(message)

/**
 * 轻量 REST 客户端：OkHttp + org.json，零序列化框架依赖。
 *
 * - 自动附带 Bearer 令牌；401 统一清令牌（全局退回登录页），与 Web 端 request.js 对齐
 * - UA 带 Mozilla：后端对管理员账号的非浏览器客户端登录有实时告警（AuthController
 *   判定 UA 不含 Mozilla 即 SECURITY_ALERT），原生 App 不应触发该告警
 * - 基址从 AppConfig 读取（用户改完设置立即生效），不缓存构造期快照
 */
object ApiClient {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android) KbNativeShell/0.1"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun newBuilder(method: String, path: String, body: JSONObject?): Request.Builder {
        val base = AppConfig.effectiveBaseUrl
        // manifest 放行了明文 HTTP（内网部署需要），这里再收一层：HTTP 只许内网地址，
        // 公网必须 HTTPS，避免 token 在内网之外被中间人抓走（审查 r13）
        if (base.startsWith("http://") && !isPrivateHost(base))
            throw ApiError(0, "出于安全考虑，HTTP 仅允许内网地址；公网请使用 HTTPS")
        val url = base + path
        val b = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        TokenStore.token.value?.let { b.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> b.get()
            "POST" -> b.post((body?.toString() ?: "{}").toRequestBody(JSON_TYPE))
            else -> throw IllegalArgumentException("不支持的方法: $method")
        }
        return b
    }

    /** GET 并解析 JSON 对象响应 */
    fun get(path: String): JSONObject = call(newBuilder("GET", path, null))

    /** GET JSON 数组响应 */
    fun getArray(path: String): org.json.JSONArray = org.json.JSONArray(exec(newBuilder("GET", path, null)))

    /** POST JSON 对象体，解析 JSON 对象响应 */
    fun post(path: String, body: JSONObject = JSONObject()): JSONObject =
        call(newBuilder("POST", path, body))

    private fun call(req: Request.Builder): JSONObject = JSONObject(exec(req))

    /** 唯一的请求执行管线：读 body → 401 处理 → 非 2xx 解析错误（原来 call/getArray 各写一遍） */
    private fun exec(req: Request.Builder): String {
        val resp = http.newCall(req.build()).execute()
        resp.use { r ->
            val text = r.body?.string() ?: ""
            if (r.code == 401) onUnauthorized()
            if (!r.isSuccessful) throw ApiError(r.code, parseError(text, r.code))
            return text
        }
    }

    /** 内网判定：localhost / 127. / 10. / 192.168. / 172.16-31. */
    private fun isPrivateHost(base: String): Boolean {
        val h = try { java.net.URI(base).host ?: "" } catch (e: Exception) { "" }
        if (h.isEmpty()) return false
        if (h == "localhost" || h.startsWith("127.")) return true
        if (h.startsWith("10.") || h.startsWith("192.168.")) return true
        val parts = h.split(".")
        if (parts.size >= 2 && parts[0] == "172") {
            val second = parts[1].toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    /** 后端统一错误体 { error: "中文提示" }（GlobalExceptionHandler） */
    private fun parseError(text: String, code: Int): String =
        try { JSONObject(text).optString("error", "请求失败($code)") } catch (e: Exception) { "请求失败($code)" }

    private fun onUnauthorized() {
        TokenStore.set(null)
    }
}
