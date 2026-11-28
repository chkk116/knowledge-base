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

/** 已绑定邮箱（GET /api/mail/accounts；email 由服务端掩码，如 `1046***@qq.com`）。 */
data class MailAccountItem(
    val id: Long,
    val provider: String,
    val email: String,
    val authType: String,
    val status: String,
    val lastError: String,
    val lastSyncAt: String,
    val lastUid: Long?,
    /** 账号签名：后端只存不拼，写信时由客户端追加到正文末尾。 */
    val signature: String = "",
)

/** 邮件列表项（GET /api/mail/messages → {items,…}）。 */
data class MailBrief(
    val id: Long,
    val accountId: Long,
    val subject: String,
    val fromAddr: String,
    val fromName: String,
    val sentAt: String,
    val snippet: String,
    val hasAttach: Boolean,
    val unread: Boolean,
)

/** 邮件详情（GET /api/mail/messages/{id}）：正文 textBody 优先，htmlBody 仅兜底转纯文本。 */
data class MailDetail(
    val id: Long,
    val subject: String,
    val fromAddr: String,
    val fromName: String,
    val toAddrs: String,
    val sentAt: String,
    val textBody: String,
    val htmlBody: String,
    /** 回复时需要带上的原始 Message-ID（不串线程就传 null）。 */
    val messageId: String,
    /** 附件（后端存的是 JSON 字符串 `[{name,size,url}]`，这里已解析成 (名称, 站内 url)）。 */
    val attachments: List<Pair<String, String>>,
)

/** 邮箱服务商（GET /api/mail/providers，绑定表单用）。 */
data class MailProvider(
    val id: String,
    val name: String,
    val authType: String,
    val emailHint: String,
)

/** 团队空间创建申请（GET /api/admin/doc-shares/create-requests，待审）。 */
data class SpaceCreateRequestItem(
    val id: Long,
    val applicantName: String,
    val requestedName: String,
    val requestedDescription: String,
    val requestedMembers: String,
    val reason: String,
    val createdAt: String,
)

/** 空间扩容申请（GET /api/admin/doc-shares/capacity-requests，待审）。容量单位字节，0 = 不限。 */
data class SpaceCapacityRequestItem(
    val id: Long,
    val shareId: Long,
    val shareName: String,
    val applicantName: String,
    val requestedCapacity: Long,
    val currentCapacity: Long,
    val reason: String,
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
data class AgentSpace(
    val id: Long,
    val name: String,
    val type: String,
    val description: String = "",
    /** 以下均为后端瞬态字段（GET /api/doc-shares/my 时填充），成员/容量展示用 */
    val ownerId: Long = 0L,
    val ownerName: String = "",
    val myPermission: String = "",
    val memberCount: Long = 0L,
    val used: Long = 0L,
    val capacity: Long = 0L,
)

/**
 * 空间扩容申请（`GET|POST /api/doc-shares/{sid}/capacity-requests`）。
 * `requestedCapacity=0` 表示申请「不限」；`currentCapacity` 是提交时的快照；
 * 状态 PENDING / APPROVED / REJECTED，驳回理由在 `reviewComment`。
 */
data class CapacityRequestItem(
    val id: Long,
    val shareId: Long,
    val shareName: String,
    val requestedCapacity: Long,
    val currentCapacity: Long,
    val reason: String,
    val status: String,
    val reviewComment: String,
    val createdAt: String,
)

/** 空间成员（GET /api/doc-shares/{sid}/members；所有者不在 perm 表里，由 AgentSpace.owner* 兜）。 */
data class ShareMemberItem(
    val userId: Long,
    val username: String,
    val displayName: String,
    val avatar: String,
    val permission: String,
)

/**
 * 空间存储（`GET /api/doc-shares/{sid}/storage`）：`used` 为本空间文档占用；`capacity` 为空间上限（null = 不限），
 * `ownerQuota/ownerUsed` 是该空间所有者个人的总配额与已用（团队空间文档算所有者的个人存储）。
 */
data class SpaceStorage(
    val used: Long,
    val capacity: Long?,
    val hasCapacity: Boolean,
    val ownerQuota: Long,
    val ownerUsed: Long,
)

/**
 * 文档双链（`GET /api/doc-shares/{sid}/docs/{docId}/links`）：`out` = 本文 `[[标题]]` 引用的，
 * `back` = 引用了本文的；`resolved=false` 表示标题没对上任何可读文档（悬空链接）。
 */
data class DocLinkItem(
    val title: String,
    val resolved: Boolean,
    val shareId: Long?,
    val docId: Long?,
    val spaceName: String?,
)

data class DocLinks(
    val out: List<DocLinkItem>,
    val back: List<DocLinkItem>,
)

/** 可邀请用户（GET /api/doc-shares/users?keyword=；只回启用的账号）。 */
data class InviteUserItem(
    val id: Long,
    val username: String,
    val displayName: String,
    val avatar: String,
)

/**
 * 共享文档的外部分享链接（`GET|POST /api/doc-shares/{sid}/docs/{docId}/public-link`）。
 * 访问地址是前端 `/s/{token}`，token 失效即 404（服务端令盘作废）。
 */
data class SharePublicLink(
    val enabled: Boolean,
    val token: String,
    val hasPassword: Boolean,
    val expireAt: String,
    val noCopy: Boolean,
    val views: Long,
)

/**
 * 文档级权限成员（`GET /api/doc-shares/{sid}/docs/{docId}/members`）。
 * `spacePermission` 为空间级权限（null = 已不在空间里的历史覆盖）；`permission` 是有效权限（文档级优先）；
 * `override=true` 表示这篇文档单独设置过（可「恢复继承」）。
 */
data class DocMemberItem(
    val userId: Long,
    val username: String,
    val displayName: String,
    val avatar: String,
    val spacePermission: String?,
    val permission: String,
    val override: Boolean,
)

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
    val pinned: Boolean = false,
)

/** 管理：服务端分页结果（Spring Page 的 content/totalElements 子集，page 为 0 基） */
data class PageBox<T>(val items: List<T>, val total: Long, val page: Int, val size: Int) {
    val pages: Int get() = if (size <= 0) 1 else (((total + size - 1) / size).toInt()).coerceAtLeast(1)
}

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

    /** 无凭据请求（验证码、图片等）沿用系统默认跟随，不涉及 token 泄漏。 */
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * 带 Authorization 的请求专用：底层一律不自动跟随，改由 [sendAuth] 逐跳处理，
     * 这样跨源重定向时才有机会剥离凭据（否则 JDK 会把 Bearer token 原样带给外域）。
     */
    private val authHttp: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private fun uri(path: String): URI = URI.create(base.trimEnd('/') + path)

    /** 认证请求最多跟随 5 跳，超出即报错，避免环路把连接耗死。 */
    private val maxAuthRedirects = 5

    /** 凭据类请求头：一旦跳到别的源（scheme+host+port 变化）就不再发送。 */
    private val credentialHeaders = setOf("authorization", "proxy-authorization", "x-api-key", "cookie")

    private fun isAuthRedirect(code: Int): Boolean =
        code == 301 || code == 302 || code == 303 || code == 307 || code == 308

    /** 同源判定口径：scheme + host + port 全一致（缺省端口按协议归一）。 */
    private fun originOf(u: URI): String {
        val scheme = (u.scheme ?: "").lowercase()
        val port = if (u.port != -1) u.port else if (scheme == "https") 443 else 80
        return "$scheme://${(u.host ?: "").lowercase()}:$port"
    }

    private fun closeQuietly(resp: HttpResponse<*>) {
        (resp.body() as? java.io.Closeable)?.let { runCatching { it.close() } }
    }

    /**
     * 手动跟随重定向的认证请求（最多 5 跳）：
     * - 同源（scheme+host+port 相同）继续跟随并保留 Authorization；
     * - 跨源则从这一跳起剥离 [credentialHeaders] 里的凭据，绝不带着 token 去外域；
     * - 303 一律转 GET；301/302 的 POST 转 GET（浏览器口径）；307/308 保持方法与 body。
     * 超时、body 编码与原实现一致，仅在跳转时重建请求。
     */
    private fun <T> sendAuth(
        method: String,
        url: URI,
        token: String,
        body: ByteArray?,
        contentType: String?,
        timeoutSeconds: Long,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        val origin = originOf(url)
        var current = url
        var verb = method.uppercase()
        var payload = body
        var type = contentType
        var keepCredential = true
        var hops = 0
        while (true) {
            val headers = ArrayList<Pair<String, String>>(2)
            headers.add("Authorization" to "Bearer $token")
            if (payload != null && type != null) headers.add("Content-Type" to type)
            val builder = HttpRequest.newBuilder(current)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .method(
                    verb,
                    if (payload != null) HttpRequest.BodyPublishers.ofByteArray(payload)
                    else HttpRequest.BodyPublishers.noBody(),
                )
            headers.forEach { (name, value) ->
                if (keepCredential || name.lowercase() !in credentialHeaders) builder.header(name, value)
            }
            val resp = try {
                authHttp.send(builder.build(), handler)
            } catch (e: Exception) {
                throw ApiError("无法连接服务器：${e.message ?: "网络错误"}")
            }
            val code = resp.statusCode()
            if (!isAuthRedirect(code)) return resp
            val location = resp.headers().firstValue("Location").orElse(null) ?: return resp
            val next = runCatching { current.resolve(location) }.getOrNull() ?: return resp
            if (hops >= maxAuthRedirects) {
                closeQuietly(resp)
                throw ApiError("重定向次数过多：$location")
            }
            closeQuietly(resp)   // 丢弃这一跳的响应体（流式接口也能及时释放）
            if (originOf(next) != origin) keepCredential = false
            if (code == 303 || ((code == 301 || code == 302) && verb == "POST")) {
                verb = "GET"
                payload = null
                type = null
            }
            current = next
            hops++
        }
    }

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

    private fun getAuth(path: String, token: String): String {
        // 走 sendAuth：同源重定向保留 token，跨源重定向自动剥离（不再由 JDK 盲目跟随）
        val resp = sendAuth("GET", uri(path), token, null, null, 20, HttpResponse.BodyHandlers.ofString())
        return when {
            resp.statusCode() == 401 -> throw ApiError("登录已过期，请重新登录")
            resp.statusCode() >= 400 -> {
                val msg = parse(resp.body())?.str("error")
                throw ApiError(msg ?: "服务器返回 ${resp.statusCode()}")
            }
            else -> resp.body()
        }
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

    /** GET /api/favorites/mine → 我收藏的已发布文档（按收藏时间倒序；字段按需取，缺省不报错） */
    suspend fun favoriteDocs(token: String): List<DocSummary> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/favorites/mine", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            DocSummary(
                id = id,
                title = j.str("title").orEmpty(),
                ownerName = j.str("ownerName").orEmpty(),
                updatedAt = j.str("updatedAt").orEmpty(),
                views = j.int("views") ?: 0,
                category = j.str("category"),
                snippet = j.str("snippet"),
            )
        }
    }

    /** DELETE /api/favorites/{docId} → 取消收藏（幂等） */
    suspend fun unfavoriteDoc(token: String, docId: Long) {
        withContext(Dispatchers.IO) { authReq("DELETE", "/api/favorites/$docId", token) }
    }

    /** 私信会话（GET /api/dm/conversations） */
    data class DmConv(
        val peerId: Long,
        val name: String,
        val lastMessage: String,
        val lastFromMe: Boolean,
        val lastAt: String,
        val unread: Int,
    )

    /** 私信消息（GET /api/dm/messages，按时间正序返回） */
    data class DmMsg(
        val id: Long,
        val fromId: Long,
        val kind: String,
        val content: String,
        val createdAt: String,
        val read: Boolean,
    )

    suspend fun dmConversations(token: String): List<DmConv> = withContext(Dispatchers.IO) {
        // 不吞异常：调用方有「会话加载失败」分支，吞掉之后网络故障会显示成「没有会话」。
        // 解析失败也照实抛，交给同一套错误处理。
        val e = Json.parseToJsonElement(getAuth("/api/dm/conversations", token))
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            DmConv(
                peerId = j.long("peerId") ?: return@mapNotNull null,
                name = j.str("name").orEmpty(),
                lastMessage = j.str("lastMessage").orEmpty(),
                lastFromMe = j.bool("lastFromMe") ?: false,
                lastAt = j.str("lastAt").orEmpty(),
                unread = j.int("unread") ?: 0,
            )
        }
    }

    suspend fun dmMessages(token: String, peerId: Long, beforeId: Long? = null, size: Int = 30): List<DmMsg> =
        withContext(Dispatchers.IO) {
            val before = if (beforeId == null) "" else "&beforeId=$beforeId"
            val e = Json.parseToJsonElement(getAuth("/api/dm/messages?peerId=$peerId&size=$size$before", token))
            (e as? JsonArray).orEmpty().mapNotNull { item ->
                val j = item as? JsonObject ?: return@mapNotNull null
                DmMsg(
                    id = j.long("id") ?: return@mapNotNull null,
                    fromId = j.long("fromId") ?: 0L,
                    kind = j.str("kind").orEmpty(),
                    content = j.str("content").orEmpty(),
                    createdAt = j.str("createdAt").orEmpty(),
                    read = j.bool("read") ?: false,
                )
            }
        }

    /** GET /api/users/search?q= → 选人发起私信（排除自己） */
    suspend fun searchUsers(token: String, q: String): List<UserPick> = withContext(Dispatchers.IO) {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val e = Json.parseToJsonElement(getAuth("/api/users/search?q=$enc", token))
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            UserPick(id, j.str("displayName").orEmpty().ifBlank { j.str("username").orEmpty() })
        }
    }

    data class UserPick(val id: Long, val name: String)

    /** DELETE /api/dm/messages/{id} → 撤回自己 2 分钟内发的消息 */
    suspend fun dmRecall(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("DELETE", "/api/dm/messages/$id", token) }
    }

    /** POST /api/dm/read {peerId} → 把与某人的消息标为已读（打开会话时调用） */
    suspend fun dmMarkRead(token: String, peerId: Long) {
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/dm/read", token, """{"peerId":$peerId}""")
        }
    }

    /** POST /api/dm/messages {toId, content} → 发送私信 */
    suspend fun dmSend(token: String, toId: Long, content: String) {
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/dm/messages", token, """{"toId":$toId,"content":${jsonStr(content)}}""")
        }
    }

    /** 复习卡片（GET /api/flashcards/due） */
    data class CardItem(
        val id: Long,
        val front: String,
        val back: String,
        val source: String,
        val intervalDays: Int,
        val dueAt: String,
    )

    /** 卡片统计（GET /api/flashcards/summary） */
    data class CardSummary(val total: Int, val due: Int, val learned: Int, val newCount: Int)

    suspend fun flashcardsDue(token: String): List<CardItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/flashcards/due", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            CardItem(
                id = j.long("id") ?: return@mapNotNull null,
                front = j.str("front").orEmpty(),
                back = j.str("back").orEmpty(),
                source = j.str("source").orEmpty(),
                intervalDays = j.int("intervalDays") ?: 0,
                dueAt = j.str("dueAt").orEmpty(),
            )
        }
    }

    suspend fun flashcardSummary(token: String): CardSummary = withContext(Dispatchers.IO) {
        val o = parse(getAuth("/api/flashcards/summary", token)) ?: return@withContext CardSummary(0, 0, 0, 0)
        CardSummary(
            total = o.int("total") ?: 0,
            due = o.int("due") ?: 0,
            learned = o.int("learned") ?: 0,
            newCount = o.int("new") ?: 0,
        )
    }

    /** POST /api/flashcards/{id}/review {grade} → grade: again / hard / good / easy */
    suspend fun reviewFlashcard(token: String, id: Long, grade: String) {
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/flashcards/$id/review", token, """{"grade":${jsonStr(grade)}}""")
        }
    }

    /** GET /api/points/log → 我的积分明细（最近 50 条，按时间倒序） */
    data class PointLogRow(
        val id: Long,
        val delta: Int,
        val type: String,
        val reason: String,
        val balance: Int,
        val createdAt: String,
    )

    suspend fun pointsLog(token: String): List<PointLogRow> = withContext(Dispatchers.IO) {
        val e = Json.parseToJsonElement(getAuth("/api/points/log", token))
        (e as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            PointLogRow(
                id = j.long("id") ?: return@mapNotNull null,
                delta = j.int("delta") ?: 0,
                type = j.str("type").orEmpty(),
                reason = j.str("reason").orEmpty(),
                balance = j.int("balance") ?: 0,
                createdAt = j.str("createdAt").orEmpty(),
            )
        }
    }

    /** 徽章墙一行 */
    data class BadgeRow(
        val id: String,
        val name: String,
        val desc: String,
        val icon: String,
        val earned: Boolean,
        val current: Long,
        val threshold: Long,
        val progress: Int,
        val worn: Boolean,
    )

    /** 徽章墙：全部定义 + 获得状态 + 进度 */
    data class BadgeWall(val badges: List<BadgeRow>, val earned: Int, val total: Int)

    /** GET /api/badges → { badges:[…], earned, total, wornBadges } */
    suspend fun badgeWall(token: String): BadgeWall = withContext(Dispatchers.IO) {
        val o = parse(getAuth("/api/badges", token))
            ?: throw IllegalStateException("徽章数据格式异常，请稍后重试")
        val rows = (o["badges"] as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            // 没有 id 的行直接丢掉：LazyColumn 用 id 当 key，缺字段会让所有行都变成 ""，
            // 一渲染就是 duplicate key 崩溃（其它面板也是这么处理的）
            val badgeId = j.str("id").orEmpty()
            if (badgeId.isBlank()) return@mapNotNull null
            BadgeRow(
                id = badgeId,
                name = j.str("name").orEmpty(),
                desc = j.str("desc").orEmpty(),
                icon = j.str("icon").orEmpty(),
                earned = j.bool("earned") ?: false,
                current = j.long("current") ?: 0,
                threshold = j.long("threshold") ?: 0,
                progress = j.int("progress") ?: 0,
                worn = j.bool("worn") ?: false,
            )
        }
        BadgeWall(rows, o.int("earned") ?: 0, o.int("total") ?: rows.size)
    }
    data class RankRow(val rank: Int, val username: String, val displayName: String, val points: Long)

    /** 排行榜：前 20 名 + 当前用户名次 */
    data class RankingData(val top: List<RankRow>, val myRank: Int, val myPoints: Long, val total: Long)

    /** GET /api/points/ranking → { top:[{rank,username,displayName,points}], me:{rank,points}, total } */
    suspend fun pointsRanking(token: String): RankingData = withContext(Dispatchers.IO) {
        val o = parse(getAuth("/api/points/ranking", token)) ?: return@withContext RankingData(emptyList(), 0, 0, 0)
        val top = (o["top"] as? JsonArray).orEmpty().mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            RankRow(
                rank = j.int("rank") ?: 0,
                username = j.str("username").orEmpty(),
                displayName = j.str("displayName").orEmpty(),
                points = j.long("points") ?: 0,
            )
        }
        val me = o["me"] as? JsonObject
        RankingData(
            top = top,
            myRank = me?.int("rank") ?: 0,
            myPoints = me?.long("points") ?: 0,
            total = o.long("total") ?: 0,
        )
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

    /** 管理：帖子列表（全部状态，POST_MANAGE），服务端分页 */
    suspend fun adminDocsPage(
        token: String,
        keyword: String = "",
        page: Int = 0,
        size: Int = 20,
    ): PageBox<AdminDocItem> = withContext(Dispatchers.IO) {
        val kw = java.net.URLEncoder.encode(keyword, "UTF-8")
        val o = parse(getAuth("/api/admin/docs?page=$page&size=$size&keyword=$kw", token))
            ?: return@withContext PageBox(emptyList(), 0, page, size)
        val arr = (o["content"] as? JsonArray) ?: JsonArray(emptyList())
        val items = arr.mapNotNull { item ->
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
                pinned = j.bool("pinned") ?: false,
            )
        }
        PageBox(items, o.long("totalElements") ?: items.size.toLong(), page, size)
    }

    /** POST /api/admin/docs/{id}/pin|unpin → 置顶/取消置顶 */
    suspend fun setDocPinned(token: String, id: Long, pinned: Boolean) {
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/admin/docs/$id/" + if (pinned) "pin" else "unpin", token)
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

    /** DELETE /api/admin/docs/{id}/purge → 彻底删除（不可恢复，仅回收站里用） */
    suspend fun purgeDoc(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("DELETE", "/api/admin/docs/$id/purge", token) }
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
    suspend fun adminReportsPage(
        token: String,
        status: String? = null,
        page: Int = 0,
        size: Int = 20,
    ): PageBox<ReportItem> = withContext(Dispatchers.IO) {
        val q = if (status.isNullOrBlank()) "" else "&status=$status"
        val (arr, total) = pageParts(runCatching { getAuth("/api/admin/reports?page=$page&size=$size$q", token) }.getOrNull())
        val items = arr.mapNotNull { item ->
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
        PageBox(items, total, page, size)
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
    /** GET /api/admin/users?page=&size=&keyword= → 服务端分页（Spring Page） */
    suspend fun adminUsersPage(
        token: String,
        keyword: String = "",
        page: Int = 0,
        size: Int = 20,
    ): PageBox<AdminUserItem> = withContext(Dispatchers.IO) {
        val kw = java.net.URLEncoder.encode(keyword, "UTF-8")
        val o = parse(getAuth("/api/admin/users?page=$page&size=$size&keyword=$kw", token))
            ?: return@withContext PageBox(emptyList(), 0, page, size)
        val arr = (o["content"] as? JsonArray) ?: JsonArray(emptyList())
        val items = arr.mapNotNull { item ->
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
        PageBox(items, o.long("totalElements") ?: items.size.toLong(), page, size)
    }

    /** POST /api/admin/users/{id}/toggle → 启用/禁用 */
    suspend fun setUserEnabled(token: String, id: Long, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/admin/users/$id/toggle", token, """{"enabled":$enabled}""")
        }
    }

    /**
     * PUT /api/admin/users/{id} {role} → 改角色。
     * 后端 UpdateReq 对 null 字段跳过（AdminService.updateUser），所以只传 role 不会清掉昵称/邮箱。
     */
    suspend fun setUserRole(token: String, id: Long, role: String) {
        withContext(Dispatchers.IO) {
            authReq("PUT", "/api/admin/users/$id", token, """{"role":${jsonStr(role)}}""")
        }
    }

    /** POST /api/admin/users/{id}/points {delta,reason} → 调积分（可正可负，后端写一条 ADJUST 明细） */
    suspend fun adjustUserPoints(token: String, id: Long, delta: Int, reason: String) {
        withContext(Dispatchers.IO) {
            authReq(
                "POST", "/api/admin/users/$id/points", token,
                """{"delta":$delta,"reason":${jsonStr(reason)}}""",
            )
        }
    }

    /** POST /api/admin/users/{id}/reset-password → 返回后端生成的随机明文密码（只回显这一次） */
    suspend fun resetUserPassword(token: String, id: Long): String = withContext(Dispatchers.IO) {
        val o = parse(authReq("POST", "/api/admin/users/$id/reset-password", token))
        o?.str("newPassword").orEmpty()
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

    /** 部门写操作共用体：只传需要改的字段（后端 OrgService.DeptReq 对 null 跳过） */
    private fun deptBody(
        name: String? = null,
        code: String? = null,
        parentId: Long? = null,
        sortOrder: Int? = null,
        description: String? = null,
        status: String? = null,
    ): String {
        val sb = StringBuilder("{")
        fun put(k: String, v: String?) {
            if (v == null) return
            if (sb.length > 1) sb.append(',')
            sb.append('"').append(k).append("\":").append(v)
        }
        put("name", name?.let { jsonStr(it) })
        put("code", code?.let { jsonStr(it) })
        put("parentId", parentId?.toString())
        put("sortOrder", sortOrder?.toString())
        put("description", description?.let { jsonStr(it) })
        put("status", status?.let { jsonStr(it) })
        sb.append('}')
        return sb.toString()
    }

    /** POST /api/admin/departments → 新建部门 */
    suspend fun createDepartment(
        token: String,
        name: String,
        code: String?,
        parentId: Long?,
        sortOrder: Int?,
        description: String?,
    ) {
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/admin/departments", token, deptBody(name, code, parentId, sortOrder, description))
        }
    }

    /** PUT /api/admin/departments/{id} → 修改部门（只传改动字段） */
    suspend fun updateDepartment(
        token: String,
        id: Long,
        name: String?,
        code: String?,
        parentId: Long?,
        sortOrder: Int?,
        description: String?,
        status: String?,
    ) {
        withContext(Dispatchers.IO) {
            authReq("PUT", "/api/admin/departments/$id", token, deptBody(name, code, parentId, sortOrder, description, status))
        }
    }

    /** DELETE /api/admin/departments/{id} → 删除（有子部门或仍有成员时后端会拒，文案原样透出） */
    suspend fun deleteDepartment(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("DELETE", "/api/admin/departments/$id", token) }
    }

    /** POST /api/admin/departments/{id}/members {userIds} → 把用户加入部门 */
    suspend fun addDeptMembers(token: String, deptId: Long, userIds: List<Long>) {
        withContext(Dispatchers.IO) {
            authReq(
                "POST", "/api/admin/departments/$deptId/members", token,
                """{"userIds":[${userIds.joinToString(",")}]}""",
            )
        }
    }

    /** GET /api/admin/announcements?page=&size= → 服务端分页 */
    suspend fun adminAnnouncementsPage(
        token: String,
        page: Int = 0,
        size: Int = 20,
    ): PageBox<AdminAnnouncement> = withContext(Dispatchers.IO) {
        val o = parse(runCatching { getAuth("/api/admin/announcements?page=$page&size=$size", token) }.getOrNull())
            ?: return@withContext PageBox(emptyList(), 0, page, size)
        val arr = (o["content"] as? JsonArray) ?: JsonArray(emptyList())
        val items = arr.mapNotNull { item ->
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
        PageBox(items, o.long("totalElements") ?: items.size.toLong(), page, size)
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
    /**
     * GET /api/admin/stats → 原始 JSON（看板图表用）。
     * 与 [adminStats] 的区别：这里保留数组（trendLabels/docTrend7/userTrend7/commentTrend7）与数字类型，
     * 供环形/柱状/折线图直接消费；[adminStats] 仍保留给「键值列表」式的旧调用方。
     */
    suspend fun adminStatsJson(token: String): JsonObject = withContext(Dispatchers.IO) {
        parse(getAuth("/api/admin/stats", token)) ?: JsonObject(emptyMap())
    }

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
    suspend fun adminAuditPage(token: String, page: Int = 0, size: Int = 20): PageBox<AuditItem> = withContext(Dispatchers.IO) {
        val (arr, total) = pageParts(runCatching { getAuth("/api/admin/audit?page=$page&size=$size", token) }.getOrNull())
        val items = arr.mapNotNull { item ->
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
        PageBox(items, total, page, size)
    }

    /** GET /api/admin/login-logs?page=&size= → 服务端分页 */
    suspend fun adminLoginLogsPage(token: String, page: Int = 0, size: Int = 20): PageBox<LoginLogItem> = withContext(Dispatchers.IO) {
        val (arr, total) = pageParts(runCatching { getAuth("/api/admin/login-logs?page=$page&size=$size", token) }.getOrNull())
        val items = arr.mapNotNull { item ->
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
        PageBox(items, total, page, size)
    }

    /**
     * 分页接口统一解析：项目里管理端列表有的返回裸数组（不分页），有的返回 Spring Page。
     * 返回「元素数组 + 总条数」，翻页条要用 total 而不是当页条数。
     */
    private fun pageParts(text: String?): Pair<JsonArray, Long> {
        val e = runCatching { Json.parseToJsonElement(text ?: "") }.getOrNull()
            ?: return JsonArray(emptyList()) to 0L
        return when (e) {
            is JsonArray -> e to e.size.toLong()
            // 兜底与 *_Page 系列一致：缺 totalElements 时用当页条数，否则会出现「列表有内容、共 0 条」
            is JsonObject -> {
                val arr = (e["content"] as? JsonArray) ?: JsonArray(emptyList())
                arr to (e.long("totalElements") ?: arr.size.toLong())
            }
            else -> JsonArray(emptyList()) to 0L
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

    /**
     * GET /api/mail/accounts → 已绑定邮箱（服务端已把 email 掩码）。
     * 邮箱模块是 11-18 新增的全栈能力：账号 / 收件箱 / 阅读 / 已读 / 删除 / 同步 / 授权码绑定。
     */
    suspend fun mailAccounts(token: String): List<MailAccountItem> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/mail/accounts", token)) }.getOrNull()
            ?: throw ApiError("邮箱账号响应异常")
        (e as? JsonArray ?: throw ApiError("邮箱账号响应异常")).mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            MailAccountItem(
                id = id,
                provider = j.str("provider").orEmpty(),
                email = j.str("email").orEmpty(),
                authType = j.str("authType").orEmpty(),
                status = j.str("status").orEmpty(),
                lastError = j.str("lastError").orEmpty(),
                lastSyncAt = j.str("lastSyncAt").orEmpty(),
                lastUid = j.long("lastUid"),
                signature = j.str("signature").orEmpty(),
            )
        }
    }

    /** GET /api/mail/messages → **对象** `{items,total,page,size}`（不是数组）。返回 (列表, 总数)。 */
    suspend fun mailMessages(
        token: String,
        accountId: Long? = null,
        folder: String = "INBOX",
        q: String = "",
        page: Int = 0,
        size: Int = 50,
    ): Pair<List<MailBrief>, Long> = withContext(Dispatchers.IO) {
        val aq = if (accountId == null || accountId <= 0) "" else "&accountId=$accountId"
        val qq = if (q.isBlank()) "" else "&q=" + java.net.URLEncoder.encode(q, "UTF-8")
        val e = runCatching {
            Json.parseToJsonElement(getAuth("/api/mail/messages?folder=$folder&page=$page&size=$size$aq$qq", token))
        }.getOrNull() ?: throw ApiError("邮件列表响应异常")
        val root = e as? JsonObject ?: throw ApiError("邮件列表响应异常")
        val arr = root["items"] as? JsonArray ?: JsonArray(emptyList())
        val list = arr.mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            MailBrief(
                id = id,
                accountId = j.long("accountId") ?: 0L,
                subject = j.str("subject").orEmpty(),
                fromAddr = j.str("fromAddr").orEmpty(),
                fromName = j.str("fromName").orEmpty(),
                sentAt = j.str("sentAt").orEmpty(),
                snippet = j.str("snippet").orEmpty(),
                hasAttach = j.bool("hasAttach") ?: false,
                unread = j.bool("unread") ?: false,
            )
        }
        list to (root.long("total") ?: list.size.toLong())
    }

    /** GET /api/mail/messages/{id} → 列表字段 + textBody/htmlBody/toAddrs */
    suspend fun mailDetail(token: String, id: Long): MailDetail? = withContext(Dispatchers.IO) {
        val j = runCatching { Json.parseToJsonElement(getAuth("/api/mail/messages/$id", token)) }.getOrNull()
            as? JsonObject ?: return@withContext null
        MailDetail(
            id = id,
            subject = j.str("subject").orEmpty(),
            fromAddr = j.str("fromAddr").orEmpty(),
            fromName = j.str("fromName").orEmpty(),
            toAddrs = j.str("toAddrs").orEmpty(),
            sentAt = j.str("sentAt").orEmpty(),
            textBody = j.str("textBody").orEmpty(),
            htmlBody = j.str("htmlBody").orEmpty(),
            messageId = j.str("messageId").orEmpty(),
            // attachments 是嵌套 JSON 字符串，解析失败就当没有附件
            attachments = runCatching {
                (Json.parseToJsonElement(j.str("attachments").orEmpty()) as JsonArray).mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    val url = o.str("url") ?: return@mapNotNull null
                    (o.str("name") ?: url.substringAfterLast('/')) to url
                }
            }.getOrDefault(emptyList()),
        )
    }

    /** POST /api/mail/messages/{id}/read?read= → 本地已读（尽力回写 IMAP） */
    suspend fun mailMarkRead(token: String, id: Long, read: Boolean) {
        withContext(Dispatchers.IO) { authReq("POST", "/api/mail/messages/$id/read?read=$read", token) }
    }

    /** DELETE /api/mail/messages/{id} → 本地删除（并尽力在服务器删除） */
    suspend fun mailDelete(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("DELETE", "/api/mail/messages/$id", token) }
    }

    /** POST /api/mail/accounts/{id}/sync → 手动同步，返回新增封数 */
    suspend fun mailSync(token: String, accountId: Long): Int = withContext(Dispatchers.IO) {
        val e = runCatching {
            Json.parseToJsonElement(authReq("POST", "/api/mail/accounts/$accountId/sync", token))
        }.getOrNull()
        (e as? JsonObject)?.long("added")?.toInt() ?: 0
    }

    /** GET /api/mail/providers → 支持的服务商（绑定表单用） */
    suspend fun mailProviders(token: String): List<MailProvider> = withContext(Dispatchers.IO) {
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/mail/providers", token)) }.getOrNull()
            ?: throw ApiError("服务商列表响应异常")
        (e as? JsonArray ?: throw ApiError("服务商列表响应异常")).mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val id = j.str("id") ?: return@mapNotNull null
            MailProvider(id = id, name = j.str("name").orEmpty(), authType = j.str("authType").orEmpty(),
                emailHint = j.str("emailHint").orEmpty())
        }
    }

    /** POST /api/mail/accounts/code {provider,email,code} → 授权码绑定（后端真连 IMAP 验证） */
    suspend fun mailBindCode(token: String, provider: String, email: String, code: String) {
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/mail/accounts/code", token,
                """{"provider":${jsonStr(provider)},"email":${jsonStr(email)},"code":${jsonStr(code)}}""")
        }
    }

    /**
     * POST /api/mail/send {accountId,to[],cc[],subject,body,inReplyTo?} → 真投递（失败不留本地档）。
     * ⚠️ 后端 `/send` 与 MailSendService 仍在他人的工作区，未升级时返回 404/405，调用方要把这句透出去。
     */
    suspend fun mailSend(
        token: String,
        accountId: Long,
        to: List<String>,
        subject: String,
        body: String,
        inReplyTo: String? = null,
    ) {
        withContext(Dispatchers.IO) {
            val tos = to.joinToString(",") { jsonStr(it) }
            val extra = if (inReplyTo.isNullOrBlank()) "" else ""","inReplyTo":${jsonStr(inReplyTo)}"""
            authReq("POST", "/api/mail/send", token,
                """{"accountId":$accountId,"to":[$tos],"cc":[],"subject":${jsonStr(subject)},"body":${jsonStr(body)}$extra}""")
        }
    }

    /** POST /api/mail/drafts {accountId,to,subject,body} → 草稿（folder=DRAFT，不投递） */
    suspend fun mailSaveDraft(token: String, accountId: Long, to: String, subject: String, body: String) {
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/mail/drafts", token,
                """{"accountId":$accountId,"to":${jsonStr(to)},"subject":${jsonStr(subject)},"body":${jsonStr(body)}}""")
        }
    }

    /** GET /api/mail/contacts?q= → 历史发件人地址（写信补全）。后端未升级时返回空列表，不报错。 */
    suspend fun mailContacts(token: String, q: String): List<String> = withContext(Dispatchers.IO) {
        val qq = if (q.isBlank()) "" else "?q=" + java.net.URLEncoder.encode(q, "UTF-8")
        val e = runCatching { Json.parseToJsonElement(getAuth("/api/mail/contacts$qq", token)) }.getOrNull()
            ?: return@withContext emptyList()
        (e as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: emptyList()
    }

    /** PUT /api/mail/accounts/{id}/signature {signature} → 保存账号签名（≤2000 字，服务端截断） */
    suspend fun mailSaveSignature(token: String, accountId: Long, signature: String) {
        withContext(Dispatchers.IO) {
            authReq("PUT", "/api/mail/accounts/$accountId/signature", token,
                """{"signature":${jsonStr(signature)}}""")
        }
    }

    // ---------- 后台：空间创建 / 扩容申请审批（仅完全管理员）----------

    /** GET /api/admin/doc-shares/create-requests → 待审的团队空间创建申请 */
    suspend fun spaceCreateRequests(token: String): List<SpaceCreateRequestItem> = withContext(Dispatchers.IO) {
        val e = runCatching {
            Json.parseToJsonElement(getAuth("/api/admin/doc-shares/create-requests", token))
        }.getOrNull() ?: throw ApiError("空间申请响应异常")
        (e as? JsonArray ?: throw ApiError("空间申请响应异常")).mapNotNull { it ->
            val j = it as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            SpaceCreateRequestItem(
                id = id,
                applicantName = j.str("applicantName").orEmpty(),
                requestedName = j.str("requestedName").orEmpty(),
                requestedDescription = j.str("requestedDescription").orEmpty(),
                requestedMembers = j.str("requestedMembers").orEmpty(),
                reason = j.str("reason").orEmpty(),
                createdAt = j.str("createdAt").orEmpty(),
            )
        }
    }

    /** GET /api/admin/doc-shares/capacity-requests → 待审的空间扩容申请 */
    suspend fun spaceCapacityRequests(token: String): List<SpaceCapacityRequestItem> = withContext(Dispatchers.IO) {
        val e = runCatching {
            Json.parseToJsonElement(getAuth("/api/admin/doc-shares/capacity-requests", token))
        }.getOrNull() ?: throw ApiError("扩容申请响应异常")
        (e as? JsonArray ?: throw ApiError("扩容申请响应异常")).mapNotNull { it ->
            val j = it as? JsonObject ?: return@mapNotNull null
            val id = j.long("id") ?: return@mapNotNull null
            SpaceCapacityRequestItem(
                id = id,
                shareId = j.long("shareId") ?: 0L,
                shareName = j.str("shareName").orEmpty(),
                applicantName = j.str("applicantName").orEmpty(),
                requestedCapacity = j.long("requestedCapacity") ?: 0L,
                currentCapacity = j.long("currentCapacity") ?: 0L,
                reason = j.str("reason").orEmpty(),
                createdAt = j.str("createdAt").orEmpty(),
            )
        }
    }

    /** 通过 / 驳回（驳回必须带理由） */
    suspend fun approveSpaceCreateRequest(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("POST", "/api/admin/doc-shares/create-requests/$id/approve", token) }
    }

    suspend fun rejectSpaceCreateRequest(token: String, id: Long, comment: String) {
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/admin/doc-shares/create-requests/$id/reject", token,
                """{"comment":${jsonStr(comment)}}""")
        }
    }

    suspend fun approveSpaceCapacityRequest(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("POST", "/api/admin/doc-shares/capacity-requests/$id/approve", token) }
    }

    suspend fun rejectSpaceCapacityRequest(token: String, id: Long, comment: String) {
        withContext(Dispatchers.IO) {
            authReq("POST", "/api/admin/doc-shares/capacity-requests/$id/reject", token,
                """{"comment":${jsonStr(comment)}}""")
        }
    }

    /**
     * POST /api/files/mine → 上传到「我的文件」（multipart，需登录）。
     * 后端按 (ownerId, sha256) 去重：同内容重复上传会返回原记录（`deduped=true`）。
     */
    suspend fun uploadMyFile(token: String, file: java.io.File, mime: String): FileItem? =
        withContext(Dispatchers.IO) {
            val boundary = "----kb" + System.nanoTime()
            val body = java.io.ByteArrayOutputStream()
            fun w(s: String) { body.write(s.toByteArray(Charsets.UTF_8)) }
            w("--$boundary\r\n")
            w("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
            w("Content-Type: $mime\r\n\r\n")
            body.write(file.readBytes())
            w("\r\n--$boundary--\r\n")
            // 走 sendAuth：上传同样带 token，跨源重定向必须剥离凭据
            val resp = sendAuth(
                "POST", uri("/api/files/mine"), token,
                body.toByteArray(), "multipart/form-data; boundary=$boundary",
                120, HttpResponse.BodyHandlers.ofString(),
            )
            if (resp.statusCode() == 401) throw ApiError("登录已过期，请重新登录")
            if (resp.statusCode() >= 400) {
                throw ApiError(parse(resp.body())?.str("error") ?: "上传失败（${resp.statusCode()}）")
            }
            parse(resp.body())?.let { j ->
                val id = j.long("id") ?: return@let null
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

    /** DELETE /api/files/mine/{id} → 删除自己上传的文件（物理文件同时移除） */
    suspend fun deleteMyFile(token: String, id: Long) {
        withContext(Dispatchers.IO) { authReq("DELETE", "/api/files/mine/$id", token) }
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

    /**
     * 通用带认证请求（GET/POST/PUT/DELETE）。[timeoutSeconds] 默认 20s；AI 单步推理等长任务可调大。
     * 重定向由 [sendAuth] 手动跟随：同源保留 token，跨源剥离凭据。
     */
    private fun authReq(method: String, path: String, token: String, body: String? = null, timeoutSeconds: Long = 20): String {
        val resp = sendAuth(
            method, uri(path), token,
            body?.toByteArray(Charsets.UTF_8), if (body != null) "application/json" else null,
            timeoutSeconds, HttpResponse.BodyHandlers.ofString(),
        )
        return when {
            resp.statusCode() == 401 -> throw ApiError("登录已过期，请重新登录")
            resp.statusCode() >= 400 -> {
                val msg = parse(resp.body())?.str("error")
                throw ApiError(msg ?: "服务器返回 ${resp.statusCode()}")
            }
            else -> resp.body()
        }
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
        // 只约束拿到响应头（含较慢的鉴权）；流式正文不设超时，靠用户停止/服务端结束收尾。
        // 重定向同样走 sendAuth：同源保留 token，跨源剥离后重发（流式响应体在跳转时丢弃）。
        val resp = sendAuth(
            "POST", uri("/api/ai/ask/stream"), token,
            body.toByteArray(Charsets.UTF_8), "application/json",
            30, HttpResponse.BodyHandlers.ofInputStream(),
        )
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

    /**
     * POST /api/ai/agent/save → 保存 Agent 最终问答（不触发模型调用）。
     * 失败一律抛 [ApiError]（401/断网/服务端 {ok:false} 都算失败），由界面提示；
     * 调用方不要吞掉异常，否则用户会误以为已保存。
     */
    suspend fun aiAgentSave(token: String, conversationId: Long, question: String, answer: String): Unit = withContext(Dispatchers.IO) {
        val body = authReq(
            "POST", "/api/ai/agent/save", token,
            """{"conversationId":$conversationId,"question":${jsonStr(question)},"answer":${jsonStr(answer)}}""",
        )
        // HTTP 200 但业务失败（{ok:false,error}）同样按失败处理，并带上服务端文案
        val o = parse(body)
        if (o != null && o.bool("ok") == false) throw ApiError(o.str("error") ?: "保存失败：服务端拒绝写入")
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
            AgentSpace(
                id = id,
                name = o.str("name").orEmpty(),
                type = o.str("type").orEmpty(),
                description = o.str("description").orEmpty(),
                ownerId = o.long("ownerId") ?: 0L,
                ownerName = o.str("ownerName").orEmpty(),
                myPermission = o.str("myPermission").orEmpty(),
                memberCount = o.long("memberCount") ?: 0L,
                used = o.long("used") ?: 0L,
                capacity = o.long("capacity") ?: 0L,
            )
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

    /** GET /api/doc-shares/{sid}/members → 空间成员列表（不含所有者行；权限 READ/WRITE/ADMIN） */
    suspend fun shareMembers(token: String, shareId: Long): List<ShareMemberItem> = withContext(Dispatchers.IO) {
        val e = runCatching {
            Json.parseToJsonElement(authReq("GET", "/api/doc-shares/$shareId/members", token))
        }.getOrNull() ?: throw ApiError("成员列表响应异常")
        (e as? JsonArray ?: throw ApiError("成员列表响应异常")).mapNotNull { item ->
            val j = item as? JsonObject ?: return@mapNotNull null
            val uid = j.long("userId") ?: return@mapNotNull null
            ShareMemberItem(
                userId = uid,
                username = j.str("username").orEmpty(),
                displayName = j.str("displayName").orEmpty(),
                avatar = j.str("avatar").orEmpty(),
                permission = j.str("permission").orEmpty().ifBlank { "READ" },
            )
        }
    }

    /**
     * 加成员：POST /api/doc-shares/{sid}/members body {userId, permission}。
     * 只允许 OWNER/ADMIN（后端校验，客户端先按 myPermission 收起入口）。
     */
    suspend fun addShareMember(token: String, shareId: Long, userId: Long, permission: String): ShareMemberItem =
        withContext(Dispatchers.IO) {
            val body = """{"userId":$userId,"permission":${jsonStr(permission)}}"""
            val j = parse(authReq("POST", "/api/doc-shares/$shareId/members", token, body))
                ?: throw ApiError("添加成员响应异常")
            ShareMemberItem(
                userId = j.long("userId") ?: userId,
                username = j.str("username").orEmpty(),
                displayName = j.str("displayName").orEmpty(),
                avatar = j.str("avatar").orEmpty(),
                permission = j.str("permission").orEmpty().ifBlank { permission },
            )
        }

    /** 改权限：PUT /api/doc-shares/{sid}/members/{userId} body {permission} */
    suspend fun updateShareMember(token: String, shareId: Long, userId: Long, permission: String) {
        withContext(Dispatchers.IO) {
            val body = """{"permission":${jsonStr(permission)}}"""
            authReq("PUT", "/api/doc-shares/$shareId/members/$userId", token, body)
        }
    }

    /** 移除成员：DELETE /api/doc-shares/{sid}/members/{userId}（所有者不可移除） */
    suspend fun removeShareMember(token: String, shareId: Long, userId: Long) {
        withContext(Dispatchers.IO) {
            authReq("DELETE", "/api/doc-shares/$shareId/members/$userId", token)
        }
    }

    /** GET /api/doc-shares/users?keyword= → 可邀请用户（启用账号，最多 20 个） */
    suspend fun shareInviteUsers(token: String, keyword: String): List<InviteUserItem> =
        withContext(Dispatchers.IO) {
            val path = "/api/doc-shares/users?keyword=${java.net.URLEncoder.encode(keyword, "UTF-8")}"
            val e = runCatching { Json.parseToJsonElement(authReq("GET", path, token)) }.getOrNull()
                ?: return@withContext emptyList()
            (e as? JsonArray).orEmpty().mapNotNull { item ->
                val j = item as? JsonObject ?: return@mapNotNull null
                val id = j.long("id") ?: return@mapNotNull null
                InviteUserItem(
                    id = id,
                    username = j.str("username").orEmpty(),
                    displayName = j.str("displayName").orEmpty(),
                    avatar = j.str("avatar").orEmpty(),
                )
            }
        }

    /** GET /api/doc-shares/{sid}/docs/{docId}/public-link → 外部分享状态（仅能管理该文档的人可查） */
    suspend fun sharePublicLink(token: String, shareId: Long, docId: Long): SharePublicLink =
        withContext(Dispatchers.IO) {
            val j = parse(authReq("GET", "/api/doc-shares/$shareId/docs/$docId/public-link", token))
                ?: throw ApiError("分享状态响应异常")
            parsePublicLink(j, defaultEnabled = false)
        }

    /**
     * 生成 / 更新外部分享链接：`POST …/public-link {password?, expireDays?, noCopy}`。
     * password 空 = 无密码；expireDays 0/缺省 = 永久。
     */
    suspend fun saveSharePublicLink(
        token: String,
        shareId: Long,
        docId: Long,
        password: String?,
        expireDays: Int,
        noCopy: Boolean,
    ): SharePublicLink = withContext(Dispatchers.IO) {
        val body = buildString {
            append("{")
            append("\"password\":").append(if (password.isNullOrBlank()) "null" else jsonStr(password))
            append(",\"expireDays\":").append(expireDays.coerceAtLeast(0))
            append(",\"noCopy\":").append(noCopy)
            append("}")
        }
        val j = parse(authReq("POST", "/api/doc-shares/$shareId/docs/$docId/public-link", token, body))
            ?: throw ApiError("生成链接响应异常")
        parsePublicLink(j, defaultEnabled = true)
    }

    /** 撤销外部分享链接：`DELETE …/public-link`（历史链接立即 404） */
    suspend fun revokeSharePublicLink(token: String, shareId: Long, docId: Long) {
        withContext(Dispatchers.IO) {
            authReq("DELETE", "/api/doc-shares/$shareId/docs/$docId/public-link", token)
        }
    }

    /** GET /api/doc-shares/{sid}/storage → 空间用量与上限（capacity 为 null 表示不限） */
    suspend fun spaceStorage(token: String, shareId: Long): SpaceStorage = withContext(Dispatchers.IO) {
        val j = parse(authReq("GET", "/api/doc-shares/$shareId/storage", token))
            ?: throw ApiError("空间存储响应异常")
        SpaceStorage(
            used = j.long("used") ?: 0L,
            capacity = j.long("capacity"),
            hasCapacity = j.bool("hasCapacity") ?: (j.long("capacity") != null),
            ownerQuota = j.long("ownerQuota") ?: 0L,
            ownerUsed = j.long("ownerUsed") ?: 0L,
        )
    }

    /**
     * 新建团队空间：`POST /api/doc-shares {name, description, type, members, memberPermission}`。
     * 需 `SHARE_CREATE` 权限；非系统管理员且团队空间已达上限（2 个）时后端 403（此时引导用户提创建申请）。
     * 容量一律由后端默认 2GB（扩容走申请）。
     */
    suspend fun createShareSpace(
        token: String,
        name: String,
        description: String,
        memberIds: List<Long> = emptyList(),
        memberPermission: String = "WRITE",
    ): AgentSpace = withContext(Dispatchers.IO) {
        val members = memberIds.joinToString(",", "[", "]")
        val body = buildString {
            append("{")
            append("\"name\":").append(jsonStr(name)).append(",")
            append("\"description\":").append(jsonStr(description)).append(",")
            append("\"type\":\"TEAM\",")
            append("\"memberPermission\":").append(jsonStr(memberPermission)).append(",")
            append("\"members\":").append(members)
            append("}")
        }
        val o = parse(authReq("POST", "/api/doc-shares", token, body)) ?: throw ApiError("创建空间响应异常")
        val id = o.long("id") ?: throw ApiError("创建空间响应异常（缺少 id）")
        AgentSpace(
            id = id,
            name = o.str("name").orEmpty(),
            type = o.str("type").orEmpty(),
            description = o.str("description").orEmpty(),
            ownerId = o.long("ownerId") ?: 0L,
            ownerName = o.str("ownerName").orEmpty(),
            myPermission = o.str("myPermission").orEmpty(),
            memberCount = o.long("memberCount") ?: 0L,
            used = o.long("used") ?: 0L,
            capacity = o.long("capacity") ?: 0L,
        )
    }

    /**
     * 提交「团队空间创建申请」：`POST /api/doc-shares/create-requests`
     * `{requestedName, requestedDescription, requestedMembers, reason}`。
     * 用于已达空间数量上限 / 无直接创建权限时，由系统管理员在后台审批（通过后自动建空间）。
     */
    suspend fun submitShareCreateRequest(
        token: String,
        name: String,
        description: String,
        memberIds: List<Long> = emptyList(),
        reason: String = "",
    ) {
        withContext(Dispatchers.IO) {
            val members = memberIds.joinToString(",", "[", "]")
            val body = buildString {
                append("{")
                append("\"requestedName\":").append(jsonStr(name)).append(",")
                append("\"requestedDescription\":").append(jsonStr(description)).append(",")
                append("\"requestedMembers\":").append(members).append(",")
                append("\"reason\":").append(jsonStr(reason))
                append("}")
            }
            authReq("POST", "/api/doc-shares/create-requests", token, body)
        }
    }

    /** GET …/{sid}/capacity-requests → 该空间的扩容申请记录（倒序，含已处理） */
    suspend fun shareCapacityRequests(token: String, shareId: Long): List<CapacityRequestItem> =
        withContext(Dispatchers.IO) {
            val e = runCatching {
                Json.parseToJsonElement(authReq("GET", "/api/doc-shares/$shareId/capacity-requests", token))
            }.getOrNull() ?: throw ApiError("扩容申请列表响应异常")
            (e as? JsonArray ?: throw ApiError("扩容申请列表响应异常")).mapNotNull { item ->
                val j = item as? JsonObject ?: return@mapNotNull null
                val id = j.long("id") ?: return@mapNotNull null
                CapacityRequestItem(
                    id = id,
                    shareId = j.long("shareId") ?: shareId,
                    shareName = j.str("shareName").orEmpty(),
                    requestedCapacity = j.long("requestedCapacity") ?: 0L,
                    currentCapacity = j.long("currentCapacity") ?: 0L,
                    reason = j.str("reason").orEmpty(),
                    status = j.str("status").orEmpty().ifBlank { "PENDING" },
                    reviewComment = j.str("reviewComment").orEmpty(),
                    createdAt = j.str("createdAt").orEmpty(),
                )
            }
        }

    /**
     * 提交扩容申请：`POST …/{sid}/capacity-requests {requestedCapacity, reason}`。
     * 需空间管理员（OWNER/ADMIN）；`capacity=0` 表示申请「不限」。
     */
    suspend fun requestShareCapacity(token: String, shareId: Long, capacity: Long, reason: String) {
        withContext(Dispatchers.IO) {
            val body = "{\"requestedCapacity\":" + capacity + ",\"reason\":" + jsonStr(reason) + "}"
            authReq("POST", "/api/doc-shares/$shareId/capacity-requests", token, body)
        }
    }

    /**
     * 空间改名 / 改描述：`PUT /api/doc-shares/{id} {name, description}`。
     * 需要空间管理员（OWNER/ADMIN）；容量不在这里改（普通管理员走扩容申请，系统管理员用管理端直接设）。
     */
    suspend fun updateShareSpace(token: String, shareId: Long, name: String, description: String): AgentSpace =
        withContext(Dispatchers.IO) {
            val body = "{\"name\":" + jsonStr(name) + ",\"description\":" + jsonStr(description) + "}"
            val o = parse(authReq("PUT", "/api/doc-shares/$shareId", token, body))
                ?: throw ApiError("更新空间响应异常")
            AgentSpace(
                id = o.long("id") ?: shareId,
                name = o.str("name").orEmpty(),
                type = o.str("type").orEmpty(),
                description = o.str("description").orEmpty(),
                ownerId = o.long("ownerId") ?: 0L,
                ownerName = o.str("ownerName").orEmpty(),
                myPermission = o.str("myPermission").orEmpty(),
                memberCount = o.long("memberCount") ?: 0L,
                used = o.long("used") ?: 0L,
                capacity = o.long("capacity") ?: 0L,
            )
        }

    /**
     * 删除空间：`DELETE /api/doc-shares/{id}`（**仅空间所有者或系统管理员**）。
     * 后端会连带删除该空间全部文档、文档版本、成员与文档级权限 —— 不可恢复。
     */
    suspend fun deleteShareSpace(token: String, shareId: Long) {
        withContext(Dispatchers.IO) {
            authReq("DELETE", "/api/doc-shares/$shareId", token)
        }
    }

    /** GET …/docs/{docId}/links → 双链（out=本文引用，back=引用本文；resolved=false 为悬空） */
    suspend fun shareDocLinks(token: String, shareId: Long, docId: Long): DocLinks =
        withContext(Dispatchers.IO) {
            val o = parse(authReq("GET", "/api/doc-shares/$shareId/docs/$docId/links", token))
                ?: throw ApiError("双链响应异常")
            fun list(key: String): List<DocLinkItem> =
                (o[key] as? JsonArray).orEmpty().mapNotNull { e ->
                    val j = e as? JsonObject ?: return@mapNotNull null
                    val t = j.str("title") ?: return@mapNotNull null
                    DocLinkItem(
                        title = t,
                        resolved = j.bool("resolved") ?: false,
                        shareId = j.long("shareId"),
                        docId = j.long("docId"),
                        spaceName = j.str("spaceName"),
                    )
                }
            DocLinks(out = list("out"), back = list("back"))
        }

    /** GET …/docs/{docId}/members → 文档级权限（空间成员 + 各自有效权限） */
    suspend fun docMembers(token: String, shareId: Long, docId: Long): List<DocMemberItem> =
        withContext(Dispatchers.IO) {
            val e = runCatching {
                Json.parseToJsonElement(authReq("GET", "/api/doc-shares/$shareId/docs/$docId/members", token))
            }.getOrNull() ?: throw ApiError("文档权限响应异常")
            (e as? JsonArray ?: throw ApiError("文档权限响应异常")).mapNotNull { item ->
                val j = item as? JsonObject ?: return@mapNotNull null
                val uid = j.long("userId") ?: return@mapNotNull null
                DocMemberItem(
                    userId = uid,
                    username = j.str("username").orEmpty(),
                    displayName = j.str("displayName").orEmpty(),
                    avatar = j.str("avatar").orEmpty(),
                    spacePermission = j.str("spacePermission"),
                    permission = j.str("permission").orEmpty().ifBlank { "READ" },
                    override = j.bool("override") ?: false,
                )
            }
        }

    /**
     * 设置文档级权限：`PUT …/members/{userId} {permission}` → `{ok, cleared}`。
     * 传与空间权限相同的值 = 清除单独设置（`cleared=true`，回退继承）。
     */
    suspend fun setDocMember(
        token: String,
        shareId: Long,
        docId: Long,
        userId: Long,
        permission: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val body = """{"permission":${jsonStr(permission)}}"""
        val j = parse(authReq("PUT", "/api/doc-shares/$shareId/docs/$docId/members/$userId", token, body))
        j?.bool("cleared") ?: true
    }

    /** 清除文档级权限（回退继承空间权限）：`DELETE …/members/{userId}` */
    suspend fun clearDocMember(token: String, shareId: Long, docId: Long, userId: Long) {
        withContext(Dispatchers.IO) {
            authReq("DELETE", "/api/doc-shares/$shareId/docs/$docId/members/$userId", token)
        }
    }

    private fun parsePublicLink(j: JsonObject, defaultEnabled: Boolean): SharePublicLink {
        val tok = j.str("token").orEmpty()
        return SharePublicLink(
            enabled = j.bool("enabled") ?: (defaultEnabled && tok.isNotBlank()),
            token = tok,
            hasPassword = j.bool("hasPassword") ?: false,
            expireAt = j.str("expireAt").orEmpty(),
            noCopy = j.bool("noCopy") ?: false,
            views = j.long("views") ?: 0L,
        )
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

    /** PUT /api/admin/courses/chapters/{id} {content} → 管理员改章节正文（后端按字段跳空，其余字段不动） */
    suspend fun updateCourseChapter(token: String, chapterId: Long, content: String) {
        withContext(Dispatchers.IO) {
            authReq(
                "PUT", "/api/admin/courses/chapters/$chapterId", token,
                """{"content":${jsonStr(content)}}""",
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

    /** PUT …/docs/{docId}/tags {tags:[…]} → 更新后的文档（库里 tags 是逗号分隔字符串）。 */
    suspend fun updateShareDocTags(token: String, shareId: Long, docId: Long, tags: List<String>): SharedDocItem =
        withContext(Dispatchers.IO) {
            val arr = tags.map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(",", prefix = "[", postfix = "]") { jsonStr(it) }
            val o = parse(authReq("PUT", "/api/doc-shares/$shareId/docs/$docId/tags", token, """{"tags":$arr}"""))
                ?: throw ApiError("更新标签响应异常")
            parseSharedDoc(o) ?: throw ApiError("更新标签响应异常（缺少 id）")
        }

    /**
     * POST …/docs/{docId}/move {targetShareId} → 移动后的文档。
     * 需要空间/文档管理员或**文档所有者**；目标空间必须有写权限（配额仍算原所有者）。
     */
    suspend fun moveShareDoc(token: String, shareId: Long, docId: Long, targetShareId: Long): SharedDocItem =
        withContext(Dispatchers.IO) {
            val o = parse(
                authReq("POST", "/api/doc-shares/$shareId/docs/$docId/move", token, """{"targetShareId":$targetShareId}""")
            ) ?: throw ApiError("移动文档响应异常")
            parseSharedDoc(o) ?: throw ApiError("移动文档响应异常（缺少 id）")
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
