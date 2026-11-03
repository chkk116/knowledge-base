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

    /**
     * AI 问答等长耗时请求专用：非流式完整回答（含 RAG 检索 + LLM 生成）经常远超 15s，
     * 复用默认 client 会在等待回答时被读超时打断（SocketTimeoutException）。
     * 读超时 180s 对齐后端 LLM 调用上限；连接超时沿用 10s（建连慢就该报错）。
     */
    val httpLong: OkHttpClient = http.newBuilder()
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun newBuilder(method: String, path: String, body: JSONObject?): Request.Builder {
        val url = AppConfig.effectiveBaseUrl + path
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
    fun getArray(path: String): org.json.JSONArray {
        val resp = http.newCall(newBuilder("GET", path, null).build()).execute()
        resp.use { r ->
            val text = r.body?.string() ?: ""
            if (r.code == 401) onUnauthorized()
            if (!r.isSuccessful) throw ApiError(r.code, parseError(text, r.code))
            return org.json.JSONArray(text)
        }
    }

    /** POST JSON 对象体，解析 JSON 对象响应 */
    fun post(path: String, body: JSONObject = JSONObject()): JSONObject =
        call(newBuilder("POST", path, body))

    /** PUT JSON 对象体 */
    fun put(path: String, body: JSONObject): JSONObject {
        val url = AppConfig.effectiveBaseUrl + path
        val b = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        TokenStore.token.value?.let { b.header("Authorization", "Bearer $it") }
        b.put(body.toString().toRequestBody(JSON_TYPE))
        return call(b)
    }

    /** DELETE */
    fun delete(path: String): JSONObject {
        val url = AppConfig.effectiveBaseUrl + path
        val b = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        TokenStore.token.value?.let { b.header("Authorization", "Bearer $it") }
        b.delete()
        return call(b)
    }

    /** POST 自定义请求体（multipart 等），解析 JSON 对象响应 */
    fun postBody(path: String, body: okhttp3.RequestBody): JSONObject {
        val url = AppConfig.effectiveBaseUrl + path
        val b = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        TokenStore.token.value?.let { b.header("Authorization", "Bearer $it") }
        b.post(body)
        return call(b)
    }

    private fun call(req: Request.Builder): JSONObject = call(http, req)

    private fun call(client: OkHttpClient, req: Request.Builder): JSONObject {
        val resp = client.newCall(req.build()).execute()
        resp.use { r ->
            val text = r.body?.string() ?: ""
            if (r.code == 401) onUnauthorized()
            if (!r.isSuccessful) throw ApiError(r.code, parseError(text, r.code))
            return JSONObject(text)
        }
    }

    /** POST JSON 对象体（长读超时版，AI 问答用） */
    fun postLong(path: String, body: JSONObject): JSONObject =
        call(httpLong, newBuilder("POST", path, body))

    /** 后端统一错误体 { error: "中文提示" }（GlobalExceptionHandler） */
    private fun parseError(text: String, code: Int): String =
        try { JSONObject(text).optString("error", "请求失败($code)") } catch (e: Exception) { "请求失败($code)" }

    private fun onUnauthorized() {
        TokenStore.set(null)
    }
}
