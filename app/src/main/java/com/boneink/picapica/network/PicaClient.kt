package com.boneink.picapica.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import android.util.Log
import com.boneink.picapica.BuildConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PicaClient(
    val config: SourceConfig,
    private val token: () -> String?,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false).build(),
    val backoff: ApiBackoff = ApiBackoff(),
    private val imageQuality: () -> String = { "original" },
) {
    private class TransportTrace { @Volatile var address: String? = null }
    private val tracedHttp = http.newBuilder().eventListener(object : EventListener() {
        override fun connectionAcquired(call: Call, connection: Connection) {
            call.request().tag(TransportTrace::class.java)?.address = connection.route().socketAddress.address?.hostAddress
        }
    }).build()
    private val singleAttemptHttp = tracedHttp.newBuilder().retryOnConnectionFailure(false).build()
    private fun diagnosticFailure(failure: PicaException, diagnostic: ApiDiagnostic): PicaException = failure.withDiagnostic(diagnostic).also {
        if (BuildConfig.DEBUG) Log.w("PicaPicaApi", it.safeDetails())
    }
    private fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl =
        config.apiBase.newBuilder().addPathSegments(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private suspend fun request(path: String, body: JSONObject? = null, query: Map<String, String> = emptyMap(), authToken: String? = token(), singleAttempt: Boolean = false): JSONObject {
        backoff.checkAllowed()
        val target = url(path, query)
        val trace = TransportTrace()
        val method = if (body == null) "GET" else "POST"
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val requestUri = target.encodedPath.removePrefix("/") + (target.encodedQuery?.let { "?$it" } ?: "")
        val builder = Request.Builder().url(target)
            .tag(TransportTrace::class.java, trace)
            .header("api-key", PicaProtocol.API_KEY)
            .header("accept", "application/vnd.picacomic.com.v1+json")
            .header("app-channel", "1")
            .header("time", timestamp).header("nonce", nonce)
            .header("signature", PicaProtocol.signature(requestUri, timestamp, nonce, method))
            .header("app-version", "2.2.1.3.3.4")
            .header("app-build-version", "45").header("app-platform", "android")
            .header("app-uuid", "defaultUuid").header("image-quality", imageQuality())
            .header("User-Agent", "okhttp/3.8.1")
        if (!authToken.isNullOrBlank()) builder.header("authorization", authToken)
        if (body != null) builder.post(body.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
        val response = try { (if (singleAttempt) singleAttemptHttp else tracedHttp).newCall(builder.build()).await() } catch (e: IOException) {
            throw diagnosticFailure(PicaException(PicaException.Kind.NETWORK, "连接失败，请检查网络后重试"), ApiDiagnostic(path, trace.address, null, null, null))
        }
        response.use {
            val content = it.body?.source()?.apply { request(4L * 1024 * 1024 + 1) }
            if (content == null || content.buffer.size > 4L * 1024 * 1024) {
                throw PicaException(PicaException.Kind.DATA, "接口返回的数据无法读取")
            }
            val json = try { JSONObject(content.readUtf8()) } catch (e: Exception) {
                if (it.code == 429) JSONObject() else throw PicaException(PicaException.Kind.SERVICE, "服务暂时不可用（HTTP ${it.code}）", httpStatus = it.code)
            }
            val code = json.optInt("code", it.code)
            if (!it.isSuccessful || code != 200) {
                val failure = ApiFailure.parse(it.code, json, path == "auth/sign-in", it.header("Retry-After"))
                failure.retryAfterSeconds?.let(backoff::blockFor)
                throw diagnosticFailure(failure, ApiDiagnostic(path, trace.address, it.handshake?.tlsVersion?.javaName,
                    it.protocol.toString(), it.header("Server-Time")?.toLongOrNull()?.minus(System.currentTimeMillis() / 1000)))
            }
            return json.optJSONObject("data") ?: JSONObject()
        }
    }

    suspend fun signIn(account: String, password: String): String {
        val value = request("auth/sign-in", JSONObject().put("email", account).put("password", password), authToken = null)
            .optString("token")
        if (value.isBlank()) throw PicaException(PicaException.Kind.DATA, "登录响应缺少凭据")
        return value
    }
    suspend fun profile(): UserProfile = parseUser(request("users/profile").getJSONObject("user"))
    suspend fun myComments(page: Int = 1): RecentCommentResults {
        val value = request("users/my-comments", query = mapOf("page" to page.toString())).getJSONObject("comments")
        val rows = value.getJSONArray("docs").objects().map { o ->
            val comic = o.optJSONObject("_comic")
            val comicId = comic?.optString("_id")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,128}")) }
            val hidden = o.optBoolean("hide")
            RecentComment(o.getString("_id"), if (hidden) "这条评论已隐藏" else o.optString("content"), o.optString("created_at"),
                o.optInt("likesCount").coerceAtLeast(0), replyCount(o), comicId,
                comic?.optString("title").orEmpty(), (o.optJSONObject("_parent")?.optString("_id") ?: o.optString("_parent")).takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,128}")) && it != "null" }, hidden, o.optBoolean("isLiked"))
        }
        return RecentCommentResults(rows, value.optInt("page", 1), value.optInt("pages", 1), value.optInt("total"))
    }
    private fun parseUser(o: JSONObject): UserProfile = UserProfile(
        o.optString("_id").ifBlank { o.optString("id") }, o.optString("name").ifBlank { "读者" },
        o.optJSONObject("avatar")?.let { avatar -> runCatching { media(avatar) }.getOrDefault("") }.orEmpty(),
        o.optString("slogan").takeUnless { it == "null" }.orEmpty(), o.optString("title").takeUnless { it == "null" }.orEmpty(),
        if (o.has("level") && !o.isNull("level")) o.optInt("level").coerceAtLeast(0) else null, if (o.has("exp") && !o.isNull("exp")) o.optLong("exp").coerceAtLeast(0) else null, o.optString("gender"),
        o.optString("created_at").takeUnless { it == "null" }.orEmpty(), o.optBoolean("verified"),
    )
    suspend fun comics(page: Int = 1): ComicResults = parseResults(request("comics", query = mapOf("s" to "dd", "page" to page.toString())).getJSONObject("comics"))
    suspend fun categories(): List<ComicCategory> = request("categories").getJSONArray("categories").objects()
        .filter { !it.optBoolean("isWeb") && it.optBoolean("active", true) && it.optString("title").isNotBlank() }
        .map { ComicCategory(it.optString("_id").ifBlank { digest(it.getString("title").toByteArray()).take(16) }, it.getString("title"), it.optJSONObject("thumb")?.let(::media) ?: "") }
    suspend fun category(title: String, sort: ComicSort = ComicSort.UPDATED, page: Int = 1): ComicResults =
        parseResults(request("comics", query = mapOf("c" to title, "s" to sort.code, "page" to page.toString())).getJSONObject("comics"))
    suspend fun ranking(period: RankingPeriod): List<Comic> = request("comics/leaderboard", query = mapOf("tt" to period.code, "ct" to "VC"))
        .getJSONArray("comics").objects().map(::parseComic)
    suspend fun favourites(page: Int = 1, sort: ComicSort = ComicSort.UPDATED): ComicResults =
        parseResults(request("users/favourite", query = mapOf("s" to sort.code, "page" to page.toString())).getJSONObject("comics"))
    suspend fun toggleFavourite(id: String): Boolean = when (request("comics/${segment(id)}/favourite", JSONObject()).optString("action")) {
        "favourite" -> true
        "un_favourite", "unfavourite" -> false
        else -> comic(id).isFavourite
    }
    suspend fun comments(comicId: String, page: Int = 1): CommentResults =
        parseComments(request("comics/${segment(comicId)}/comments", query = mapOf("page" to page.toString())))
    suspend fun replies(commentId: String, page: Int = 1): CommentResults =
        parseComments(request("comments/${segment(commentId)}/childrens", query = mapOf("page" to page.toString())))
    suspend fun postComment(comicId: String, content: String) {
        request("comics/${segment(comicId)}/comments", commentBody(content), singleAttempt = true)
    }
    suspend fun postReply(commentId: String, content: String) {
        request("comments/${segment(commentId)}", commentBody(content), singleAttempt = true)
    }
    suspend fun toggleCommentLike(commentId: String): Boolean = when (request("comments/${segment(commentId)}/like", JSONObject(), singleAttempt = true).optString("action")) {
        "like" -> true
        "unlike", "un_like" -> false
        else -> throw PicaException(PicaException.Kind.DATA, "点赞状态暂未确认，请刷新评论")
    }
    private fun commentBody(content: String): JSONObject {
        val text = content.trim()
        require(text.isNotEmpty()) { "请先写下评论" }
        require(text.length <= 1000) { "评论最多 1000 字" }
        return JSONObject().put("content", text)
    }
    private fun parseComments(data: JSONObject): CommentResults {
        val obj = data.getJSONObject("comments")
        val pinned = data.optJSONArray("topComments")?.objects()?.map { parseComment(it).copy(pinned = true) } ?: emptyList()
        return CommentResults(obj.getJSONArray("docs").objects().map(::parseComment), obj.optInt("page", 1),
            obj.optInt("pages", 1), obj.optInt("total"), pinned)
    }
    private fun replyCount(o: JSONObject): Int = (if (o.has("totalComments") && !o.isNull("totalComments"))
        o.optInt("totalComments") else o.optInt("commentsCount")).coerceAtLeast(0)
    private fun parseComment(o: JSONObject): ComicComment {
        val user = o.optJSONObject("_user")?.let(::parseUser)
        return ComicComment(o.getString("_id"), user?.name ?: "已注销读者",
            if (o.optBoolean("hide")) "这条评论已隐藏" else o.optString("content"), o.optString("created_at"),
            o.optInt("likesCount").coerceAtLeast(0), replyCount(o), o.optBoolean("isLiked"), user = user,
            parentId = (o.optJSONObject("_parent")?.optString("_id") ?: o.optString("_parent")).takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,128}")) && it != "null" }, hidden = o.optBoolean("hide"))
    }
    suspend fun search(keyword: String, page: Int = 1, sort: SearchSort = SearchSort.NEWEST, categories: Set<String> = emptySet()): ComicResults {
        val body = JSONObject().put("keyword", keyword).put("sort", sort.code)
        val values = categories.filter(String::isNotBlank).distinct().sorted()
        if (values.isNotEmpty()) body.put("categories", JSONArray(values))
        return parseResults(request("comics/advanced-search", body, mapOf("page" to page.toString())).getJSONObject("comics"))
    }
    suspend fun comic(id: String): Comic = request("comics/${segment(id)}").let { data ->
        parseComic(data.getJSONObject("comic")).let { it.copy(isFavourite = data.optBoolean("isFavourite", it.isFavourite)) }
    }
    suspend fun chapters(id: String): List<Chapter> {
        val all = mutableListOf<Chapter>()
        var page = 1
        do {
            val obj = request("comics/${segment(id)}/eps", query = mapOf("page" to page.toString())).getJSONObject("eps")
            obj.getJSONArray("docs").objects().forEach { all += Chapter(it.getString("_id"), it.getString("title"), it.getInt("order")) }
            val count = obj.optInt("pages", 1)
            page++
        } while (page <= count.coerceAtMost(1000))
        return all.distinctBy { it.order }.sortedBy { it.order }
    }
    suspend fun pages(id: String, order: Int): List<PageImage> {
        val all = mutableListOf<PageImage>()
        var page = 1
        do {
            val obj = request("comics/${segment(id)}/order/$order/pages", query = mapOf("page" to page.toString())).getJSONObject("pages")
            obj.getJSONArray("docs").objects().forEach { all += PageImage(it.getString("_id"), all.size, media(it.getJSONObject("media"))) }
            val count = obj.optInt("pages", 1)
            page++
        } while (page <= count.coerceAtMost(1000))
        if (all.isEmpty()) throw PicaException(PicaException.Kind.DATA, "本章暂时没有可读取的图片")
        return all
    }
    private fun parseResults(obj: JSONObject) = ComicResults(obj.getJSONArray("docs").objects().map(::parseComic), obj.optInt("page", 1), obj.optInt("pages", 1))
    private fun parseComic(o: JSONObject) = Comic(o.getString("_id"), o.getString("title"), o.optString("author"),
        o.optJSONObject("thumb")?.let(::media) ?: "", o.optString("description"),
        o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        o.optInt("epsCount"), o.optBoolean("finished"), o.optBoolean("allowDownload", true), o.optBoolean("isFavourite"), o.optBoolean("allowComment", true))
    private fun media(o: JSONObject): String {
        val server = o.getString("fileServer")
        val path = o.getString("path")
        val base = server.toHttpUrl()
        val full = base.newBuilder().addPathSegment("static").addPathSegments(path.removePrefix("/")).build().toString()
        return config.validateMedia(full)
    }
    private fun segment(id: String): String {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { "作品标识无效" }
        return id
    }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
        }
    })
}
