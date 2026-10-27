package com.boneink.picapica

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.boneink.picapica.network.PicaProtocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic non-explicit artwork and accounts. Never included in the application APK. */
class FixtureServer : AutoCloseable {
    val server = MockWebServer()
    val metadataFixture = AtomicBoolean(false)
    val homePages = AtomicInteger(1)
    val inlinePages = AtomicBoolean(false)
    val failInlineOnce = AtomicBoolean(false)
    val failMyCommentsOnce = AtomicBoolean(false)
    val myCommentsEmpty = AtomicBoolean(false)
    val failAvatar = AtomicBoolean(false)
    val failCoverOnce = AtomicBoolean(false)
    val failSecondImageOnce = AtomicBoolean(false)
    val favourite = AtomicBoolean(false)
    val failNextComment = AtomicBoolean(false)
    val failNestedReplyOnce = AtomicBoolean(false)
    val nestedRepliesPosted = CopyOnWriteArrayList<String>()
    val boardRepliesPosted = CopyOnWriteArrayList<String>()
    val unavailableHistory = AtomicBoolean(false)
    val commentsPosted = CopyOnWriteArrayList<String>()
    val repliesPosted = CopyOnWriteArrayList<String>()
    val commentLiked = AtomicBoolean(false)
    val imageCounts = ConcurrentHashMap<String, AtomicInteger>()
    val paths = CopyOnWriteArrayList<String>()
    val qualityHeaders = CopyOnWriteArrayList<String>()
    val searchRequests = CopyOnWriteArrayList<JSONObject>()
    val filteredSearch = AtomicBoolean(false)
    val failSearchOnce = AtomicBoolean(false)
    val violations = CopyOnWriteArrayList<String>()
    val image = createImage()
    private var stopped = false

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                paths += path
                if (path.startsWith("/static/")) {
                    if (request.getHeader("authorization") != null) { violations += "token leaked to image request"; return json(403, JSONObject()) }
                    imageCounts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                    if (path.endsWith("avatar.png") && failAvatar.get()) return MockResponse().setResponseCode(503)
                    if (path.endsWith("cover.png") && failCoverOnce.compareAndSet(true, false)) return MockResponse().setResponseCode(503)
                    if (path.endsWith("page2.png") && failSecondImageOnce.compareAndSet(true, false)) return MockResponse().setResponseCode(503)
                    return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(image))
                }
                val signature = PicaProtocol.signature(request.path!!, request.getHeader("time") ?: "", request.getHeader("nonce") ?: "", request.method!!)
                if (signature != request.getHeader("signature")) { violations += "bad request signature"; return json(403, JSONObject()) }
                if (path == "/auth/sign-in") {
                    val data = JSONObject(request.body.readUtf8())
                    return if (data.optString("email") == ACCOUNT && data.optString("password") == PASSWORD)
                        json(200, JSONObject().put("token", TOKEN)) else MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                            .setBody("""{"code":400,"message":"invalid email or password"}""")
                }
                if (request.getHeader("authorization") != TOKEN) return json(401, JSONObject())
                return when (path) {
                    "/comics/5822a6e3ad7ede654696e482/comments" -> json(200, JSONObject().put("comments", page(JSONArray()
                        .put(comment("boardRoot", "官方讨论板测试评论", 1)))).put("topComments", JSONArray()))
                    "/comments/boardRoot/childrens" -> {
                        val docs = JSONArray().put(comment("boardReply", "讨论板测试回复").put("_parent", "boardRoot"))
                        boardRepliesPosted.forEachIndexed { i, text -> docs.put(comment("boardPosted$i", text).put("_parent", "boardRoot")) }
                        json(200, JSONObject().put("comments", page(docs)))
                    }
                    "/comments/boardRoot" -> { boardRepliesPosted += JSONObject(request.body.readUtf8()).getString("content"); json(200, JSONObject()) }
                    "/comics/comic1/comments" -> if (request.method == "POST") {
                        if (failNextComment.compareAndSet(true, false)) json(503, JSONObject()) else {
                            commentsPosted += JSONObject(request.body.readUtf8()).getString("content")
                            json(200, JSONObject())
                        }
                    } else {
                        val number = request.requestUrl!!.queryParameter("page")!!.toInt()
                        val docs = if (number == 1) JSONArray().put(comment("c1", "光影很有画面感。", 1 + repliesPosted.size)) else JSONArray().put(comment("c2", "期待后续故事。"))
                        if (number == 1) commentsPosted.forEachIndexed { index, content -> docs.put(comment("posted$index", content)) }
                        json(200, JSONObject().put("comments", page(docs, number, 2)).put("topComments", JSONArray().put(comment("pinned", "欢迎分享阅读感受。"))))
                    }
                    "/comments/c1/childrens" -> {
                        val docs = JSONArray().put(comment("reply1", "我也喜欢这一页。", nestedRepliesPosted.size).put("_parent", "c1"))
                        repliesPosted.forEachIndexed { index, content -> docs.put(comment("replyPosted$index", content)) }
                        json(200, JSONObject().put("comments", page(docs)))
                    }
                    "/comments/reply1/childrens" -> {
                        val docs = JSONArray()
                        nestedRepliesPosted.forEachIndexed { index, content -> docs.put(comment("nested$index", content).put("_parent", "reply1")) }
                        json(200, JSONObject().put("comments", page(docs)))
                    }
                    "/comments/reply1" -> {
                        if (failNestedReplyOnce.compareAndSet(true, false)) json(503, JSONObject())
                        else { nestedRepliesPosted += JSONObject(request.body.readUtf8()).getString("content"); json(200, JSONObject()) }
                    }
                    "/comments/nested0/childrens" -> json(200, JSONObject().put("comments", page(JSONArray())))
                    "/comments/mine1/childrens", "/comments/mine2/childrens", "/comments/mine4/childrens", "/comments/mine5/childrens" -> {
                        if (unavailableHistory.get()) json(404, JSONObject())
                        else if (failInlineOnce.compareAndSet(true, false)) json(503, JSONObject())
                        else {
                            val number = request.requestUrl!!.queryParameter("page")?.toInt() ?: 1
                            val reply = if (inlinePages.get() && number == 2) comment("historyReply2", "分页回复。") else comment("historyReply", "这是历史评论下的回复。", if (inlinePages.get()) 1 else 0)
                            json(200, JSONObject().put("comments", page(JSONArray().put(reply), number, if (inlinePages.get()) 2 else 1)))
                        }
                    }
                    "/comments/historyReply/childrens" -> json(200, JSONObject().put("comments", page(JSONArray().put(comment("deepReply", "楼中楼回复内容。").put("_parent", "historyReply")))))
                    "/comments/c1" -> { repliesPosted += JSONObject(request.body.readUtf8()).getString("content"); json(200, JSONObject()) }
                    "/comments/c1/like" -> { val liked = !commentLiked.get(); commentLiked.set(liked); json(200, JSONObject().put("action", if (liked) "like" else "unlike")) }
                    "/users/profile" -> json(200, JSONObject().put("user", user("fixture-user", "故事收藏家")))
                    "/users/my-comments" -> {
                        if (failMyCommentsOnce.compareAndSet(true, false)) json(503, JSONObject())
                        else {
                            val number = request.requestUrl!!.queryParameter("page")?.toInt() ?: 1
                            val docs = JSONArray()
                            if (!myCommentsEmpty.get()) {
                                val range = if (number == 1) 1..4 else 4..5
                                range.forEach { index -> docs.put(JSONObject().put("_id", "mine$index").put("content", "第 $index 次阅读感受：期待接下来的故事。")
                                    .put("created_at", "2026-10-27T00:00:00Z").put("likesCount", index).put("totalComments", 1).put("commentsCount", 0)
                                    .put("_parent", if (index == 2) "c1" else JSONObject.NULL)
                                    .put("_comic", if (index == 3) JSONObject.NULL else JSONObject().put("_id", "comic1").put("title", "雨后观测站"))) }
                            }
                            json(200, JSONObject().put("comments", page(docs, number, if (myCommentsEmpty.get()) 1 else 2).put("total", if (myCommentsEmpty.get()) 0 else 5)))
                        }
                    }
                    "/categories" -> json(200, JSONObject().put("categories", JSONArray()
                        .put(JSONObject().put("_id", "daily").put("title", "日常").put("thumb", media("cover.png")))
                        .put(JSONObject().put("title", "全年龄"))))
                    "/comics/leaderboard" -> json(200, JSONObject().put("comics", JSONArray().put(comic())))
                    "/users/favourite" -> json(200, JSONObject().put("comics", page(if (favourite.get()) JSONArray().put(comic()) else JSONArray())))
                    "/comics/comic1/favourite" -> {
                        val selected = !favourite.get(); favourite.set(selected)
                        json(200, JSONObject().put("action", if (selected) "favourite" else "un_favourite"))
                    }
                    "/comics" -> {
                        val category = request.requestUrl!!.queryParameter("c")
                        val number = request.requestUrl!!.queryParameter("page")?.toIntOrNull() ?: 1
                        val item = if (number == 2) comic().put("_id", "comic2").put("title", "街角来信") else comic()
                        json(200, JSONObject().put("comics", page(JSONArray().put(item), number, if (category != null) 2 else homePages.get())))
                    }
                    "/comics/advanced-search" -> {
                        val body = JSONObject(request.body.readUtf8())
                        val number = request.requestUrl!!.queryParameter("page")!!.toInt()
                        searchRequests += JSONObject(body.toString()).put("page", number)
                        if (failSearchOnce.compareAndSet(true, false)) json(503, JSONObject()) else {
                            val keyword = body.optString("keyword")
                            val results = if (filteredSearch.get() && keyword != "无结果") JSONArray().put(if (number == 1) comic() else comic().put("_id", "comic2").put("title", "街角来信"))
                                else if (keyword == "雨后") JSONArray().put(comic()) else JSONArray()
                            json(200, JSONObject().put("comics", page(results, number, if (filteredSearch.get() && keyword != "无结果") 2 else 1)))
                        }
                    }
                    "/comics/comic1" -> json(200, JSONObject().put("comic", comic()).put("isFavourite", favourite.get()))
                    "/comics/comic1/eps" -> json(200, JSONObject().put("eps", page(JSONArray()
                        .put(JSONObject().put("_id", "chapter1").put("order", 1).put("title", "第 1 话 · 来信"))
                        .put(JSONObject().put("_id", "chapter2").put("order", 2).put("title", "第 2 话 · 雨停之后")))))
                    "/comics/comic1/order/1/pages", "/comics/comic1/order/2/pages" -> {
                        qualityHeaders += request.getHeader("image-quality").orEmpty()
                        val docs = JSONArray()
                        for (i in 1..3) docs.put(JSONObject().put("_id", "page$i").put("media", media("page$i.png")))
                        json(200, JSONObject().put("pages", page(docs)))
                    }
                    else -> json(404, JSONObject())
                }
            }
        }
        server.start()
    }
    private fun user(id: String, name: String) = JSONObject().put("_id", id).put("name", name)
        .put("avatar", media("avatar.png")).put("slogan", "把每一次阅读，留在记忆里。")
        .put("title", "故事收藏家").put("level", 12).put("exp", 12000).put("gender", "bot").put("created_at", "2020-01-01T00:00:00Z")
    private fun comment(id: String, content: String, replies: Int = 0) = JSONObject().put("_id", id).put("content", content)
        .put("_user", user("reader1", "阅读同伴")).put("created_at", "2026-10-27T00:00:00.000Z")
        .put("totalComments", replies).put("commentsCount", 0).put("likesCount", if (id == "c1" && commentLiked.get()) 3 else 2)
        .put("isLiked", id == "c1" && commentLiked.get())
    fun comic(): JSONObject = JSONObject().put("_id", "comic1").put("title", "雨后观测站")
        .put("author", "PicaPica 联调作品").put("description", "一封没有署名的来信，让平凡的日常有了新的方向。这是用于验证阅读与下载的原创示例。")
        .put("thumb", media("cover.png")).put("tags", JSONArray().put("日常").put("幻想"))
        .put("epsCount", 2).put("finished", false).put("allowDownload", true).apply {
            if (metadataFixture.get()) put("categories", JSONArray().put("日常").put("生肉")).put("pagesCount", 30).put("chineseTeam", "").put("tags", JSONArray().put("幻想").put("AI生成")).put("likesCount", 42)
        }
    private fun media(path: String) = JSONObject().put("fileServer", server.url("/").toString()).put("path", path)
    private fun page(docs: JSONArray, number: Int = 1, count: Int = 1) = JSONObject().put("docs", docs).put("page", number).put("pages", count).put("total", docs.length())
    private fun json(code: Int, data: JSONObject) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("code", code).put("data", data).toString())
    override fun close() { if (!stopped) { stopped = true; server.shutdown() } }
    companion object {
        const val ACCOUNT = "reader@example.invalid"
        const val PASSWORD = "fixture-password-only"
        const val TOKEN = "fixture-token-not-a-real-account"
        private fun createImage(): ByteArray {
            val bitmap = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(Color.rgb(56, 79, 89))
            paint.color = Color.rgb(228, 220, 182); canvas.drawCircle(120f, 160f, 52f, paint)
            paint.color = Color.rgb(34, 58, 71); canvas.drawRect(260f, 310f, 430f, 800f, paint)
            paint.color = Color.rgb(135, 162, 164); paint.strokeWidth = 2f
            repeat(11) { i -> canvas.drawLine(260f, 330f + i * 40, 430f, 330f + i * 40, paint) }
            paint.color = Color.WHITE; paint.textSize = 32f; canvas.drawText("AFTER THE RAIN", 36f, 610f, paint)
            paint.textSize = 20f; canvas.drawText("PicaPica / integration artwork", 36f, 652f, paint)
            return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
        }
    }
}
