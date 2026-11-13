package com.kb.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
