package com.boneink.picapica.network

import com.boneink.picapica.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest

data class SourceConfig(val apiBase: HttpUrl = "https://picaapi.picacomic.com/".toHttpUrl()) {
    init {
        require(apiBase.isHttps || (BuildConfig.DEBUG && apiBase.host in setOf("127.0.0.1", "localhost", "10.0.2.2"))) {
            "接口必须使用 HTTPS"
        }
        require(apiBase.username.isEmpty() && apiBase.password.isEmpty())
        require(apiBase.encodedQuery == null && apiBase.fragment == null)
    }
    val key: String = digest(apiBase.toString().toByteArray()).take(16)
    fun comicKey(id: String) = "$key:$id"
    fun chapterKey(id: String, order: Int) = "${comicKey(id)}:$order"
    fun validateMedia(url: String): String {
        val parsed = url.toHttpUrl()
        require(parsed.isHttps || (BuildConfig.DEBUG && parsed.host in setOf("127.0.0.1", "localhost", "10.0.2.2"))) {
            "图片链接必须使用 HTTPS"
        }
        return parsed.toString()
    }
}

fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

data class Comic(
    val id: String,
    val title: String,
    val author: String = "",
    val coverUrl: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val chapterCount: Int = 0,
    val finished: Boolean = false,
    val allowDownload: Boolean = true,
    val isFavourite: Boolean = false,
    val allowComment: Boolean = true,
    val categories: List<String> = emptyList(),
    val chineseTeam: String = "",
    val aiGenerated: Boolean = false,
    val completionKnown: Boolean = true,
    val tagsAvailable: Boolean = tags.isNotEmpty(),
    val categoriesAvailable: Boolean = categories.isNotEmpty(),
    val pagesCount: Int = 0,
    val likes: Long? = null,
    val views: Long? = null,
)
data class ComicCategory(val id: String, val title: String, val coverUrl: String = "")
object OfficialDiscussion {
    const val COMIC_ID = "5822a6e3ad7ede654696e482"
    val category = ComicCategory("official-discussion", "官方评论区")
}
enum class ComicSort(val code: String, val title: String) { UPDATED("ua", "最近更新"), NEWEST("dd", "最新上架"), POPULAR("ld", "最多喜欢"), VIEWED("vd", "最多浏览") }
enum class SearchSort(val code: String, val title: String) { NEWEST("dd", "新到旧"), OLDEST("da", "旧到新"), POPULAR("ld", "最多喜欢"), VIEWED("vd", "最多浏览") }
enum class RankingPeriod(val code: String, val title: String) { DAY("H24", "日榜"), WEEK("D7", "周榜"), MONTH("D30", "月榜") }
data class Chapter(val id: String, val title: String, val order: Int)
data class PageImage(val id: String, val index: Int, val url: String, val localPath: String? = null, val width: Int = 0, val height: Int = 0)
data class ComicResults(val items: List<Comic>, val page: Int, val pages: Int)
data class UserProfile(
    val id: String, val name: String, val avatarUrl: String = "", val slogan: String = "", val title: String = "",
    val level: Int? = null, val exp: Long? = null, val gender: String = "", val createdAt: String = "", val verified: Boolean = false,
)
data class RecentComment(
    val id: String, val content: String, val createdAt: String, val likes: Int, val replies: Int,
    val comicId: String? = null, val comicTitle: String = "", val parentId: String? = null, val hidden: Boolean = false, val isLiked: Boolean = false,
)
data class RecentCommentResults(val items: List<RecentComment>, val page: Int, val pages: Int, val total: Int)
class PicaException(
    val kind: Kind, override val message: String,
    val httpStatus: Int? = null, val apiCode: Int? = null, val errorCode: String? = null,
    val retryAfterSeconds: Long? = null,
    val diagnostic: ApiDiagnostic? = null,
) : Exception(message) {
    enum class Kind { AUTH, CREDENTIALS, RATE_LIMIT, PROTOCOL, NETWORK, SERVICE, DATA }
    /** Deliberately excludes raw responses, request bodies, account names and tokens. */
    fun safeDetails(): String = listOfNotNull("类型：${kind.name}", httpStatus?.let { "HTTP：$it" },
        apiCode?.let { "API：$it" }, errorCode?.let { "服务错误码：$it" }, diagnostic?.describe()).joinToString("\n")
    fun withDiagnostic(value: ApiDiagnostic) = PicaException(kind, message, httpStatus, apiCode, errorCode, retryAfterSeconds, value)
}

/** Contains transport metadata only, never request/response bodies or authentication headers. */
data class ApiDiagnostic(val path: String, val address: String?, val tls: String?, val protocol: String?, val clockSkewSeconds: Long?) {
    fun describe() = listOfNotNull("接口：$path", address?.let { "连接地址：$it" }, tls?.let { "TLS：$it" },
        protocol?.let { "传输：$it" }, clockSkewSeconds?.let { "服务时间差：${it}秒" }).joinToString("\n")
}

data class ComicComment(
    val id: String, val author: String, val content: String, val createdAt: String = "",
    val likes: Int = 0, val replies: Int = 0, val isLiked: Boolean = false, val pinned: Boolean = false, val user: UserProfile? = null, val parentId: String? = null, val hidden: Boolean = false,
)
data class CommentResults(val items: List<ComicComment>, val page: Int, val pages: Int, val total: Int, val pinned: List<ComicComment> = emptyList())
