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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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

    /**
     * GET /badges → **JSONObject** `{ badges:[{id,name,desc,icon,color,tier,metric,threshold,current,earned,worn,progress}],
     * wornBadges:[], earned:Int, total:Int }`。
     * ⚠️ 不是数组：用 getArray 会直接抛异常（徽章页曾经永远「暂无徽章」）。`id` 是字符串（如 `first_post`）。
     */
    suspend fun badges(): JSONObject = withContext(Dispatchers.IO) { ApiClient.get("/api/badges") }

    /** POST /badges/wear { badgeId:String }：空串=全部卸下，已佩戴再传=卸下，未佩戴=佩戴（上限校验在后端） */
    suspend fun wearBadge(badgeId: String): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/badges/wear", JSONObject().put("badgeId", badgeId))
    }

    /** GET /points/log/paged → Page { content: [{type, delta, balance, reason, createdAt}], total, earned, spent } */
    suspend fun pointsLog(page: Int): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.get("/api/points/log/paged?page=$page&size=20")
    }

    /** GET /points/ranking → **JSONObject** `{ top:[{rank,username,displayName,avatar,points}], total:Int, me:{rank,username,points} }` */
    suspend fun ranking(): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.get("/api/points/ranking")
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

    /**
     * GET /admin/docs?page&size&keyword → Spring Page {content:[…]}
     * 注意：后端返回的是分页体（JSONObject），直接按数组解析必抛异常（旧实现内容管理页永远空）。
     */
    suspend fun adminDocs(page: Int = 0, size: Int = 50, keyword: String = ""): JSONArray =
        withContext(Dispatchers.IO) {
            val kw = if (keyword.isBlank()) "" else
                "&keyword=${java.net.URLEncoder.encode(keyword, "UTF-8")}"
            ApiClient.get("/api/admin/docs?page=$page&size=$size$kw").optJSONArray("content") ?: JSONArray()
        }

    /** POST /admin/docs/{id}/block|unblock → 封禁/解封 */
    suspend fun adminSetDocBlocked(id: Long, blocked: Boolean): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/admin/docs/$id/" + if (blocked) "block" else "unblock")
        Unit
    }

    /** DELETE /admin/docs/{id} → 移入回收站（可恢复） */
    suspend fun adminDeleteDoc(id: Long): Unit =
        withContext(Dispatchers.IO) { ApiClient.delete("/api/admin/docs/$id"); Unit }

    /** GET /admin/docs/recycle → 回收站文章（含已删除） */
    suspend fun adminRecycleDocs(): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/admin/docs/recycle")
    }

    /** POST /admin/docs/{id}/restore → 从回收站恢复 */
    suspend fun adminRestoreDoc(id: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/admin/docs/$id/restore")
        Unit
    }

    /** DELETE /admin/docs/{id}/purge → 彻底删除（不可恢复） */
    suspend fun adminPurgeDoc(id: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.delete("/api/admin/docs/$id/purge")
        Unit
    }

    /** GET /admin/stats → 看板聚合（docTotal/userTotal/trendLabels/topDocs/topUsers/activities…） */
    suspend fun adminStats(): JSONObject = withContext(Dispatchers.IO) { ApiClient.get("/api/admin/stats") }

    /** GET /admin/audit → Page<AuditLog> */
    suspend fun adminAudit(page: Int = 0, size: Int = 50): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.get("/api/admin/audit?page=$page&size=$size").optJSONArray("content") ?: JSONArray()
    }

    /** GET /admin/login-logs → Page<LoginLog> */
    suspend fun adminLoginLogs(page: Int = 0, size: Int = 50): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.get("/api/admin/login-logs?page=$page&size=$size").optJSONArray("content") ?: JSONArray()
    }

    /** GET /admin/reports?status= → {items:[…], pending:N}（后端不是标准 Page，键名是 items） */
    suspend fun adminReports(status: String? = null, page: Int = 0, size: Int = 50): JSONObject =
        withContext(Dispatchers.IO) {
            val q = if (status.isNullOrBlank()) "" else "&status=${status.uppercase()}"
            ApiClient.get("/api/admin/reports?page=$page&size=$size$q")
        }

    /**
     * PUT /admin/reports/{id} {status:HANDLED|REJECTED, note, action} → 处理举报。
     * action=NONE 仅记录；action=DELETE_CONTENT 会按目标类型处置（POST → 文章置 BLOCKED 下架）。
     */
    suspend fun adminHandleReport(
        id: Long,
        status: String,
        note: String,
        action: String = "NONE",
    ): Unit = withContext(Dispatchers.IO) {
        ApiClient.put(
            "/api/admin/reports/$id",
            JSONObject().put("status", status).put("note", note).put("action", action.uppercase()),
        )
        Unit
    }

    /** GET /admin/banned-words/libraries → 词库列表 [{id,name,description,enabled,count}] */
    suspend fun bannedLibraries(): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/admin/banned-words/libraries")
    }

    /** POST /admin/banned-words/libraries {name,description} */
    suspend fun createBannedLibrary(name: String, description: String): Unit = withContext(Dispatchers.IO) {
        ApiClient.post(
            "/api/admin/banned-words/libraries",
            JSONObject().put("name", name).put("description", description),
        )
        Unit
    }

    /** PUT /admin/banned-words/libraries/{id} {enabled} → 启用/停用词库 */
    suspend fun setBannedLibraryEnabled(id: Long, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        ApiClient.put("/api/admin/banned-words/libraries/$id", JSONObject().put("enabled", enabled))
        Unit
    }

    /** GET /admin/banned-words?libraryId&page&size → {content:[…],total} */
    suspend fun bannedWords(libraryId: Long? = null, page: Int = 0, size: Int = 100): JSONArray =
        withContext(Dispatchers.IO) {
            val q = if (libraryId == null) "" else "&libraryId=$libraryId"
            ApiClient.get("/api/admin/banned-words?page=$page&size=$size$q").optJSONArray("content") ?: JSONArray()
        }

    /**
     * POST /admin/banned-words {libraryId, words} → 批量加词。
     * ⚠️ 后端 `words` 收的是**换行分隔的字符串**（`(String) body.get("words")` 后按 \r\n 切分），
     * 传 JSON 数组会在后端强转失败，这里必须拼成多行文本。
     */
    suspend fun addBannedWords(libraryId: Long, words: List<String>): JSONObject = withContext(Dispatchers.IO) {
        val text = words.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        ApiClient.post("/api/admin/banned-words", JSONObject().put("libraryId", libraryId).put("words", text))
    }

    /** DELETE /admin/banned-words/{id} */
    suspend fun deleteBannedWord(id: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.delete("/api/admin/banned-words/$id")
        Unit
    }

    /** GET /categories → 分类树（公开接口，节点含 children/sortOrder） */
    suspend fun categories(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/categories") }

    /** POST /admin/categories {name,parentId} */
    suspend fun createCategory(name: String, parentId: Long?): Unit = withContext(Dispatchers.IO) {
        ApiClient.post(
            "/api/admin/categories",
            JSONObject().put("name", name).put("parentId", parentId ?: JSONObject.NULL),
        )
        Unit
    }

    /** DELETE /admin/categories/{id}（有子分类或仍被文章引用时后端拒绝） */
    suspend fun deleteCategory(id: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.delete("/api/admin/categories/$id")
        Unit
    }

    /** GET /tags → 标签（含文章计数） */
    suspend fun tags(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/tags") }

    /** POST /admin/tags {name} */
    suspend fun createTag(name: String): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/admin/tags", JSONObject().put("name", name))
        Unit
    }

    /** DELETE /admin/tags/{id} */
    suspend fun deleteTag(id: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.delete("/api/admin/tags/$id")
        Unit
    }

    /**
     * GET /docs → **Spring Page** `{content:[…]}`（不是数组；用 getArray 会抛异常，发现页曾经永远空）。
     * 这里取第一页并拍平成数组，调用方口径不变。
     */
    suspend fun articles(page: Int = 0, size: Int = 50): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.get("/api/docs?page=$page&size=$size").optJSONArray("content") ?: JSONArray()
    }

    // ---------- 我的文章（作者视角）----------

    /** GET /docs/mine → 我写的文章（含草稿 / 待审 / 已发布 / 已封禁） */
    suspend fun myDocs(): JSONArray = withContext(Dispatchers.IO) { ApiClient.getArray("/api/docs/mine") }

    /** POST /docs/{id}/submit → 草稿提交审核（普通用户不能自发布，统一转待审核） */
    suspend fun submitDocReview(id: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/docs/$id/submit")
        Unit
    }

    /** POST /docs/{id}/withdraw → 撤回审核（待审核 → 草稿，审核前可拉回） */
    suspend fun withdrawDocReview(id: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/docs/$id/withdraw")
        Unit
    }

    /** DELETE /docs/{id} → 作者删除自己的文章（与后台 admin 删除不是同一路径） */
    suspend fun deleteMyDoc(id: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.delete("/api/docs/$id")
        Unit
    }

    /** GET /docs/{id} → 文章详情（帖子页） */
    suspend fun articleDetail(id: Long): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.get("/api/docs/$id")
    }

    /** GET /docs/{id}/comments → 评论列表 */
    suspend fun articleComments(id: Long): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/docs/$id/comments")
    }

    /** GET /api/docs/{id}/related?limit= → 相关文档（同分类 + 标签重叠，公开接口，按相关度排序） */
    suspend fun relatedDocs(id: Long, limit: Int = 6): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/docs/$id/related?limit=$limit")
    }

    // ---------- 互动（点赞 / 收藏 / 订阅 / 评论点赞）----------
    // 只对已发布内容开放：后端对 PENDING/BLOCKED 返回 403「内容尚未发布」，文案直接透出。

    /** POST /api/likes/{id} → (是否已赞, 当前点赞数) */
    suspend fun toggleLike(id: Long): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        val o = ApiClient.post("/api/likes/$id")
        o.optBoolean("liked") to o.optInt("likes")
    }

    /** GET /api/likes/mine/ids → 我点过赞的文章 id 集合 */
    suspend fun myLikeIds(): Set<Long> = withContext(Dispatchers.IO) {
        val a = ApiClient.getArray("/api/likes/mine/ids")
        (0 until a.length()).map { a.optLong(it) }.filter { it > 0 }.toSet()
    }

    /** POST /api/favorites/{id} → 切换收藏，返回是否已收藏 */
    suspend fun toggleFavorite(id: Long): Boolean = withContext(Dispatchers.IO) {
        ApiClient.post("/api/favorites/$id").optBoolean("favorited")
    }

    /** GET /api/favorites/mine/ids → 我收藏的文章 id 集合（判断某篇是否已收藏，避免拉全量列表） */
    suspend fun myFavoriteIds(): Set<Long> = withContext(Dispatchers.IO) {
        val a = ApiClient.getArray("/api/favorites/mine/ids")
        (0 until a.length()).map { a.optLong(it) }.filter { it > 0 }.toSet()
    }

    /** GET /api/favorites/mine → 我收藏的文章（仅已发布，按收藏时间倒序；Document 数组） */
    suspend fun myFavoriteDocs(): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/favorites/mine")
    }

    /** GET /api/docs/{id}/subscribed → (是否已订阅, 订阅人数)；未登录也返回 false */
    suspend fun docSubscription(id: Long): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        val o = ApiClient.get("/api/docs/$id/subscribed")
        o.optBoolean("subscribed") to o.optLong("count")
    }

    /** POST|DELETE /api/docs/{id}/subscribe → 切换订阅，返回最新 (状态, 人数) */
    suspend fun setDocSubscribed(id: Long, on: Boolean): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        val o = if (on) ApiClient.post("/api/docs/$id/subscribe") else ApiClient.delete("/api/docs/$id/subscribe")
        o.optBoolean("subscribed") to o.optLong("count")
    }

    /** POST /api/comments/{id}/like → 评论点赞 (是否已赞, 当前赞数) */
    suspend fun toggleCommentLike(commentId: Long): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        val o = ApiClient.post("/api/comments/$commentId/like")
        o.optBoolean("liked") to o.optInt("likes")
    }

    /** DELETE /api/comments/{id} → 删除评论（普通用户只能删自己的，管理员可删任意） */
    suspend fun deleteComment(id: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.delete("/api/comments/$id")
        Unit
    }

    /**
     * POST /api/reports {targetType,targetId,reason,detail} → 举报内容。
     * targetType：DOC / POST / COMMENT / DM / USER / FEEDBACK；reason 必填（≤120 字），detail 选填（≤2000 字）。
     * 后端限同一人对同一对象 10 分钟内只提交一次。
     */
    suspend fun reportContent(
        targetType: String,
        targetId: Long,
        reason: String,
        detail: String = "",
    ): Unit = withContext(Dispatchers.IO) {
        ApiClient.post(
            "/api/reports",
            JSONObject()
                .put("targetType", targetType.uppercase())
                .put("targetId", targetId)
                .put("reason", reason)
                .put("detail", detail),
        )
        Unit
    }

    /** GET /api/comments/my-likes → 我点过赞的评论 id 集合 */
    suspend fun myCommentLikeIds(): Set<Long> = withContext(Dispatchers.IO) {
        val a = ApiClient.getArray("/api/comments/my-likes")
        (0 until a.length()).map { a.optLong(it) }.filter { it > 0 }.toSet()
    }

    /** POST /docs/{id}/comments { content } */
    suspend fun postComment(id: Long, content: String, parentId: Long? = null): Unit =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("content", content)
            if (parentId != null) body.put("parentId", parentId)
            ApiClient.post("/api/docs/$id/comments", body)
        }

    /** GET /users/{id}/card → 用户名片 */
    suspend fun userCard(id: Long): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.get("/api/users/$id/card")
    }

    /** GET /users/{id}/articles?page → **Spring Page** `{content:[…]}`（不是数组，用 getArray 会抛异常） */
    suspend fun userArticles(id: Long, page: Int = 0, size: Int = 20): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.get("/api/users/$id/articles?page=$page&size=$size").optJSONArray("content") ?: JSONArray()
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

    // ---------- 私信（DM）----------
    // 会话列表按 peer 聚合；消息按 id 游标分页（beforeId 向上翻）。发送返回带 quota 的消息体，
    // 未互关时有条数限制（remaining=-1 表示不限）。

    /** GET /dm/conversations?archived= → [{peerId,name,avatar,lastMessage,lastFromMe,lastAt,unread,allowDm}] */
    suspend fun dmConversations(archived: Boolean = false): JSONArray = withContext(Dispatchers.IO) {
        ApiClient.getArray("/api/dm/conversations?archived=$archived")
    }

    /** GET /dm/messages?peerId&beforeId&size → 升序消息 [{id,fromId,kind,content,createdAt,read,reply*}] */
    suspend fun dmHistory(peerId: Long, beforeId: Long? = null, size: Int = 30): JSONArray =
        withContext(Dispatchers.IO) {
            val q = if (beforeId == null) "" else "&beforeId=$beforeId"
            ApiClient.getArray("/api/dm/messages?peerId=$peerId&size=$size$q")
        }

    /** POST /dm/messages {toId,content,kind} → 新消息（含 quota） */
    suspend fun dmSend(toId: Long, content: String, kind: String = "TEXT"): JSONObject =
        withContext(Dispatchers.IO) {
            ApiClient.post("/api/dm/messages", JSONObject().put("toId", toId).put("content", content).put("kind", kind))
        }

    /** POST /dm/read {peerId} → 该会话标记已读，返回处理条数 */
    suspend fun dmMarkRead(peerId: Long): Int = withContext(Dispatchers.IO) {
        ApiClient.post("/api/dm/read", JSONObject().put("peerId", peerId)).optInt("read", 0)
    }

    /** GET /dm/unread → 私信总未读 */
    suspend fun dmUnread(): Int = withContext(Dispatchers.IO) {
        ApiClient.get("/api/dm/unread").optInt("unread", 0)
    }

    /** POST /dm/conversations/{peerId}/archive {archived} → 归档/取消归档（参数名是 peerId，不是 toId） */
    suspend fun dmArchive(peerId: Long, archived: Boolean): Unit = withContext(Dispatchers.IO) {
        ApiClient.post("/api/dm/conversations/$peerId/archive", JSONObject().put("archived", archived))
        Unit
    }

    /** DELETE /dm/conversations/{peerId} → 本用户视角软删会话 */
    suspend fun dmDeleteConv(peerId: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.delete("/api/dm/conversations/$peerId")
        Unit
    }

    /** DELETE /dm/messages/{id} → 撤回自己 2 分钟内的消息 */
    suspend fun dmRecall(id: Long): Unit = withContext(Dispatchers.IO) {
        ApiClient.delete("/api/dm/messages/$id")
        Unit
    }

    /** GET /dm/quota?peerId= → {mutual,replied,sent,remaining,limited,peerAllowDm} */
    suspend fun dmQuota(peerId: Long): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.get("/api/dm/quota?peerId=$peerId")
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
      coroutineScope {
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
        // 页面离开/取消协程时，读循环可能正阻塞在 readUtf8Line 上（ensureActive 插不进去），
        // 用旁路协程在取消时立刻 call.cancel()，把阻塞读打断
        val canceller = launch {
            try { awaitCancellation() } finally { call.cancel() }
        }
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
                    // 先把事件类型取走并重置：空 data / 解析失败也必须清，否则下一条消息会被误路由
                    val evt = event
                    event = "message"
                    if (data.isEmpty()) return
                    val payload = try { JSONObject(data.toString()) } catch (e: Exception) { null }
                    data.setLength(0)
                    if (payload == null) return
                    when (evt) {
                        "done" -> result = payload
                        "error" -> throw ApiError(0, payload.optString("error", "AI 生成中断"))
                        else -> {
                            payload.optString("delta").takeIf { it.isNotEmpty() }?.let(onDelta)
                            payload.optString("reasoning").takeIf { it.isNotEmpty() }?.let(onReasoning)
                        }
                    }
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
        } catch (e: java.io.IOException) {
            currentCoroutineContext().ensureActive()   // 已取消就别把 IOException 当失败抛给界面
            throw e
        } finally {
            canceller.cancel()
            call.cancel()   // 取消/异常时释放连接，避免后台继续消耗
        }
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

    /**
     * POST /api/files/mine → 上传到「我的文件」（登记归属，才会出现在 `GET /files/mine`）。
     * ⚠️ 编辑器/扫描走的 `POST /api/files` **不登记 owner**，用它上传的文件不会出现在文件列表里。
     */
    suspend fun uploadMyFile(file: java.io.File, mime: String): JSONObject = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
            .build()
        ApiClient.postBody("/api/files/mine", body)
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
