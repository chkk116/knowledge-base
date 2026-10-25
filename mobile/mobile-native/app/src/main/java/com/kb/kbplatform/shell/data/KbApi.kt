/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 知识库平台 REST 接口封装（竖切版本用到的子集）。
 * 协议以 backend/web 控制器为准：登录 / 滑动验证码 / 首页概览 / 公告 / 文档空间 / 通知。
 */
object KbApi {

    // ---------- 认证 ----------

    data class Session(
        val token: String,
        val id: Long,
        val username: String,
        val displayName: String,
        val role: String,
        val points: Long
    )

    data class Captcha(
        val token: String,
        val bg: Bitmap,
        val piece: Bitmap,
        val pieceY: Int
    )

    /** GET /auth/captcha → base64 PNG 背景图 + 拼块 + 槽位纵坐标（correctX 只在服务端） */
    suspend fun fetchCaptcha(): Captcha = withContext(Dispatchers.IO) {
        val j = ApiClient.get("/api/auth/captcha")
        Captcha(
            token = j.getString("token"),
            bg = decodeDataPng(j.getString("bg")),
            piece = decodeDataPng(j.getString("piece")),
            pieceY = j.optInt("pieceY", 0)
        )
    }

    private fun decodeDataPng(dataUri: String): Bitmap {
        val b64 = dataUri.substringAfter("base64,", dataUri)
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw ApiError(0, "验证码图片解码失败")
    }

    /**
     * POST /auth/captcha/slide。
     * x：按 320px 服务端基准换算后的拼块左偏移（容差 ±6px）；
     * trajectory：[{x: 拼块显示坐标, t: 毫秒时间戳}]，要求 ≥3 点且首尾 ≥120ms。
     * 服务端返回 200 + {ok:false,error} 表示没拼对（业务失败，不是 HTTP 错误）。
     */
    suspend fun slideCaptcha(token: String, x: Int, trajectory: JSONArray): Unit =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("token", token)
                .put("x", x)
                .put("trajectory", trajectory)
            val resp = ApiClient.post("/api/auth/captcha/slide", body)
            if (!resp.optBoolean("ok", false)) {
                throw ApiError(0, resp.optString("error", "滑动验证未通过，请重新拖动拼图"))
            }
        }

    /** POST /auth/login { username, password, captchaToken } */
    suspend fun login(username: String, password: String, captchaToken: String): Session =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("username", username)
                .put("password", password)
                .put("captchaToken", captchaToken)
            val j = ApiClient.post("/api/auth/login", body)
            Session(
                token = j.getString("token"),
                id = j.optLong("id", 0),
                username = j.optString("username"),
                displayName = j.optString("displayName", j.optString("username")),
                role = j.optString("role"),
                points = j.optLong("points", 0)
            )
        }

    /** GET /auth/me → 当前用户资料 */
    suspend fun me(): JSONObject = withContext(Dispatchers.IO) { ApiClient.get("/api/auth/me") }

    // ---------- 首页 ----------

    /** GET /docs/overview → { totalDocs, weekNew, members, pendingReview? } */
    suspend fun overview(): JSONObject = withContext(Dispatchers.IO) { ApiClient.get("/api/docs/overview") }

    /** GET /announcements → 公告列表 */
    suspend fun announcements(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/announcements") }

    // ---------- 文档 ----------

    /** GET /doc-shares/my → 我的空间列表 */
    suspend fun myShares(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/doc-shares/my") }

    /** GET /doc-shares/{id}/docs → 空间内文档列表 */
    suspend fun shareDocs(shareId: Long): JSONArray =
        withContext(Dispatchers.IO) { ApiClient.getArray("/api/doc-shares/$shareId/docs") }

    // ---------- 通知 ----------

    /** GET /notifications → 我的通知（倒序） */
    suspend fun notifications(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/notifications") }

    /** POST /notifications/{id}/read → 标记已读 */
    suspend fun markNotificationRead(id: Long): Unit =
        withContext(Dispatchers.IO) { ApiClient.post("/api/notifications/$id/read") }

    /** GET /notifications/unread → { count } */
    suspend fun unreadCount(): Int = withContext(Dispatchers.IO) {
        ApiClient.get("/api/notifications/unread").optInt("count", 0)
    }
}
