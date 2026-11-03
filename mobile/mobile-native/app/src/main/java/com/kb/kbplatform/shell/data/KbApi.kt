/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.shell.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.kb.kbplatform.shell.AppConfig
import com.kb.kbplatform.shell.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
        val permissions: String,
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
                permissions = j.optString("permissions"),
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

    /** GET /doc-shares/docs/all → 全部文档（跨空间，含 content） */
    suspend fun allShareDocs(): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/doc-shares/docs/all")
    }

    /** GET /doc-shares/inbox/count → { count } */
    suspend fun inboxCount(): Int = withContext(Dispatchers.IO) {
        ApiClient.get("/api/doc-shares/inbox/count").optInt("count", 0)
    }

    /** GET /doc-shares/my-quota → { used, quota }（个人存储） */
    suspend fun myQuota(): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.get("/api/doc-shares/my-quota")
    }

    /** POST /clip/preview { url } → { url, title, site, excerpt, content } */
    suspend fun clipPreview(url: String): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.post("/api/clip/preview", JSONObject().put("url", url))
    }

    // ---------- 批量页（回收站/订阅/摘录/徽章/积分/排行/待整理箱） ----------

    /** GET /docs/recycle → 回收站文档 */
    suspend fun recycleList(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/docs/recycle") }

    suspend fun recycleRestore(id: Long): Unit =
        withContext(Dispatchers.IO) { ApiClient.post("/api/docs/$id/restore") }

    suspend fun recyclePurge(id: Long): Unit =
        withContext(Dispatchers.IO) { ApiClient.delete("/api/docs/$id/purge") }

    /** GET /docs/subscriptions → 我的文档订阅 */
    suspend fun subscriptions(): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/docs/subscriptions")
    }

    suspend fun unsubscribe(id: Long): Unit =
        withContext(Dispatchers.IO) { ApiClient.delete("/api/docs/$id/subscribe") }

    /** GET /doc-shares/annotations/mine?limit → 我的摘录（跨文档） */
    suspend fun myAnnotations(): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/doc-shares/annotations/mine?limit=100")
    }

    suspend fun deleteAnnotation(shareId: Long, docId: Long, annoId: Long): Unit =
        withContext(Dispatchers.IO) {
            ApiClient.delete("/api/doc-shares/$shareId/docs/$docId/annotations/$annoId")
        }

    /** GET /badges → 徽章列表；POST /badges/wear { badgeId } */
    suspend fun badges(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/badges") }

    suspend fun wearBadge(badgeId: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/badges/wear", JSONObject().put("badgeId", if (badgeId > 0) badgeId else ""))
    }

    /** GET /points/log/paged → Page { content: [{type, points, reason, createdAt}] } */
    suspend fun pointsLog(page: Int): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.get("/api/points/log/paged?page=$page&size=20")
    }

    /** GET /points/ranking → 排行列表 */
    suspend fun ranking(): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/points/ranking")
    }

    /** GET /doc-shares/inbox/list → 待整理箱 */
    suspend fun inboxList(): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/doc-shares/inbox/list")
    }

    /** POST /doc-shares/inbox/{docId}/archive { targetShareId, title } */
    suspend fun inboxArchive(docId: Long, targetShareId: Long, title: String): Unit =
        withContext(Dispatchers.IO) {
            ApiClient.post(
                "/api/doc-shares/inbox/$docId/archive",
                JSONObject().put("targetShareId", targetShareId).put("title", title)
            )
        }

    /** DELETE /doc-shares/{sid}/docs/{did} */
    suspend fun deleteShareDoc(shareId: Long, docId: Long): Unit =
        withContext(Dispatchers.IO) { ApiClient.delete("/api/doc-shares/$shareId/docs/$docId") }

    // ---------- Wave B（课程/错题本/徽章/文件/版本/角色/内容管理/发现/统计/用户/帖子/编辑） ----------

    /** PUT /doc-shares/{sid}/docs/{did} { title, content }（原生编辑保存，后写赢） */
    suspend fun updateShareDoc(shareId: Long, docId: Long, title: String, content: String): JSONObject =
        withContext(Dispatchers.IO) {
            ApiClient.put(
                "/api/doc-shares/$shareId/docs/$docId",
                JSONObject().put("title", title).put("content", content)
            )
        }

    /** GET /courses → 课程列表 */
    suspend fun courses(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/courses") }

    /** GET /courses/{id} → 课程详情（含 chapters） */
    suspend fun courseDetail(id: Long): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.get("/api/courses/$id")
    }

    /** POST /courses/{cid}/chapters/{chid}/finish → 标记章节完成 */
    suspend fun finishChapter(courseId: Long, chapterId: Long): Unit =
        withContext(Dispatchers.IO) { ApiClient.post("/api/courses/$courseId/chapters/$chapterId/finish") }

    /** GET /exams/practice/wrong → 错题本（含答案与解析） */
    suspend fun wrongBook(): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/exams/practice/wrong")
    }

    /** GET /files/mine → 我的文件（文件中心） */
    suspend fun myFiles(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/files/mine") }

    /** GET /doc-shares/{sid}/docs/{did}/versions → 版本历史（倒序） */
    suspend fun docVersions(shareId: Long, docId: Long): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/doc-shares/$shareId/docs/$docId/versions")
    }

    /** POST .../versions/{vid}/restore */
    suspend fun restoreDocVersion(shareId: Long, docId: Long, vid: Long): Unit =
        withContext(Dispatchers.IO) {
            ApiClient.post("/api/doc-shares/$shareId/docs/$docId/versions/$vid/restore")
        }

    /** GET /admin/roles → 角色列表 */
    suspend fun adminRoles(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/admin/roles") }

    /** GET /admin/docs → 内容管理列表 */
    suspend fun adminDocs(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/admin/docs") }

    /** DELETE /admin/docs/{id} */
    suspend fun adminDeleteDoc(id: Long): Unit =
        withContext(Dispatchers.IO) { ApiClient.delete("/api/admin/docs/$id") }

    /** GET /docs → 文章列表（发现页） */
    suspend fun articles(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/docs") }

    /** GET /docs/{id} → 文章详情（帖子页） */
    suspend fun articleDetail(id: Long): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.get("/api/docs/$id")
    }

    /** GET /docs/{id}/comments → 评论列表 */
    suspend fun articleComments(id: Long): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/docs/$id/comments")
    }

    /** POST /docs/{id}/comments { content } */
    suspend fun postComment(id: Long, content: String): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/docs/$id/comments", JSONObject().put("content", content))
    }

    /** GET /users/{id}/card → 用户名片 */
    suspend fun userCard(id: Long): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.get("/api/users/$id/card")
    }

    /** GET /users/{id}//articles?page → 用户文章 */
    suspend fun userArticles(id: Long): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/users/$id/articles?page=0&size=20")
    }

    /** POST/DELETE /users/{id}/follow */
    suspend fun followUser(id: Long, follow: Boolean): Unit = withContext(Dispatchers.IO) {
        if (follow) ApiClient.post("/api/users/$id/follow")
        else ApiClient.delete("/api/users/$id/follow")
    }

    /** GET /doc-shares/storage → { used, quota } */
    suspend fun storage(): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.get("/api/doc-shares/storage")
    }

    /** GET /doc-shares/{id}/docs → 空间内文档列表 */
    suspend fun shareDocs(shareId: Long): JSONArray =
        withContext(Dispatchers.IO) { ApiClient.getArray("/api/doc-shares/$shareId/docs") }

    // ---------- 通知 ----------

    /** GET /notifications → 我的通知（倒序） */
    suspend fun notifications(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/notifications") }

    /** POST /notifications/{id}/read → 标记已读 */
    suspend fun markNotificationRead(id: Long): Unit =
        withContext(Dispatchers.IO) { ApiClient.post("/api/notifications/$id/read") }

    /** POST /notifications/read-all → 全部标已读 */
    suspend fun markAllNotificationsRead(): Unit =
        withContext(Dispatchers.IO) { ApiClient.post("/api/notifications/read-all") }

    /** DELETE /notifications/{id} → 删除单条（只影响自己那条） */
    suspend fun deleteNotification(id: Long): Unit =
        withContext(Dispatchers.IO) {
            ApiClient.delete("/api/notifications/$id")
            Unit
        }

    /** GET /notifications/unread → { count } */
    suspend fun unreadCount(): Int = withContext(Dispatchers.IO) {
        ApiClient.get("/api/notifications/unread").optInt("count", 0)
    }

    // ---------- AI 问答 ----------

    /** GET /ai/models → [{ id, provider, name, model, isDefault }]（仅启用的） */
    suspend fun aiModels(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/ai/models") }

    /**
     * POST /ai/ask/stream（SSE 流式问答，与 Web 端 aiAskStream 同协议）。
     * 事件：data {"delta"} / {"reasoning"} 逐段；event:done → 与 /ai/ask 同结构结果；
     * event:error → {error}。手动按行解析而非 okhttp-sse（POST 请求手动解析最可控）。
     * 走长读超时通道：段与段之间间隔超 15s 的情况真实存在（长思考模型）。
     */
    suspend fun aiAskStream(
        question: String,
        conversationId: Long? = null,
        mode: String = "kb",
        modelId: Long? = null,
        userModelId: Long? = null,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("question", question).put("mode", mode)
        conversationId?.let { body.put("conversationId", it) }
        modelId?.let { body.put("modelId", it) }
        userModelId?.let { body.put("userModelId", it) }
        val builder = okhttp3.Request.Builder()
            .url(AppConfig.effectiveBaseUrl + "/api/ai/ask/stream")
            .header("User-Agent", ApiClient.USER_AGENT)
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        TokenStore.token.value?.let { builder.header("Authorization", "Bearer $it") }

        val call = ApiClient.httpLong.newCall(builder.build())
        try {
            call.execute().use { r ->
                if (r.code == 401) { TokenStore.set(null); throw ApiError(401, "登录已过期，请重新登录") }
                if (!r.isSuccessful) throw ApiError(r.code, "请求失败(${r.code})")
                // 逐行阻塞读：事件到达即回调，不等整个响应结束（否则流式=伪流式，整段回答还要驻留内存）
                val source = r.body?.source() ?: throw ApiError(0, "空响应")

                var event = "message"
                val data = StringBuilder()
                var result: JSONObject? = null
                fun flush() {
                    if (data.isEmpty()) return
                    val payload = try { JSONObject(data.toString()) } catch (e: Exception) { null }
                    data.setLength(0)
                    if (payload == null) return
                    when (event) {
                        "done" -> result = payload
                        "error" -> throw ApiError(0, payload.optString("error", "AI 生成中断"))
                        else -> {
                            payload.optString("delta").takeIf { it.isNotEmpty() }?.let(onDelta)
                            payload.optString("reasoning").takeIf { it.isNotEmpty() }?.let(onReasoning)
                        }
                    }
                    event = "message"
                }
                while (true) {
                    currentCoroutineContext().ensureActive()   // 页面离开/取消时尽快中止，不再白跑满超时
                    val rawLine = source.readUtf8Line() ?: break
                    val line = rawLine.trimEnd('\r')
                    when {
                        line.isEmpty() -> flush()   // 空行 = 事件边界（SSE 规范）
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')   // SSE 规范：多行 data 以 \n 连接
                            data.append(line.substring(5).trimStart())
                        }
                    }
                }
                flush()
                // done 是协议约定事件；缺失说明流被截断（或协议变更），不能当成功返回空对象
                result ?: throw ApiError(0, "回答流中断（缺少 done 事件）")
            }
        } finally {
            call.cancel()   // 取消/异常时释放连接，避免后台继续消耗
        }
    }

    /**
     * POST /ai/ask → { conversationId, title, answer, sources?, modelName?, latencyMs? }（一次性完整回答，非流式）。
     * modelId：系统模型；userModelId：用户私有模型（/api/ai/models 里 scope=user 的条目）。
     * 两者都空 → 后端按候选链选默认模型。走长读超时通道：完整回答常超 15s。
     */
    suspend fun aiAsk(
        question: String,
        conversationId: Long? = null,
        modelId: Long? = null,
        mode: String = "kb",
        userModelId: Long? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("question", question).put("mode", mode)
        conversationId?.let { body.put("conversationId", it) }
        modelId?.let { body.put("modelId", it) }
        userModelId?.let { body.put("userModelId", it) }
        ApiClient.postLong("/api/ai/ask", body)
    }

    // ---------- 复习卡片 ----------

    /** GET /flashcards/due → 到期卡片（实体数组：id/front/back/dueAt/...） */
    suspend fun dueCards(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/flashcards/due") }

    /** POST /flashcards/{id}/review { grade: again|hard|good|easy } → 更新后的卡片 */
    suspend fun reviewCard(id: Long, grade: String): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.post("/api/flashcards/$id/review", JSONObject().put("grade", grade))
    }

    // ---------- 考试练习 ----------

    /** POST /exams/practice/session → { sessionId, paper, questions: [{id, type, content, options:[{label,content}]}] } */
    suspend fun startPractice(count: Int = 10): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.post("/api/exams/practice/session", JSONObject().put("count", count))
    }

    /** POST /exams/practice/check → { correct, manual, correctAnswer:[{label,content}], analysis } */
    suspend fun practiceCheck(sessionId: Long, questionId: Long, answer: List<String>): JSONObject =
        withContext(Dispatchers.IO) {
            ApiClient.post(
                "/api/exams/practice/check",
                JSONObject()
                    .put("sessionId", sessionId)
                    .put("questionId", questionId)
                    .put("answer", org.json.JSONArray(answer))
            )
        }

    // ---------- 分享导入 ----------

    /** POST /files（multipart）→ { url } 或 { data: { url } } */
    suspend fun uploadFile(file: java.io.File, mime: String): JSONObject = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
            .build()
        ApiClient.postBody("/api/files", body)
    }

    /** POST /doc-shares/{id}/docs[?inbox=true] { title, content } */
    suspend fun createShareDoc(
        shareId: Long,
        title: String,
        content: String,
        inbox: Boolean = false
    ): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.post(
            "/api/doc-shares/$shareId/docs" + if (inbox) "?inbox=true" else "",
            JSONObject().put("title", title).put("content", content)
        )
    }

    /** POST /doc-shares { name, type: PERSONAL } → 个人空间（存在即取，不存在则建） */
    suspend fun personalShare(): JSONObject = withContext(Dispatchers.IO) {
        val mine = ApiClient.getArray("/api/doc-shares/my")
        for (i in 0 until mine.length()) {
            val s = mine.optJSONObject(i) ?: continue
            if (s.optString("type") == "PERSONAL") return@withContext s
        }
        ApiClient.post(
            "/api/doc-shares",
            JSONObject().put("name", "我的文档").put("type", "PERSONAL")
        )
    }

    /** GET /doc-shares/{id}/docs/{docId} → 文档实体（含 content HTML，正文离线缓存用） */
    suspend fun getShareDoc(shareId: Long, docId: Long): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.get("/api/doc-shares/$shareId/docs/$docId")
    }

    // ---------- 考试中心 ----------

    /** GET /exams → 已发布试卷列表（title/questionCount/durationMin/passScore/totalScore/status） */
    suspend fun exams(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/exams") }

    /** GET /exams/{id} → { paper, questions: [{id, type, content, options:[{label,content}], score}] } */
    suspend fun examDetail(id: Long): JSONObject = withContext(Dispatchers.IO) { ApiClient.get("/api/exams/$id") }

    /** POST /exams/{id}/start → ExamRecord { id(=recordId), deadlineAt, ... } */
    suspend fun startExam(id: Long): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.post("/api/exams/$id/start")
    }

    /** POST /exams/{paperId}/submit { recordId, items: [{questionId, answer:[label…]}] } → ExamRecord { score, passed } */
    suspend fun submitExam(paperId: Long, recordId: Long, items: List<Pair<Long, List<String>>>): JSONObject =
        withContext(Dispatchers.IO) {
            val arr = JSONArray()
            items.forEach { (qid, ans) ->
                arr.put(JSONObject().put("questionId", qid).put("answer", JSONArray(ans)))
            }
            ApiClient.post(
                "/api/exams/$paperId/submit",
                JSONObject().put("recordId", recordId).put("items", arr)
            )
        }

    /** GET /exams/mine → 我的考试记录（score/passed/submittedAt） */
    suspend fun myExams(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/exams/mine") }

    // ---------- AI 会话 ----------

    /** GET /ai/conversations → [{id, title, updatedAt}] */
    suspend fun aiConversations(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/ai/conversations") }

    /** POST /ai/conversations {title} → {id, ...} */
    suspend fun aiCreateConversation(title: String): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.post("/api/ai/conversations", JSONObject().put("title", title))
    }

    /** GET /ai/conversations/{id}/messages → [{id, role, content, sources, mode, createdAt}] */
    suspend fun aiMessages(convId: Long): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/ai/conversations/$convId/messages")
    }

    /** DELETE /ai/conversations/{id} → {ok} */
    suspend fun aiDeleteConversation(convId: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.delete("/api/ai/conversations/$convId")
    }

    // ---------- 后台管理 ----------

    /** GET /admin/users → Spring Page {content:[…], totalElements, …} */
    suspend fun adminUsers(page: Int, size: Int, keyword: String): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.get("/api/admin/users?page=$page&size=$size" + if (keyword.isNotBlank()) "&keyword=${java.net.URLEncoder.encode(keyword, "UTF-8")}" else "")
    }

    /** POST /admin/users/{id}/toggle {enabled} */
    suspend fun adminToggleUser(id: Long, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/admin/users/$id/toggle", JSONObject().put("enabled", enabled))
    }

    /** PUT /admin/users/{id} {role} */
    suspend fun adminSetRole(id: Long, role: String): Unit = withContext(Dispatchers.IO) {
        ApiClient.put("/api/admin/users/$id", JSONObject().put("role", role))
    }

    /** GET /admin/ai/models → 模型全字段列表 */
    suspend fun adminAiModels(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/admin/ai/models") }

    /** POST /admin/ai/models/{id}/toggle {enabled} */
    suspend fun adminToggleAiModel(id: Long, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/admin/ai/models/$id/toggle", JSONObject().put("enabled", enabled))
    }

    /** POST /admin/ai/models/{id}/default */
    suspend fun adminDefaultAiModel(id: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/admin/ai/models/$id/default")
    }

    /** POST /admin/ai/models/{id}/test → 连接测试结果 */
    suspend fun adminTestAiModel(id: Long): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.post("/api/admin/ai/models/$id/test")
    }

    /** GET /admin/ai/models/platforms → 可选平台（key/label/默认 baseUrl/内置模型列表） */
    suspend fun adminAiPlatforms(): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/admin/ai/models/platforms")
    }

    /**
     * POST /admin/ai/models → 新建或更新（带 id 就是更新）。
     * 字段对齐 Web 端表单：provider/name/model/baseUrl/apiKey/contextWindow/vision/enabled。
     * ⚠️ apiKey 由后端 AES-GCM 加密落库，传明文即可，**不要**在本地做任何编码。
     */
    suspend fun adminSaveAiModel(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.post("/api/admin/ai/models", body)
    }

    /** DELETE /admin/ai/models/{id} */
    suspend fun adminDeleteAiModel(id: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.delete("/api/admin/ai/models/$id")
        Unit
    }

    /** GET /admin/docs/pending → 待审文章列表 */
    suspend fun adminPendingDocs(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/admin/docs/pending") }

    /** POST /admin/docs/{id}/approve | /reject {reason} */
    suspend fun adminApproveDoc(id: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/admin/docs/$id/approve")
    }

    suspend fun adminRejectDoc(id: Long, reason: String): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/admin/docs/$id/reject", JSONObject().put("reason", reason))
    }

    /** GET /admin/announcements → Spring Page {content:[…]} */
    // ---------- 站点设置（通用键值，CORE_MAINTAIN 权限） ----------
    //
    // 后端 `/api/admin/settings/{key}` 返回 { value: "<json 字符串>", key }，
    // value 为空串表示还没配置过（用默认值）。

    suspend fun adminGetSetting(key: String): String = withContext(Dispatchers.IO) {
        ApiClient.get("/api/admin/settings/$key").optString("value")
    }

    suspend fun adminPutSetting(key: String, json: String): Unit = withContext(Dispatchers.IO) {
        ApiClient.put("/api/admin/settings/$key", JSONObject().put("value", json))
        Unit
    }

    suspend fun adminAnnouncements(): JSONArray = withContext(Dispatchers.IO) {
        val page = ApiClient.get("/api/admin/announcements")
        page.optJSONArray("content") ?: JSONArray()
    }

    /** POST /admin/announcements {title, content} */
    suspend fun adminCreateAnnouncement(title: String, content: String): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/admin/announcements", JSONObject().put("title", title).put("content", content))
    }

    /** DELETE /admin/announcements/{id} */
    suspend fun adminDeleteAnnouncement(id: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.delete("/api/admin/announcements/$id")
        Unit
    }

    /** PUT /admin/announcements/{id} {title, content} → 编辑公告 */
    suspend fun adminUpdateAnnouncement(id: Long, title: String, content: String): Unit =
        withContext(Dispatchers.IO) {
            ApiClient.put("/api/admin/announcements/$id", JSONObject().put("title", title).put("content", content))
        }

    /** POST /admin/users → 新建用户（username/displayName/password/role/enabled） */
    suspend fun adminCreateUser(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.post("/api/admin/users", body)
    }

    /** POST /admin/users/{id}/reset-password → 重置为随机初始密码（返回体里带新密码） */
    suspend fun adminResetPassword(id: Long): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.post("/api/admin/users/$id/reset-password")
    }

    /** POST /admin/announcements/{id}/enable {enabled} / pin */
    suspend fun adminToggleAnnouncement(id: Long, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/admin/announcements/$id/enable", JSONObject().put("enabled", enabled))
    }

    suspend fun adminPinAnnouncement(id: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/admin/announcements/$id/pin")
    }

    // ---------- 应用内更新（免登录接口） ----------

    /** GET /public/app-version → { versionCode, versionName, url, notes, force }；versionCode=0 表示未启用 */
    suspend fun appVersion(): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.get("/api/public/app-version?channel=native")
    }
}
