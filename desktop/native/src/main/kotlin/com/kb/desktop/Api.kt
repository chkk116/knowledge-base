package com.kb.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/** 一次会话：登录成功后持有 token（仅内存，不落盘）。 */
data class Session(val server: String, val token: String, val username: String)

/** 滑块验证码素材（bg/piece 是 PNG 字节）。 */
data class CaptchaImg(val token: String, val pieceY: Int, val bg: ByteArray, val piece: ByteArray)

/** API 异常：message 直接展示给用户。 */
class ApiError(message: String) : Exception(message)

/** 文章列表项（GET /api/docs 列表字段）。 */
data class DocSummary(
    val id: Long,
    val title: String,
    val ownerName: String,
    val updatedAt: String,
    val views: Int,
    val category: String?,
    val snippet: String?,
)

/** 文章分页（Spring Page 的 content/totalElements）。 */
data class DocPage(val items: List<DocSummary>, val total: Long)

/** 文章详情（GET /api/docs/{id}，content 为服务端净化过的 HTML）。 */
data class DocDetail(
    val id: Long,
    val title: String,
    val content: String,
    val ownerName: String?,
    val ownerId: Long?,
    val category: String?,
    val tags: String?,
    val status: String?,
    val updatedAt: String?,
    val createdAt: String?,
    val views: Int,
    val likes: Int,
)

/** 文档层级面包屑项（GET /api/docs/{id}/breadcrumb，根→…→自身）。 */
data class CrumbItem(val id: Long, val title: String)

/** 评论（GET /api/docs/{id}/comments；content 为 HTML，含表情图片）。 */
data class CommentItem(
    val id: Long,
    val parentId: Long?,
    val userId: Long?,
    val username: String,
    val displayName: String?,
    val content: String,
    val likes: Int,
    val createdAt: String,
)

/** 服务端版本通道信息（GET /api/public/app-version?channel=desktop）。 */
data class AppVersionInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String,
    val checksum: String,
    val force: Boolean,
)

/** 我的文件项（GET /api/files/mine）。 */
data class FileItem(
    val id: Long,
    val name: String,
    val url: String,
    val size: Long?,
    val contentType: String?,
    val createdAt: String,
)

/** AI 可选模型（GET /api/ai/models）。scope=system 时请求发 modelId，scope=user 时发 userModelId。 */
data class AiModelOption(
    val id: Long,
    val name: String,
    val provider: String,
    val scope: String,
    val isDefault: Boolean,
)

/** AI 会话列表项（GET /api/ai/conversations）。 */
data class AiConversationItem(val id: Long, val title: String, val updatedAt: String)

/** 引用来源（RAG 检索命中，id = 文章 id）。 */
data class AiSource(val id: Long, val title: String, val snippet: String)

/** AI 历史消息（GET /api/ai/conversations/{id}/messages）。role 为 user/ai。 */
data class AiMsg(
    val id: Long,
    val role: String,
    val content: String,
    val mode: String,
    val sources: List<AiSource>,
)

/** 流式回答的 done 事件载荷（一次问答的收尾信息）。 */
data class AiAskResult(
    val conversationId: Long,
    val title: String,
    val answer: String,
    val qaId: Long?,
    val sources: List<AiSource>,
    val modelName: String?,
    val reasoning: String?,
)

/** Agent 工具用的轻量文档项（检索/我的文档）。 */
data class AgentDoc(val id: Long, val title: String, val snippet: String)

/** Agent 工具用的共享空间项（流程图要落进共享文档的画布）。 */
data class AgentSpace(val id: Long, val name: String, val type: String)

/** 作者小卡片（GET /api/users/{id}/card，公开字段，不含登录名）。 */
data class AuthorCard(
    val id: Long,
    val displayName: String,
    val bio: String,
    val codingAge: Int,
    val original: Long,
    val likes: Long,
    val fans: Long,
    val points: Long,
    val displayBadge: String?,
    val isFollowing: Boolean,
)

/** 共享空间里的一篇文档。列表接口不带 content，详情接口带 HTML 快照（协同实时正文在 yjs_state，客户端只读快照）。 */
data class SharedDocItem(
    val id: Long,
    val title: String,
    val ownerName: String,
    val updatedAt: String,
    val tags: String?,
    val contentType: String?,
    val myPerm: String?,
    val size: Long,
    val content: String?,
    // 跨空间列表（/doc-shares/docs/all）才有值；按空间取列表时后端也会带，缺省 0
    val shareId: Long = 0,
)

/** 课程（GET /api/courses；详情接口另带 chapters）。content/summary 均为净化后的 HTML。 */
data class CourseItem(
    val id: Long,
    val title: String,
    val summary: String,
    val category: String?,
    val ownerName: String?,
    val tags: String?,
    val examPaperId: Long?,
)

/** 课程章节（content 是后端净化过的 HTML，交给 DocHtml 渲染）。 */
data class ChapterItem(
    val id: Long,
    val title: String,
    val content: String,
    val articleId: Long?,
)

/** GET /api/courses/{id} → {course, chapters} */
data class CourseDetail(val course: CourseItem, val chapters: List<ChapterItem>)

/** 章节完成状态（GET /api/courses/{id}/progress）。 */
data class ChapterProgress(val chapterId: Long, val title: String, val finished: Boolean)

/** 试卷（GET /api/exams；questionCount 由后端 @Transient 回填）。 */
data class ExamPaperItem(
    val id: Long,
    val title: String,
    val description: String,
    val questionCount: Int,
    val totalScore: Int,
    val passScore: Int,
    val durationMin: Int,
    val status: String,
    val practiceEnabled: Boolean,
)

/** 我的考试记录（GET /api/exams/mine）。 */
data class ExamRecordItem(
    val id: Long,
    val paperId: Long,
    val paperTitle: String,
    val score: Int,
    val passed: Boolean,
    val submittedAt: String,
    val pendingCount: Int,
    val questionCount: Int,
)

/** 协同正文（GET /api/doc-shares/{sid}/docs/{docId}/yjs）：base64 的 Yjs 状态，客户端本地解析渲染。 */
data class ShareYjs(
    val state: String?,
    val yjsRev: Long,
    val updatedAt: String,
    val tooLarge: Boolean,
)

/** 管理：分类树节点（GET /api/categories，children 递归） */
data class CatItem(
    val id: Long,
    val name: String,
    val parentId: Long?,
    val sortOrder: Int,
    val children: List<CatItem>,
)

/** 管理：标签（带文章计数，GET /api/tags） */
data class TagItem(val id: Long, val name: String, val count: Long)

/** 管理：违禁词库 */
data class BannedLibItem(
    val id: Long,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val builtin: Boolean,
)

/** 管理：违禁词条 */
data class BannedWordItem(val id: Long, val word: String, val libraryId: Long?)

/** 管理：孤儿附件（正文/头像未引用的上传文件） */
data class OrphanFileItem(val url: String, val size: Long?)

/** 管理：待阅卷记录（一场考试一条） */
data class GradingPendingItem(
    val recordId: Long,
    val user: String,
    val paperTitle: String,
    val score: Int,
    val pendingCount: Int,
    val submittedAt: String,
)

/** 管理：阅卷题目（主观题 pending=true 才需要打分） */
data class GradingQuestion(
    val questionId: Long,
    val type: String,
    val content: String,
    val userAnswer: String,
    val pending: Boolean,
    val score: Int,
    val maxScore: Int,
    val comment: String,
)

/** 管理：一场考试的阅卷详情 */
data class GradingDetail(val recordId: Long, val items: List<GradingQuestion>)

/** 管理：举报/反馈 */
data class ReportItem(
    val id: Long,
    val targetType: String,
    val targetId: Long?,
    val reason: String,
    val detail: String,
    val status: String,
    val createdAt: String,
)

/** 管理：待审核文章 / 待审核数量卡片用 */
data class AdminDocItem(
    val id: Long,
    val title: String,
    val ownerName: String,
    val category: String?,
    val status: String,
    val updatedAt: String,
    val createdAt: String,
    val views: Int,
)

/** 管理：用户列表项（不含密码，公开字段） */
data class AdminUserItem(
    val id: Long,
    val username: String,
    val displayName: String,
    val role: String,
    val enabled: Boolean,
    val points: Long,
    val createdAt: String,
)

/** 管理：部门（后端返回扁平列表，前端按 parentId 组树） */
data class DeptItem(
    val id: Long,
    val name: String,
    val code: String?,
    val parentId: Long?,
    val sortOrder: Int,
)

/** 管理：公告（含 enabled，管理端才会用） */
data class AdminAnnouncement(
    val id: Long,
    val title: String,
    val content: String,
    val pinned: Boolean,
    val enabled: Boolean,
    val createdAt: String,
)

/** 管理：操作审计 */
data class AuditItem(
    val id: Long,
    val actorName: String,
    val action: String,
    val targetType: String,
    val targetName: String,
    val detail: String,
    val ip: String,
    val createdAt: String,
)

/** 管理：登录日志 */
data class LoginLogItem(
    val id: Long,
    val username: String,
    val ip: String,
    val success: Boolean,
    val reason: String,
    val createdAt: String,
)

/** 我订阅的文章（GET /api/docs/subscriptions；deleted=true 表示文档已被删除，标题由后端给占位）。 */
data class SubscriptionItem(
    val docId: Long,
    val title: String,
    val category: String?,
    val deleted: Boolean,
    val subscribedAt: String,
)

/** 首页轻量概览（GET /api/docs/overview）：已发布文档数 / 近 7 天新增 / 在册成员；管理员才带待审核数。 */
data class HomeOverview(
    val totalDocs: Long = 0,
    val weekNew: Long = 0,
    val members: Long = 0,
    val pendingReview: Long? = null,
)

/** 公告（GET /api/announcements，只回已启用的，置顶在前）。content 是纯文本/Markdown。 */
data class AnnouncementItem(
    val id: Long,
    val title: String,
    val content: String,
    val pinned: Boolean,
    val createdAt: String,
)

/** HTTP + JSON 访问层。登录三步 + 文章/文件只读接口。 */
class ApiClient(private val base: String) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private fun uri(path: String): URI = URI.create(base.trimEnd('/') + path)

    private fun send(req: HttpRequest): HttpResponse<String> = try {
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() >= 400) {
            val msg = parse(resp.body())?.str("error")
            throw ApiError(msg ?: "服务器返回 ${resp.statusCode()}")
        }
        resp
    } catch (e: ApiError) {
        throw e
    } catch (e: Exception) {
        throw ApiError("无法连接服务器：${e.message ?: "网络错误"}")
    }

    private fun post(path: String, body: String): String =
        send(HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()).body()

    private fun get(path: String): String =
        send(HttpRequest.newBuilder(uri(path))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()).body()

    private fun getAuth(path: String, token: String): String = try {
        val resp = http.send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer $token")
            .timeout(Duration.ofSeconds(20))
            .GET().build(), HttpResponse.BodyHandlers.ofString())
        when {
            resp.statusCode() == 401 -> throw ApiError("登录已过期，请重新登录")
            resp.statusCode() >= 400 -> {
                val msg = parse(resp.body())?.str("error")
                throw ApiError(msg ?: "服务器返回 ${resp.statusCode()}")
            }
            else -> resp.body()
        }
    } catch (e: ApiError) {
        throw e
    } catch (e: Exception) {
        throw ApiError("无法连接服务器：${e.message ?: "网络错误"}")
    }

    private fun parse(text: String?): JsonObject? =
        text?.let { t ->
            runCatching { Json.parseToJsonElement(t).jsonObject }.getOrNull()
        }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull

    private fun jsonStr(v: String): String = buildString {
        append('"')
        for (ch in v) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
        append('"')
    }

    /** bg/piece 兼容 data URL、站内路径、绝对 URL 三种来源。 */
    private fun resolveBytes(src: String): ByteArray = when {
        src.startsWith("data:") -> {
            val b64 = src.substringAfter("base64,", "")
            if (b64.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(b64)
        }
        src.startsWith("http://") || src.startsWith("https://") -> bytes(src)
        src.startsWith("/") || src.startsWith("uploads/") || src.startsWith("emojis/") ->
            bytes(uri("/" + src.trimStart('/')).toString())
        else -> ByteArray(0)
    }

    private fun bytes(url: String): ByteArray = try {
        http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()).body()
    } catch (e: Exception) {
        ByteArray(0)
    }

    /** GET /api/auth/captcha → {token, pieceY, bg, piece} */
    suspend fun captcha(): CaptchaImg = withContext(Dispatchers.IO) {
        val o = parse(get("/api/auth/captcha")) ?: throw ApiError("验证码响应异常")
        val token = o.str("token").orEmpty()
        if (token.isEmpty()) throw ApiError(o.str("error") ?: "验证码加载失败")
        CaptchaImg(token, o.int("pieceY") ?: 0, resolveBytes(o.str("bg") ?: ""), resolveBytes(o.str("piece") ?: ""))
    }

    /** POST /api/auth/captcha/slide {token,x,trajectory} → {ok} */
    suspend fun verifySlide(token: String, x: Int, trajectory: List<Pair<Int, Long>>) = withContext(Dispatchers.IO) {
        val traj = trajectory.joinToString(",") { """{"x":${it.first},"t":${it.second}}""" }
        val o = parse(post("/api/auth/captcha/slide", """{"token":${jsonStr(token)},"x":$x,"trajectory":[$traj]}"""))
            ?: throw ApiError("验证响应异常")
        if (o.bool("ok") != true) throw ApiError(o.str("error") ?: "验证失败，请重新拖动")
    }

    /** POST /api/auth/login {username,password,captchaToken} → {token} */
    suspend fun login(username: String, password: String, captchaToken: String): String = withContext(Dispatchers.IO) {
        val o = parse(post("/api/auth/login", """{"username":${jsonStr(username)},"password":${jsonStr(password)},"captchaToken":${jsonStr(captchaToken)}}"""))
            ?: throw ApiError("登录响应异常")
        o.str("token")?.takeIf { it.isNotBlank() }
            ?: throw ApiError(o.str("error") ?: "登录失败，请检查账号或密码")
    }

    /** GET /api/docs?page&size&sort → Spring Page {content[], totalElements} */
    suspend fun docs(token: String, page: Int = 0, size: Int = 30, sort: String = "latest"): DocPage = withContext(Dispatchers.IO) {
        val o = parse(getAuth("/api/docs?page=$page&size=$size&sort=$sort", token))
            ?: throw ApiError("文章列表响应异常")
        val arr = (o["content"] as? JsonArray) ?: throw ApiError("文章列表响应异常")
        val items = arr.mapNotNull { e ->
            val j = e as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            DocSummary(
                id = id,
                title = j.str("title").orEmpty(),
                ownerName = j.str("ownerName").orEmpty(),
                updatedAt = j.str("updatedAt").orEmpty(),
                views = j.int("views") ?: 0,
                category = j.str("category"),
                snippet = j.str("snippet") ?: j.str("content")?.let { plainText(it) },
            )
        }
        DocPage(items, (o["totalElements"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: items.size.toLong())
    }

    /** GET /api/docs/{id} → 文章详情（content 为 HTML；后端每次打开会 +1 浏览量） */
    suspend fun doc(token: String, id: Long): DocDetail = withContext(Dispatchers.IO) {
        val o = parse(getAuth("/api/docs/$id", token)) ?: throw ApiError("文章响应异常")
        DocDetail(
            id = id,
            title = o.str("title").orEmpty(),
            content = o.str("content").orEmpty(),
            ownerName = o.str("ownerName"),
            ownerId = o.long("ownerId"),
            category = o.str("category"),
            tags = o.str("tags"),
            status = o.str("status"),
            updatedAt = o.str("updatedAt"),
            createdAt = o.str("createdAt"),
            views = o.int("views") ?: 0,
            likes = o.int("likes") ?: 0,
        )
    }

    /** GET /api/docs/{id}/breadcrumb → [{id,title}]（根→…→自身；平铺文档返回空） */
    suspend fun docBreadcrumb(token: String, id: Long): List<CrumbItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/docs/$id/breadcrumb", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val cid = j.long("id") ?: return@mapNotNull null
            CrumbItem(cid, j.str("title").orEmpty())
        }
    }

    // ---- 点赞 / 收藏（POST 切换、GET 我的 id 集合；口径同 Web 端 api/kb.js）----

    /** POST /api/likes/{id} → (是否已赞, 当前点赞数) */
    suspend fun toggleLike(token: String, id: Long): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        val o = parse(authReq("POST", "/api/likes/$id", token)) ?: throw ApiError("点赞响应异常")
        (o.bool("liked") ?: false) to (o.int("likes") ?: 0)
    }

    /** GET /api/likes/mine/ids → 我点过赞的文章 id 集合 */
    suspend fun myLikeIds(token: String): Set<Long> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/likes/mine/ids", token)) }.getOrNull()
        (e as? JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull }.toSet()
    }

    /** POST /api/favorites/{id} → 是否已收藏 */
    suspend fun toggleFavorite(token: String, id: Long): Boolean = withContext(Dispatchers.IO) {
        val o = parse(authReq("POST", "/api/favorites/$id", token)) ?: throw ApiError("收藏响应异常")
        o.bool("favorited") ?: false
    }

    /** GET /api/favorites/mine/ids → 我收藏的文章 id 集合 */
    suspend fun myFavoriteIds(token: String): Set<Long> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/favorites/mine/ids", token)) }.getOrNull()
        (e as? JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull }.toSet()
    }

    // ---- 评论（列表公开；发表/点赞/删除需登录）----

    /** GET /api/docs/{id}/comments → 评论列表（content 为 HTML） */
    suspend fun docComments(token: String, id: Long): List<CommentItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/docs/$id/comments", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val cid = j.long("id") ?: return@mapNotNull null
            CommentItem(
                id = cid,
                parentId = j.long("parentId"),
                userId = j.long("userId"),
                username = j.str("username").orEmpty(),
                displayName = j.str("displayName"),
                content = j.str("content").orEmpty(),
                likes = j.int("likes") ?: 0,
                createdAt = j.str("createdAt").orEmpty(),
            )
        }
    }

    /**
     * POST /api/docs/{id}/comments {content} → 新评论。
     * [parentId] 非空表示回复；与 Web 端一致，回复统一挂到**顶层评论**（最多两层）。
     */
    suspend fun addDocComment(token: String, id: Long, content: String, parentId: Long? = null): CommentItem = withContext(Dispatchers.IO) {
        val body = if (parentId != null) {
            """{"content":${jsonStr(content)},"parentId":$parentId}"""
        } else {
            """{"content":${jsonStr(content)}}"""
        }
        val o = parse(authReq("POST", "/api/docs/$id/comments", token, body))
            ?: throw ApiError("评论响应异常")
        CommentItem(
            id = o.long("id") ?: 0L,
            parentId = o.long("parentId"),
            userId = o.long("userId"),
            username = o.str("username").orEmpty(),
            displayName = o.str("displayName"),
            content = o.str("content").orEmpty(),
            likes = o.int("likes") ?: 0,
            createdAt = o.str("createdAt").orEmpty(),
        )
    }

    /**
     * POST /api/reports → 举报内容。
     * targetType：DOC / POST / COMMENT / DM / USER / FEEDBACK；reason 必填（≤120 字），detail 选填（≤2000 字）。
     */
    suspend fun reportContent(
        token: String,
        targetType: String,
        targetId: Long,
        reason: String,
        detail: String = "",
    ) {
        val body = """{"targetType":${jsonStr(targetType.uppercase())},"targetId":$targetId,"reason":${jsonStr(reason)},"detail":${jsonStr(detail)}}"""
        withContext(Dispatchers.IO) { authReq("POST", "/api/reports", token, body) }
    }

    /** POST /api/comments/{id}/like → (是否已赞, 当前赞数) */
    suspend fun toggleCommentLike(token: String, id: Long): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        val o = parse(authReq("POST", "/api/comments/$id/like", token)) ?: throw ApiError("点赞响应异常")
        (o.bool("liked") ?: false) to (o.int("likes") ?: 0)
    }

    /** GET /api/comments/my-likes → 我点过赞的评论 id 集合 */
    suspend fun myCommentLikes(token: String): Set<Long> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/comments/my-likes", token)) }.getOrNull()
        (e as? JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull }.toSet()
    }

    /** DELETE /api/comments/{id}（仅自己的评论；失败信息直接透出） */
    suspend fun deleteComment(token: String, id: Long) = withContext(Dispatchers.IO) {
        authReq("DELETE", "/api/comments/$id", token)
    }

    /** GET /api/auth/me → (当前用户 id, 角色)，用于阅读页「编辑」按钮权限判定。 */
    suspend fun me(token: String): Pair<Long, String> = withContext(Dispatchers.IO) {
        val o = parse(getAuth("/api/auth/me", token)) ?: throw ApiError("登录状态读取失败")
        (o.long("id") ?: 0L) to o.str("role").orEmpty()
    }

    /** GET /api/docs/{id}/related?limit=4 → 相关文档（同分类 + 标签重叠，公开接口） */
    suspend fun docRelated(token: String, id: Long, limit: Int = 4): List<DocSummary> =
        withContext(Dispatchers.IO) {
            val e = runCatching {
                Json.parseToJsonElement(getAuth("/api/docs/$id/related?limit=$limit", token))
            }.getOrNull() ?: return@withContext emptyList()
            (e as? JsonArray).orEmpty().mapNotNull { item ->
                val j = item as? JsonObject ?: return@mapNotNull null
                val did = j.long("id") ?: return@mapNotNull null
                DocSummary(
                    id = did,
                    title = j.str("title").orEmpty(),
                    ownerName = j.str("ownerName").orEmpty(),
                    updatedAt = j.str("updatedAt").orEmpty(),
                    views = j.int("views") ?: 0,
                    category = j.str("category"),
                    snippet = null,
                )
            }
        }

    // ---------------- 站内通知（评论 / @ 提醒） ----------------

    /** 通知项（GET /api/notifications 倒序）。shareId 用于共享文档提及跳转，文章通知为空。 */
    data class NotiItem(
        val id: Long,
        val type: String,
        val actor: String,
        val docId: Long?,
        val shareId: Long?,
        val commentId: Long?,
        val summary: String,
        val read: Boolean,
        val createdAt: String,
    )

    suspend fun notifications(token: String): List<NotiItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/notifications", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            NotiItem(
                id = id,
                type = j.str("type").orEmpty(),
                actor = j.str("actor").orEmpty(),
                docId = j.long("docId"),
                shareId = j.long("shareId"),
                commentId = j.long("commentId"),
                summary = j.str("summary").orEmpty(),
                read = j.bool("read") ?: false,
                createdAt = j.str("createdAt").orEmpty(),
            )
        }
    }

    /** GET /api/notifications/unread → 未读数 */
    suspend fun unreadNotifications(token: String): Int = withContext(Dispatchers.IO) {
        parse(getAuth("/api/notifications/unread", token))?.int("count") ?: 0
    }

    /** POST /api/notifications/{id}/read */
    suspend fun markNotificationRead(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("POST", "/api/notifications/$id/read", token) }
    }

    /** POST /api/notifications/read-all */
    suspend fun markAllNotificationsRead(token: String) {
        withContext(Dispatchers.IO) { authReq("POST", "/api/notifications/read-all", token) }
    }

    /** DELETE /api/notifications/{id} */
    suspend fun deleteNotification(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("DELETE", "/api/notifications/$id", token) }
    }

    // ---------------- 管理（ADMIN 或细粒度权限；403 文案由后端透出） ----------------

    // ---------------- 我的文章（作者视角；与后台 POST_MANAGE 的 adminDocs 区分） ----------------

    /** GET /api/docs/mine → 我写的文章（含 DRAFT / PENDING / PUBLISHED / BLOCKED） */
    suspend fun myOwnDocs(token: String): List<AdminDocItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/docs/mine", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            AdminDocItem(
                id = id,
                title = j.str("title").orEmpty(),
                ownerName = j.str("ownerName").orEmpty(),
                category = j.str("category"),
                status = j.str("status").orEmpty(),
                updatedAt = j.str("updatedAt").orEmpty(),
                createdAt = j.str("createdAt").orEmpty(),
                views = j.int("views") ?: 0,
            )
        }
    }

    /** POST /api/docs/{id}/submit → 草稿提交审核（普通用户不能自发布，统一转待审核） */
    suspend fun submitDocReview(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("POST", "/api/docs/$id/submit", token) }
    }

    /** POST /api/docs/{id}/withdraw → 撤回审核（待审核 → 草稿） */
    suspend fun withdrawDocReview(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("POST", "/api/docs/$id/withdraw", token) }
    }

    /** DELETE /api/docs/{id} → 作者删自己的文章（与 admin 删除不是同一路径） */
    suspend fun deleteOwnDoc(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("DELETE", "/api/docs/$id", token) }
    }

    /** 管理：帖子列表（全部状态，POST_MANAGE） */
    suspend fun adminDocs(token: String, keyword: String = ""): List<AdminDocItem> = withContext(Dispatchers.IO) {
        val kw = java.net.URLEncoder.encode(keyword, "UTF-8")
        val o = parse(getAuth("/api/admin/docs?page=0&size=50&keyword=$kw", token))
            ?: return@withContext emptyList()
        val arr = (o["content"] as? JsonArray) ?: return@withContext emptyList()
        arr.mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            AdminDocItem(
                id = id,
                title = j.str("title").orEmpty(),
                ownerName = j.str("ownerName").orEmpty(),
                category = j.str("category"),
                status = j.str("status").orEmpty(),
                updatedAt = j.str("updatedAt").orEmpty(),
                createdAt = j.str("createdAt").orEmpty(),
                views = j.int("views") ?: 0,
            )
        }
    }

    /** POST /api/admin/docs/{id}/block|unblock → 封禁/解封 */
    suspend fun setDocBlocked(token: String, id: Long, blocked: Boolean) {
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/admin/docs/$id/" + if (blocked) "block" else "unblock", token)
        }
    }

    /** DELETE /api/admin/docs/{id} → 移入回收站（可恢复） */
    suspend fun moveDocToRecycle(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("DELETE", "/api/admin/docs/$id", token) }
    }

    /** PUT /api/admin/categories/{id} → 调整同级顺序 / 换父级（只传需改的字段） */
    suspend fun updateCategoryOrder(token: String, id: Long, parentId: Long?, sortOrder: Int) {
        val pid = if (parentId == null) "null" else parentId.toString()
        withContext(Dispatchers.IO) {
            authReq("PUT", "/api/admin/categories/$id", token, """{"parentId":$pid,"sortOrder":$sortOrder}""")
        }
    }

    /** GET /api/categories → 分类树（children 递归） */
    suspend fun categoryTree(token: String): List<CatItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/categories", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item -> (item as? JsonObject)?.let { parseCat(it) } }
    }

    private fun parseCat(j: JsonObject): CatItem? {
        val id = j.long("id") ?: return null
        val kids = (j["children"] as? JsonArray).orEmpty().mapNotNull { k -> (k as? JsonObject)?.let { parseCat(it) } }
        return CatItem(id, j.str("name").orEmpty(), j.long("parentId"), j.int("sortOrder") ?: 0, kids)
    }

    /** POST /api/admin/categories {name, parentId?} */
    suspend fun createCategory(token: String, name: String, parentId: Long?) {
        val pid = if (parentId == null) "null" else parentId.toString()
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/admin/categories", token, """{"name":${jsonStr(name)},"parentId":$pid}""")
        }
    }

    /** PUT /api/admin/categories/{id} {name} */
    suspend fun renameCategory(token: String, id: Long, name: String) {
        withContext(Dispatchers.IO) {
            authReq("PUT", "/api/admin/categories/$id", token, """{"name":${jsonStr(name)}}""")
        }
    }

    /** DELETE /api/admin/categories/{id}（有子分类时后端拒绝） */
    suspend fun deleteCategory(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("DELETE", "/api/admin/categories/$id", token) }
    }

    /** GET /api/tags → 标签（含文章计数） */
    suspend fun tagList(token: String): List<TagItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/tags", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            TagItem(id, j.str("name").orEmpty(), j.long("count") ?: 0L)
        }
    }

    /** POST /api/admin/tags {name} */
    suspend fun createTag(token: String, name: String) {
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/admin/tags", token, """{"name":${jsonStr(name)}}""")
        }
    }

    /** DELETE /api/admin/tags/{id} */
    suspend fun deleteTag(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("DELETE", "/api/admin/tags/$id", token) }
    }

    /** GET /api/admin/docs/recycle → 回收站文章（含已删除） */
    suspend fun recycleDocs(token: String): List<AdminDocItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/admin/docs/recycle", token)) }.getOrNull()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            AdminDocItem(
                id = id,
                title = j.str("title").orEmpty(),
                ownerName = j.str("ownerName").orEmpty(),
                category = j.str("category"),
                status = j.str("status").orEmpty(),
                updatedAt = j.str("updatedAt").orEmpty(),
                createdAt = j.str("createdAt").orEmpty(),
                views = j.int("views") ?: 0,
            )
        }
    }

    /** POST /api/admin/docs/{id}/restore → 从回收站恢复 */
    suspend fun restoreDoc(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("POST", "/api/admin/docs/$id/restore", token) }
    }

    /** GET /api/admin/banned-words/libraries → 词库列表 */
    suspend fun bannedLibraries(token: String): List<BannedLibItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/admin/banned-words/libraries", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            BannedLibItem(
                id = id,
                name = j.str("name").orEmpty(),
                description = j.str("description").orEmpty(),
                enabled = j.bool("enabled") ?: true,
                builtin = j.bool("builtin") ?: false,
            )
        }
    }

    /** POST /api/admin/banned-words/libraries → 新建词库 */
    suspend fun createBannedLibrary(token: String, name: String, description: String) {
        withContext(Dispatchers.IO) {
            authReq(
                "POST", "/api/admin/banned-words/libraries", token,
                """{"name":${jsonStr(name)},"description":${jsonStr(description)}}""",
            )
        }
    }

    /** PUT /api/admin/banned-words/libraries/{id} → 启用/停用词库 */
    suspend fun setBannedLibraryEnabled(token: String, id: Long, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            authReq("PUT", "/api/admin/banned-words/libraries/$id", token, """{"enabled":$enabled}""")
        }
    }

    /** GET /api/admin/banned-words?libraryId= → 词条（Page，取一页 100 条足够本地筛选） */
    suspend fun bannedWords(token: String, libraryId: Long?): List<BannedWordItem> = withContext(Dispatchers.IO) {
        val q = if (libraryId == null) "" else "&libraryId=$libraryId"
        val arr = pageArray(runCatching { getAuth("/api/admin/banned-words?page=0&size=100$q", token) }.getOrNull())
        arr.mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            BannedWordItem(id, j.str("word").orEmpty(), j.long("libraryId"))
        }
    }

    /** POST /api/admin/banned-words {libraryId, words} → 批量加词
     *  ⚠️ 后端 body.get("words") 强转 String 后按换行切分，传 JSON 数组会 ClassCastException，必须拼多行文本 */
    suspend fun addBannedWords(token: String, libraryId: Long, words: List<String>) {
        val text = words.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/admin/banned-words", token, """{"libraryId":$libraryId,"words":${jsonStr(text)}}""")
        }
    }

    /** DELETE /api/admin/banned-words/{id} */
    suspend fun deleteBannedWord(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("DELETE", "/api/admin/banned-words/$id", token) }
    }

    /** GET /api/admin/files/orphans?page&size → {content:[{url,size}], total, imageCount, audioCount} */
    suspend fun orphanFiles(token: String): List<OrphanFileItem> = withContext(Dispatchers.IO) {
        val arr = pageArray(runCatching { getAuth("/api/admin/files/orphans?page=1&size=100", token) }.getOrNull())
        arr.mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val url = j.str("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            OrphanFileItem(url, j.long("size"))
        }
    }

    /** DELETE /api/admin/files/orphan?url= → 删除单个孤儿文件（仅管理员） */
    suspend fun deleteOrphanFile(token: String, url: String) {
        val enc = java.net.URLEncoder.encode(url, "UTF-8")
        withContext(Dispatchers.IO) { authReq("DELETE", "/api/admin/files/orphan?url=$enc", token) }
    }

    /** GET /api/admin/exams/grading/pending → 待阅卷记录 */
    suspend fun gradingPending(token: String): List<GradingPendingItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/admin/exams/grading/pending", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val rid = j.long("recordId") ?: return@mapNotNull null
            GradingPendingItem(
                recordId = rid,
                user = j.str("user").orEmpty(),
                paperTitle = j.str("paperTitle").orEmpty(),
                score = j.int("score") ?: 0,
                pendingCount = j.int("pendingCount") ?: 0,
                submittedAt = j.str("submittedAt").orEmpty(),
            )
        }
    }

    /** GET /api/admin/exams/grading/{recordId} → 全部作答（主观题 pending=true 待打分） */
    suspend fun gradingDetail(token: String, recordId: Long): GradingDetail? = withContext(Dispatchers.IO) {
        val o = parse(getAuth("/api/admin/exams/grading/$recordId", token)) ?: return@withContext null
        val arr = (o["items"] as? JsonArray) ?: JsonArray(emptyList())
        val items = arr.mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val qid = j.long("questionId") ?: return@mapNotNull null
            GradingQuestion(
                questionId = qid,
                type = j.str("type").orEmpty(),
                content = j.str("content").orEmpty(),
                userAnswer = j.str("userAnswer").orEmpty(),
                pending = j.bool("pending") ?: false,
                score = j.int("score") ?: 0,
                maxScore = j.int("maxScore") ?: 0,
                comment = j.str("comment").orEmpty(),
            )
        }
        GradingDetail(o.long("recordId") ?: recordId, items)
    }

    /** POST /api/admin/exams/grading/{recordId} → 提交打分（questionId → (分数, 评语)） */
    suspend fun submitGrading(token: String, recordId: Long, marks: Map<Long, Pair<Int, String>>) {
        val items = marks.entries.joinToString(",") { (qid, m) ->
            """{"questionId":$qid,"score":${m.first},"comment":${jsonStr(m.second)}}"""
        }
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/admin/exams/grading/$recordId", token, """{"items":[$items]}""")
        }
    }

    /** GET /api/admin/reports?status= → 举报列表（Page，取第一页 50 条） */
    suspend fun adminReports(token: String, status: String? = null): List<ReportItem> = withContext(Dispatchers.IO) {
        val q = if (status.isNullOrBlank()) "" else "&status=$status"
        val arr = pageArray(runCatching { getAuth("/api/admin/reports?page=0&size=50$q", token) }.getOrNull())
        arr.mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            ReportItem(
                id = id,
                targetType = j.str("targetType").orEmpty(),
                targetId = j.long("targetId"),
                reason = j.str("reason").orEmpty(),
                detail = j.str("detail").orEmpty(),
                status = j.str("status").orEmpty(),
                createdAt = j.str("createdAt").orEmpty(),
            )
        }
    }

    /** PUT /api/admin/reports/{id} → 处理举报（status=HANDLED|REJECTED + 备注） */
    suspend fun handleReport(token: String, id: Long, status: String, note: String) {
        withContext(Dispatchers.IO) {
            authReq(
                "PUT", "/api/admin/reports/$id", token,
                """{"status":${jsonStr(status)},"note":${jsonStr(note)},"action":"NONE"}""",
            )
        }
    }

    /** GET /api/admin/docs/pending → 待审核文章 */
    suspend fun adminPendingDocs(token: String): List<AdminDocItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/admin/docs/pending", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            AdminDocItem(
                id = id,
                title = j.str("title").orEmpty(),
                ownerName = j.str("ownerName").orEmpty(),
                category = j.str("category"),
                status = j.str("status").orEmpty(),
                updatedAt = j.str("updatedAt").orEmpty(),
                createdAt = j.str("createdAt").orEmpty(),
                views = j.int("views") ?: 0,
            )
        }
    }

    /** POST /api/admin/docs/{id}/approve */
    suspend fun approveDoc(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("POST", "/api/admin/docs/$id/approve", token) }
    }

    /** POST /api/admin/docs/{id}/reject（reason 必填，退回作者草稿） */
    suspend fun rejectDoc(token: String, id: Long, reason: String) {
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/admin/docs/$id/reject", token, """{"reason":${jsonStr(reason)}}""")
        }
    }

    /** GET /api/admin/users?keyword= → Page<User>，桌面端只取第一页（50 条） */
    suspend fun adminUsers(token: String, keyword: String = ""): List<AdminUserItem> = withContext(Dispatchers.IO) {
        val kw = java.net.URLEncoder.encode(keyword, "UTF-8")
        val o = parse(getAuth("/api/admin/users?page=0&size=50&keyword=$kw", token))
            ?: return@withContext emptyList()
        val arr = (o["content"] as? JsonArray) ?: return@withContext emptyList()
        arr.mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            AdminUserItem(
                id = id,
                username = j.str("username").orEmpty(),
                displayName = j.str("displayName").orEmpty(),
                role = j.str("role").orEmpty(),
                enabled = j.bool("enabled") ?: true,
                points = j.long("points") ?: 0,
                createdAt = j.str("createdAt").orEmpty(),
            )
        }
    }

    /** POST /api/admin/users/{id}/toggle → 启用/禁用 */
    suspend fun setUserEnabled(token: String, id: Long, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/admin/users/$id/toggle", token, """{"enabled":$enabled}""")
        }
    }

    /** GET /api/admin/departments → 扁平列表（parentId 组树由前端做） */
    suspend fun adminDepartments(token: String): List<DeptItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/admin/departments", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            DeptItem(
                id = id,
                name = j.str("name").orEmpty(),
                code = j.str("code"),
                parentId = j.long("parentId"),
                sortOrder = j.int("sortOrder") ?: 0,
            )
        }
    }

    /** GET /api/admin/announcements → Page<Map>，桌面端取第一页（50 条） */
    suspend fun adminAnnouncements(token: String): List<AdminAnnouncement> = withContext(Dispatchers.IO) {
        val arr = pageArray(runCatching { getAuth("/api/admin/announcements?page=0&size=50", token) }.getOrNull())
        arr.mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            AdminAnnouncement(
                id = id,
                title = j.str("title").orEmpty(),
                content = j.str("content").orEmpty(),
                pinned = j.bool("pinned") ?: false,
                enabled = j.bool("enabled") ?: true,
                createdAt = j.str("createdAt").orEmpty(),
            )
        }
    }

    /** POST /api/admin/announcements → 新建（正文为纯文本，后端按原样存） */
    suspend fun createAnnouncement(token: String, title: String, content: String, pinned: Boolean): Boolean {
        withContext(Dispatchers.IO) {
            authReq(
                "POST", "/api/admin/announcements", token,
                """{"title":${jsonStr(title)},"content":${jsonStr(content)},"pinned":$pinned}""",
            )
        }
        return true
    }

    /** POST /api/admin/announcements/{id}/pin → 切换置顶 */
    suspend fun toggleAnnouncementPin(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("POST", "/api/admin/announcements/$id/pin", token) }
    }

    /** POST /api/admin/announcements/{id}/enable → 切换启用 */
    suspend fun toggleAnnouncementEnabled(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("POST", "/api/admin/announcements/$id/enable", token) }
    }

    /** GET /api/admin/stats → 看板聚合（键值平铺，前端按需展示） */
    suspend fun adminStats(token: String): Map<String, String> = withContext(Dispatchers.IO) {
        val o = parse(getAuth("/api/admin/stats", token)) ?: return@withContext emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in o) {
            out[k] = when (v) {
                is JsonPrimitive -> if (v.contentOrNull == null || v.contentOrNull == "null") "" else v.contentOrNull!!
                is JsonArray -> "${v.size} 项"
                else -> ""
            }
        }
        out
    }

    /** GET /api/admin/audit → Page<Map>（支持数组或 Page 两种形态） */
    suspend fun adminAudit(token: String): List<AuditItem> = withContext(Dispatchers.IO) {
        val arr = pageArray(runCatching { getAuth("/api/admin/audit?page=0&size=50", token) }.getOrNull())
        arr.mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            AuditItem(
                id = id,
                actorName = j.str("actorName").orEmpty(),
                action = j.str("action").orEmpty(),
                targetType = j.str("targetType").orEmpty(),
                targetName = j.str("targetName").orEmpty(),
                detail = j.str("detail").orEmpty(),
                ip = j.str("ip").orEmpty(),
                createdAt = j.str("createdAt").orEmpty(),
            )
        }
    }

    /** GET /api/admin/login-logs → Page<Map> */
    suspend fun adminLoginLogs(token: String): List<LoginLogItem> = withContext(Dispatchers.IO) {
        val arr = pageArray(runCatching { getAuth("/api/admin/login-logs?page=0&size=50", token) }.getOrNull())
        arr.mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            LoginLogItem(
                id = id,
                username = j.str("username").orEmpty(),
                ip = j.str("ip").orEmpty(),
                success = j.bool("success") ?: false,
                reason = j.str("reason").orEmpty(),
                createdAt = j.str("createdAt").orEmpty(),
            )
        }
    }

    /** 管理列表接口有的返回数组、有的返回 Spring Page，统一取出元素数组。 */
    private fun pageArray(text: String?): JsonArray {
        val e = runCatching { Json.parseToJsonElement(text ?: "") }.getOrNull()
            ?: return JsonArray(emptyList())
        return when (e) {
            is JsonArray -> e
            is JsonObject -> (e["content"] as? JsonArray) ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
    }

    /** GET /api/docs/subscriptions → 我订阅的文章（文档已删时 deleted=true） */
    suspend fun mySubscriptions(token: String): List<SubscriptionItem> = withContext(Dispatchers.IO) {
        val e = runCatching {
            Json.parseToJsonElement(getAuth("/api/docs/subscriptions", token))
        }.getOrNull() ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val did = j.long("docId") ?: return@mapNotNull null
            SubscriptionItem(
                docId = did,
                title = j.str("title").orEmpty(),
                category = j.str("category"),
                deleted = j.bool("deleted") ?: false,
                subscribedAt = j.str("subscribedAt").orEmpty(),
            )
        }
    }

    /** GET /api/docs/{id}/subscribed → (是否已订阅, 订阅人数) */
    suspend fun docSubscription(token: String, id: Long): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        val o = parse(getAuth("/api/docs/$id/subscribed", token)) ?: return@withContext false to 0L
        (o.bool("subscribed") ?: false) to (o.long("count") ?: 0L)
    }

    /** POST/DELETE /api/docs/{id}/subscribe → 切换订阅，返回最新 (状态, 人数) */
    suspend fun setSubscribed(token: String, id: Long, on: Boolean): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        val o = parse(authReq(if (on) "POST" else "DELETE", "/api/docs/$id/subscribe", token))
            ?: return@withContext on to 0L
        (o.bool("subscribed") ?: on) to (o.long("count") ?: 0L)
    }

    /** GET /api/docs/{id}/export?format=word → 后端已拼好的 .doc（HTML 封装），原样落盘即可用 Word/WPS 打开 */
    suspend fun exportWord(token: String, id: Long): String = withContext(Dispatchers.IO) {
        getAuth("/api/docs/$id/export?format=word", token)
    }

    /** GET /api/users/{id}/card → 作者小卡片；无权限/不存在返回 null（卡片是可选信息） */
    suspend fun userCard(token: String, userId: Long): AuthorCard? = withContext(Dispatchers.IO) {
        val o = parse(getAuth("/api/users/$userId/card", token)) ?: return@withContext null
        AuthorCard(
            id = o.long("id") ?: userId,
            displayName = o.str("displayName").orEmpty(),
            bio = o.str("bio").orEmpty(),
            codingAge = o.int("codingAge") ?: 0,
            original = o.long("original") ?: 0,
            likes = o.long("likes") ?: 0,
            fans = o.long("fans") ?: 0,
            points = o.long("points") ?: 0,
            displayBadge = o.str("displayBadge"),
            isFollowing = o.bool("isFollowing") ?: false,
        )
    }

    /** POST/DELETE /api/users/{id}/follow → 关注/取关，返回最新 isFollowing */
    suspend fun setFollow(token: String, userId: Long, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        val o = runCatching {
            parse(authReq(if (on) "POST" else "DELETE", "/api/users/$userId/follow", token))
        }.getOrNull()
        o?.bool("isFollowing") ?: on
    }

    /** GET /api/files/mine → 数组 */
    suspend fun myFiles(token: String): List<FileItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/files/mine", token)) }.getOrNull()
            ?: throw ApiError("文件列表响应异常")
        val arr = e as? JsonArray ?: throw ApiError("文件列表响应异常")
        arr.mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            FileItem(
                id = id,
                name = j.str("name").orEmpty(),
                url = j.str("url").orEmpty(),
                size = j.long("size"),
                contentType = j.str("contentType"),
                createdAt = j.str("createdAt").orEmpty(),
            )
        }
    }

    /** GET /api/docs/overview → 首页轻量概览；失败由调用方静默降级，不阻塞首页 */
    suspend fun overview(token: String): HomeOverview = withContext(Dispatchers.IO) {
        val o = parse(getAuth("/api/docs/overview", token)) ?: throw ApiError("首页概览响应异常")
        HomeOverview(
            totalDocs = o.long("totalDocs") ?: 0,
            weekNew = o.long("weekNew") ?: 0,
            members = o.long("members") ?: 0,
            pendingReview = o.long("pendingReview"),
        )
    }

    /** GET /api/announcements → 已启用公告（置顶在前） */
    suspend fun announcements(token: String): List<AnnouncementItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/announcements", token)) }.getOrNull()
            ?: throw ApiError("公告响应异常")
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            AnnouncementItem(
                id = id,
                title = j.str("title").orEmpty(),
                content = j.str("content").orEmpty(),
                pinned = j.bool("pinned") ?: false,
                createdAt = j.str("createdAt").orEmpty(),
            )
        }
    }

    /** GET /api/public/app-version?channel=desktop → 最新版本信息（免登录） */
    suspend fun appVersion(channel: String = AppInfo.channel): AppVersionInfo = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(channel, "UTF-8")
        val o = parse(get("/api/public/app-version?channel=$q")) ?: throw ApiError("版本信息响应异常")
        AppVersionInfo(
            versionCode = o.int("versionCode") ?: 0,
            versionName = o.str("versionName").orEmpty(),
            url = o.str("url").orEmpty(),
            notes = o.str("notes").orEmpty(),
            checksum = o.str("checksum").orEmpty(),
            force = o.bool("force") ?: false,
        )
    }

    /** 通用带认证请求（GET/POST/PUT/DELETE）。[timeoutSeconds] 默认 20s；AI 单步推理等长任务可调大。 */
    private fun authReq(method: String, path: String, token: String, body: String? = null, timeoutSeconds: Long = 20): String = try {
        val resp = http.send(HttpRequest.newBuilder(uri(path))
            .header("Authorization", "Bearer $token")
            .apply { if (body != null) header("Content-Type", "application/json") }
            .method(method, if (body != null) HttpRequest.BodyPublishers.ofString(body)
                else HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .build(), HttpResponse.BodyHandlers.ofString())
        when {
            resp.statusCode() == 401 -> throw ApiError("登录已过期，请重新登录")
            resp.statusCode() >= 400 -> {
                val msg = parse(resp.body())?.str("error")
                throw ApiError(msg ?: "服务器返回 ${resp.statusCode()}")
            }
            else -> resp.body()
        }
    } catch (e: ApiError) {
        throw e
    } catch (e: Exception) {
        throw ApiError("无法连接服务器：${e.message ?: "网络错误"}")
    }

    /** GET /api/ai/models → 可选模型列表（不含 Key） */
    suspend fun aiModels(token: String): List<AiModelOption> = withContext(Dispatchers.IO) {
        val body = authReq("GET", "/api/ai/models", token)
        val e = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            ?: throw ApiError("模型列表响应异常")
        val arr = e as? JsonArray ?: throw ApiError("模型列表响应异常")
        arr.mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            AiModelOption(
                id = id,
                name = j.str("name").orEmpty(),
                provider = j.str("provider").orEmpty(),
                scope = j.str("scope") ?: "system",
                isDefault = j.bool("isDefault") ?: false,
            )
        }
    }

    /** GET /api/ai/conversations → 会话列表（后端按更新时间排序） */
    suspend fun aiConversations(token: String): List<AiConversationItem> = withContext(Dispatchers.IO) {
        val body = authReq("GET", "/api/ai/conversations", token)
        val e = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            ?: throw ApiError("会话列表响应异常")
        val arr = e as? JsonArray ?: throw ApiError("会话列表响应异常")
        arr.mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            AiConversationItem(id = id, title = j.str("title").orEmpty(), updatedAt = j.str("updatedAt").orEmpty())
        }
    }

    /** POST /api/ai/conversations {title} → 新建会话 */
    suspend fun aiCreateConversation(token: String, title: String): AiConversationItem = withContext(Dispatchers.IO) {
        val o = parse(authReq("POST", "/api/ai/conversations", token, """{"title":${jsonStr(title)}}"""))
            ?: throw ApiError("创建会话响应异常")
        val id = o.long("id") ?: throw ApiError("创建会话响应异常")
        AiConversationItem(id = id, title = o.str("title").orEmpty(), updatedAt = o.str("updatedAt").orEmpty())
    }

    /** DELETE /api/ai/conversations/{id}（连带消息） */
    suspend fun aiDeleteConversation(token: String, id: Long) = withContext(Dispatchers.IO) {
        authReq("DELETE", "/api/ai/conversations/$id", token)
    }

    /** PUT /api/ai/conversations/{id} {title} → 重命名会话（后端会截断清洗） */
    suspend fun aiRenameConversation(token: String, id: Long, title: String) = withContext(Dispatchers.IO) {
        val o = parse(authReq("PUT", "/api/ai/conversations/$id", token, """{"title":${jsonStr(title)}}"""))
            ?: throw ApiError("重命名响应异常")
        val id2 = o.long("id") ?: id
        AiConversationItem(id = id2, title = o.str("title").orEmpty(), updatedAt = o.str("updatedAt").orEmpty())
    }

    /** POST /api/ai/feedback {qaId, rating}；rating：1=赞，-1=踩，0=取消 */
    suspend fun aiFeedback(token: String, qaId: Long, rating: Int) = withContext(Dispatchers.IO) {
        authReq("POST", "/api/ai/feedback", token, """{"qaId":$qaId,"rating":$rating,"reason":""}""")
    }

    /** GET /api/ai/conversations/{id}/messages → 历史消息（按时间正序） */
    suspend fun aiMessages(token: String, id: Long): List<AiMsg> = withContext(Dispatchers.IO) {
        val body = authReq("GET", "/api/ai/conversations/$id/messages", token)
        val e = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            ?: throw ApiError("历史消息响应异常")
        val arr = e as? JsonArray ?: throw ApiError("历史消息响应异常")
        arr.mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            AiMsg(
                id = j.long("id") ?: 0L,
                role = j.str("role").orEmpty(),
                content = j.str("content").orEmpty(),
                mode = j.str("mode") ?: "kb",
                sources = parseSourceList(j["sources"]),
            )
        }
    }

    /**
     * POST /api/ai/ask/stream（SSE）：发起流式问答，返回可逐行读取的句柄。
     * [images] 为本地压缩好的 dataURL（data:image/jpeg;base64,…），后端硬限 ≤4 张、单张解码 ≤2MB。
     * 用户点「停止」时调 [ChatStream.abort] 断开连接，已生成内容保留（后端口径同 Web 端）。
     */
    suspend fun askStreamStart(
        token: String,
        question: String,
        conversationId: Long?,
        mode: String,
        modelId: Long?,
        userModelId: Long?,
        images: List<String> = emptyList(),
    ): ChatStream = withContext(Dispatchers.IO) {
        val body = buildString {
            append("""{"question":""").append(jsonStr(question))
            if (conversationId != null) append(""","conversationId":$conversationId""")
            append(""","mode":""").append(jsonStr(mode))
            if (modelId != null) append(""","modelId":$modelId""")
            if (userModelId != null) append(""","userModelId":$userModelId""")
            if (images.isNotEmpty()) {
                append(""","images":[""")
                append(images.joinToString(",") { jsonStr(it) })
                append("]")
                append("}")
            } else {
                append("}")
            }
        }
        val req = HttpRequest.newBuilder(uri("/api/ai/ask/stream"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            // 只约束拿到响应头（含较慢的鉴权）；流式正文不设超时，靠用户停止/服务端结束收尾
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: Exception) {
            throw ApiError("无法连接服务器：${e.message ?: "网络错误"}")
        }
        if (resp.statusCode() == 401) {
            resp.body().close()
            throw ApiError("登录已过期，请重新登录")
        }
        if (resp.statusCode() >= 400) {
            val msg = parse(resp.body().readBytes().toString(Charsets.UTF_8))?.str("error")
            resp.body().close()
            throw ApiError(msg ?: "服务器返回 ${resp.statusCode()}")
        }
        ChatStream(resp)
    }

    /** GET /api/search?q= → 检索结果（Agent 用）。 */
    suspend fun searchDocs(token: String, query: String): List<AgentDoc> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val e = runCatching { Json.parseToJsonElement(authReq("GET", "/api/search?q=$q", token)) }.getOrNull()
            ?: return@withContext emptyList()
        val arr = e as? JsonArray ?: return@withContext emptyList()
        arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val id = o.long("id") ?: return@mapNotNull null
            AgentDoc(id, o.str("title").orEmpty(), o.str("snippet").orEmpty())
        }
    }

    /** GET /api/docs/mine → 我的文档（Agent 用）。 */
    suspend fun myDocs(token: String): List<AgentDoc> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(authReq("GET", "/api/docs/mine", token)) }.getOrNull()
            ?: return@withContext emptyList()
        val arr = e as? JsonArray ?: return@withContext emptyList()
        arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val id = o.long("id") ?: return@mapNotNull null
            AgentDoc(id, o.str("title").orEmpty(), o.str("snippet").orEmpty())
        }
    }

    /** POST /api/ai/agent/step → Agent 单步推理（不落库，避免刷爆会话列表）。 */
    suspend fun aiAgentStep(token: String, prompt: String): String = withContext(Dispatchers.IO) {
        val o = parse(
            authReq("POST", "/api/ai/agent/step", token, """{"question":${jsonStr(prompt)}}""", timeoutSeconds = 110)
        ) ?: throw ApiError("Agent 响应异常")
        o.str("answer") ?: throw ApiError(o.str("error") ?: "Agent 无回答")
    }

    /** POST /api/agent/save → 保存 Agent 最终问答（不触发模型调用）。 */
    suspend fun aiAgentSave(token: String, conversationId: Long, question: String, answer: String) = withContext(Dispatchers.IO) {
        runCatching {
            authReq(
                "POST", "/api/ai/agent/save", token,
                """{"conversationId":$conversationId,"question":${jsonStr(question)},"answer":${jsonStr(answer)}}""",
            )
        }
    }

    /**
     * POST /api/docs → 新建普通文档（Agent create_doc）。
     * 普通用户不能自发布：draft 存 DRAFT，其余一律 PENDING_REVIEW（后端也会强制纠正 PUBLISHED）。
     * @return 文档 id 与实际状态
     */
    suspend fun createDoc(token: String, title: String, html: String, draft: Boolean): Pair<Long, String> = withContext(Dispatchers.IO) {
        val status = if (draft) "DRAFT" else "PENDING_REVIEW"
        val body = """{"title":${jsonStr(title)},"content":${jsonStr(html)},"status":"$status"}"""
        val o = parse(authReq("POST", "/api/docs", token, body)) ?: throw ApiError("创建文档响应异常")
        (o.long("id") ?: 0L) to o.str("status").orEmpty().ifBlank { status }
    }

    /** GET /api/doc-shares/my → 我的共享空间（优先 PERSONAL）。 */
    suspend fun myShares(token: String): List<AgentSpace> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(authReq("GET", "/api/doc-shares/my", token)) }.getOrNull()
            ?: return@withContext emptyList()
        val arr = e as? JsonArray ?: return@withContext emptyList()
        arr.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val id = o.long("id") ?: return@mapNotNull null
            AgentSpace(id, o.str("name").orEmpty(), o.str("type").orEmpty())
        }
    }

    /** GET /api/doc-shares/{sid}/docs → 空间文档列表（无正文，只有标题/标签/权限/占用字节） */
    suspend fun shareDocs(token: String, shareId: Long): List<SharedDocItem> = withContext(Dispatchers.IO) {
        val e = runCatching {
            Json.parseToJsonElement(authReq("GET", "/api/doc-shares/$shareId/docs", token))
        }.getOrNull() ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            (item as? JsonObject)?.let { parseSharedDoc(it) }
        }
    }

    /**
     * GET /api/doc-shares/docs/all → 我可见的**全部空间**文档（跨空间检索用）。
     * 后端带护栏：超量截断、默认不取全文（withContent=false 只给摘要），所以这里只用于列表与筛选。
     */
    suspend fun allShareDocs(token: String): List<SharedDocItem> = withContext(Dispatchers.IO) {
        val e = runCatching {
            Json.parseToJsonElement(authReq("GET", "/api/doc-shares/docs/all", token))
        }.getOrNull() ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            (item as? JsonObject)?.let { parseSharedDoc(it) }
        }
    }

    /** GET /api/doc-shares/{sid}/docs/{docId}/yjs → 协同正文的 Yjs 状态（base64；超限时 state=null） */
    suspend fun shareDocYjs(token: String, shareId: Long, docId: Long): ShareYjs? = withContext(Dispatchers.IO) {
        val o = parse(authReq("GET", "/api/doc-shares/$shareId/docs/$docId/yjs", token))
            ?: return@withContext null
        ShareYjs(
            state = o.str("state"),
            yjsRev = o.long("yjsRev") ?: 0,
            updatedAt = o.str("updatedAt").orEmpty(),
            tooLarge = o.bool("tooLarge") ?: false,
        )
    }

    /** GET /api/doc-shares/{sid}/docs/{docId} → 单篇详情（含 content 的 HTML 快照） */
    suspend fun shareDocDetail(token: String, shareId: Long, docId: Long): SharedDocItem? =
        withContext(Dispatchers.IO) {
            val o = parse(authReq("GET", "/api/doc-shares/$shareId/docs/$docId", token))
                ?: return@withContext null
            parseSharedDoc(o)
        }

    /** 共享文档历史版本（GET /api/doc-shares/{sid}/docs/{docId}/versions；含可疑走 base64 的 yjsState） */
    data class ShareVersionItem(
        val id: Long,
        val title: String,
        val contentLen: Int,
        val yjsState: String?,
        val createdAt: String,
    )

    /**
     * 历史版本列表（倒序）。
     * 后端返回完整实体（content + yjs_state），这里只留长度与 base64 状态：
     * 没有 `/yjs` 接口时可用最近版本的 yjsState 兜底解析正文（旧 jar 也有这个端点）。
     */
    suspend fun shareDocVersions(token: String, shareId: Long, docId: Long): List<ShareVersionItem> =
        withContext(Dispatchers.IO) {
            val e = runCatching {
                Json.parseToJsonElement(authReq("GET", "/api/doc-shares/$shareId/docs/$docId/versions", token))
            }.getOrNull() ?: return@withContext emptyList()
            (e as? JsonArray).orEmpty().mapNotNull { item ->
                val j = item as? JsonObject ?: return@mapNotNull null
                val id = j.long("id") ?: return@mapNotNull null
                val yjsEl = j["yjsState"]
                val yjs = if (yjsEl is JsonPrimitive && yjsEl.isString) yjsEl.content.takeIf { it.length > 16 } else null
                ShareVersionItem(
                    id = id,
                    title = j.str("title").orEmpty(),
                    contentLen = j.str("content")?.length ?: 0,
                    yjsState = yjs,
                    createdAt = j.str("createdAt").orEmpty(),
                )
            }
        }

    /** POST /api/doc-shares/{sid}/docs/{docId}/versions → 把当前内容存成一个历史版本 */
    suspend fun snapshotShareDoc(token: String, shareId: Long, docId: Long) {
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/doc-shares/$shareId/docs/$docId/versions", token)
        }
    }

    /** POST /api/doc-shares/{sid}/docs/{docId}/versions/{vid}/restore → 恢复到该版本（需空间/文档管理员或所有者） */
    suspend fun restoreShareDocVersion(token: String, shareId: Long, docId: Long, vid: Long) {
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/doc-shares/$shareId/docs/$docId/versions/$vid/restore", token)
        }
    }

    private fun parseSharedDoc(j: JsonObject): SharedDocItem? {
        val id = j.long("id") ?: return null
        return SharedDocItem(
            id = id,
            title = j.str("title").orEmpty(),
            ownerName = j.str("ownerName").orEmpty(),
            updatedAt = j.str("updatedAt").orEmpty(),
            tags = j.str("tags"),
            contentType = j.str("contentType"),
            myPerm = j.str("myPerm"),
            size = j.long("size") ?: 0,
            content = j.str("content"),
            shareId = j.long("shareId") ?: 0,
        )
    }

    /** GET /api/courses?category= → 课程列表（培训） */
    suspend fun courses(token: String, category: String? = null): List<CourseItem> = withContext(Dispatchers.IO) {
        val path = if (category.isNullOrBlank()) "/api/courses"
        else "/api/courses?category=${java.net.URLEncoder.encode(category, "UTF-8")}"
        val e = runCatching { Json.parseToJsonElement(getAuth(path, token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item -> (item as? JsonObject)?.let { parseCourse(it) } }
    }

    /** GET /api/courses/categories → 课程分类 */
    suspend fun courseCategories(token: String): List<String> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/courses/categories", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
    }

    /** GET /api/courses/{id} → {course, chapters} */
    suspend fun courseDetail(token: String, id: Long): CourseDetail? = withContext(Dispatchers.IO) {
        val o = parse(getAuth("/api/courses/$id", token)) ?: return@withContext null
        val c = (o["course"] as? JsonObject)?.let { parseCourse(it) } ?: return@withContext null
        val chapters = (o["chapters"] as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val cid = j.long("id") ?: return@mapNotNull null
            ChapterItem(cid, j.str("title").orEmpty(), j.str("content").orEmpty(), j.long("articleId"))
        }
        CourseDetail(c, chapters)
    }

    /** GET /api/courses/{id}/progress → 每章完成状态 */
    suspend fun courseProgress(token: String, id: Long): List<ChapterProgress> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/courses/$id/progress", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val cid = j.long("chapterId") ?: return@mapNotNull null
            ChapterProgress(cid, j.str("title").orEmpty(), j.bool("finished") ?: false)
        }
    }

    /** POST /api/courses/{id}/chapters/{cid}/finish|reset → 标记完成 / 重置 */
    suspend fun setChapterFinished(token: String, courseId: Long, chapterId: Long, finished: Boolean) {
        withContext(Dispatchers.IO) {
            authReq(
                "POST",
                "/api/courses/$courseId/chapters/$chapterId/" + if (finished) "finish" else "reset",
                token,
            )
        }
    }

    /** GET /api/exams → 试卷列表 */
    suspend fun examPapers(token: String): List<ExamPaperItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/exams", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            ExamPaperItem(
                id = id,
                title = j.str("title").orEmpty(),
                description = j.str("description").orEmpty(),
                questionCount = j.int("questionCount") ?: 0,
                totalScore = j.int("totalScore") ?: 0,
                passScore = j.int("passScore") ?: 60,
                durationMin = j.int("durationMin") ?: 0,
                status = j.str("status").orEmpty(),
                practiceEnabled = j.bool("practiceEnabled") ?: true,
            )
        }
    }

    /** GET /api/exams/mine → 我的考试记录 */
    suspend fun examRecords(token: String): List<ExamRecordItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/exams/mine", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            ExamRecordItem(
                id = id,
                paperId = j.long("paperId") ?: 0,
                paperTitle = j.str("paperTitle").orEmpty(),
                score = j.int("score") ?: 0,
                passed = j.bool("passed") ?: false,
                submittedAt = j.str("submittedAt").orEmpty(),
                pendingCount = j.int("pendingCount") ?: 0,
                questionCount = j.int("questionCount") ?: 0,
            )
        }
    }

    private fun parseCourse(j: JsonObject): CourseItem? {
        val id = j.long("id") ?: return null
        return CourseItem(
            id = id,
            title = j.str("title").orEmpty(),
            summary = j.str("summary").orEmpty(),
            category = j.str("category"),
            ownerName = j.str("ownerName"),
            tags = j.str("tags"),
            examPaperId = j.long("examPaperId"),
        )
    }

    /** GET /api/doc-shares/{sid}/docs/{docId} → 取正文（追加流程图时用）。 */
    suspend fun shareDocContent(token: String, shareId: Long, docId: Long): String = withContext(Dispatchers.IO) {
        val o = parse(authReq("GET", "/api/doc-shares/$shareId/docs/$docId", token)) ?: return@withContext ""
        o.str("content").orEmpty()
    }

    /** POST /api/doc-shares/{sid}/docs → 新建共享文档，返回文档 id。 */
    suspend fun createShareDoc(token: String, shareId: Long, title: String, content: String): Long = withContext(Dispatchers.IO) {
        val body = """{"title":${jsonStr(title)},"content":${jsonStr(content)}}"""
        val o = parse(authReq("POST", "/api/doc-shares/$shareId/docs", token, body))
            ?: throw ApiError("创建共享文档响应异常")
        o.long("id") ?: throw ApiError("创建共享文档未返回 id")
    }

    /** PUT /api/doc-shares/{sid}/docs/{docId} → 覆盖正文（在已有文档追加流程图）。 */
    suspend fun updateShareDoc(token: String, shareId: Long, docId: Long, content: String) = withContext(Dispatchers.IO) {
        authReq(
            "PUT", "/api/doc-shares/$shareId/docs/$docId", token,
            """{"content":${jsonStr(content)}}""",
        )
    }
}

/**
 * 流式问答句柄：[nextLine] 在 IO 线程逐行读 SSE；[abort] 关闭底层连接令下一次读取失败退出
 * （用户停止/界面销毁时调用），已生成内容保留。仅暴露行读取，事件解析归 UI 层。
 */
class ChatStream internal constructor(resp: HttpResponse<InputStream>) {
    private val body: InputStream = resp.body()
    private val reader = BufferedReader(InputStreamReader(body, Charsets.UTF_8))

    @Volatile
    internal var aborted = false
        private set

    /** 读一行；流结束或连接被断开返回 null。 */
    fun nextLine(): String? = try {
        reader.readLine()
    } catch (e: Exception) {
        null
    }

    /** 主动停止：关闭连接。 */
    fun abort() {
        aborted = true
        runCatching { body.close() }
    }

    /** 静默收尾（正常结束后关闭连接）。 */
    fun close() {
        runCatching { body.close() }
    }
}

/** sources 字段兼容数组/缺省两种形态（历史消息里存的是 JSON 串，服务端已解析成数组）。 */
internal fun parseSourceList(raw: kotlinx.serialization.json.JsonElement?): List<AiSource> =
    (raw as? JsonArray)?.mapNotNull { e ->
        val s = e as? JsonObject ?: return@mapNotNull null
        val id = s.long("id") ?: return@mapNotNull null
        AiSource(id = id, title = s.str("title").orEmpty(), snippet = s.str("snippet").orEmpty())
    } ?: emptyList()

/** 顶层 JSON 小工具（ApiClient 内同名成员扩展只在类内可见）。 */
private fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull

/** HTML 串粗略去标签，仅作列表摘要兜底。 */
private fun plainText(html: String): String =
    html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(80)
