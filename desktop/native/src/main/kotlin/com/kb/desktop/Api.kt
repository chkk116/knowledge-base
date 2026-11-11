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
    val category: String?,
    val updatedAt: String?,
    val views: Int,
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

    /** GET /api/docs/{id} → 文章详情（content 为 HTML） */
    suspend fun doc(token: String, id: Long): DocDetail = withContext(Dispatchers.IO) {
        val o = parse(getAuth("/api/docs/$id", token)) ?: throw ApiError("文章响应异常")
        DocDetail(
            id = id,
            title = o.str("title").orEmpty(),
            content = o.str("content").orEmpty(),
            ownerName = o.str("ownerName"),
            category = o.str("category"),
            updatedAt = o.str("updatedAt"),
            views = o.int("views") ?: 0,
        )
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
        val o = parse(get("/api/public/app-version?channel=$channel")) ?: throw ApiError("版本信息响应异常")
        AppVersionInfo(
            versionCode = o.int("versionCode") ?: 0,
            versionName = o.str("versionName").orEmpty(),
            url = o.str("url").orEmpty(),
            notes = o.str("notes").orEmpty(),
            checksum = o.str("checksum").orEmpty(),
            force = o.bool("force") ?: false,
        )
    }
}

/** HTML 串粗略去标签，仅作列表摘要兜底。 */
private fun plainText(html: String): String =
    html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(80)
