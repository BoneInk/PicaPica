package com.boneink.picapica.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Website protocol observed on fulovehouse.net, 2026-11-02. Never sends PicACG headers. */
class XiangxiangClient(
    override val config: SourceConfig,
    private val token: () -> String?,
    http: OkHttpClient = OkHttpClient(),
    override val backoff: ApiBackoff = ApiBackoff(),
    persistSession: (String) -> Unit = {},
    mirrorBases: List<HttpUrl> = XiangxiangOrigins.mirrorsFor(config.apiBase),
) : ComicSource {
    override val capabilities = SourceCapabilities(registration = true, editProfile = true, editSlogan = false, recentComments = true, recentCommentsTitle = "历史留言",
        officialDiscussion = false, searchCategories = false, searchSorts = listOf(SearchSort.NEWEST),
        comicSorts = listOf(ComicSort.UPDATED), rankingPeriods = RankingPeriod.entries,
        rankingTitles = mapOf(RankingPeriod.DAY to "人气榜", RankingPeriod.WEEK to "收藏榜", RankingPeriod.MONTH to "搜索榜"), homepage = true)
    private val origins = XiangxiangOrigins(config.apiBase, mirrorBases)
    private val cookies = SourceCookies(origins, token, persistSession)
    private val client = http.newBuilder().cookieJar(cookies).followRedirects(false).retryOnConnectionFailure(false).build()
    private val chapterCache = java.util.concurrent.ConcurrentHashMap<String, List<Chapter>>()
    private data class CommentContext(val comicId: String, val parent: String? = null)
    private val commentContexts = java.util.concurrent.ConcurrentHashMap<String, CommentContext>()
    private val numeric = Regex("[0-9]{1,20}")
    private fun id(value: String): String = value.also { require(it.matches(numeric)) { "作品或评论标识无效" } }
    private fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl = cookies.base().newBuilder()
        .addPathSegments(path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
    private suspend fun bytes(path: String, query: Map<String, String> = emptyMap(), form: Map<String, String>? = null): ByteArray {
        return fetch(url(path, query), form)
    }
    private suspend fun fetch(target: HttpUrl, form: Map<String, String>? = null, redirects: Int = 0, upload: RequestBody? = null): ByteArray {
        backoff.checkAllowed()
        val request = Request.Builder().url(target).header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .header("Referer", XiangxiangOrigins.baseOf(target).toString())
        if (upload != null) request.post(upload)
        else if (form != null) request.post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())
        return try {
            client.newCall(request.build()).awaitSource().use { response ->
                when {
                    response.code == 429 -> { backoff.blockFor(response.header("Retry-After")?.toLongOrNull() ?: 30)
                        throw PicaException(PicaException.Kind.RATE_LIMIT, "请求过于频繁，请稍后重试", httpStatus = 429) }
                    response.code == 401 -> throw PicaException(PicaException.Kind.AUTH, "香香腐宅登录已失效，请重新登录")
                    response.code in setOf(301, 302, 303, 307, 308) -> {
                        val next = response.header("Location")?.let(target::resolve)
                        if (next == null || !origins.accepts(next))
                            throw PicaException(PicaException.Kind.SERVICE, "香香腐宅返回了无法识别的跳转，请稍后重试", httpStatus = response.code)
                        if (next.encodedPath.contains("/auth/login") && form == null)
                            throw PicaException(PicaException.Kind.AUTH, "香香腐宅登录已失效，请重新登录")
                        if (upload != null && response.code in setOf(302, 303) && XiangxiangOrigins.baseOf(next) == XiangxiangOrigins.baseOf(target)
                            && next.encodedPath in setOf("/home/user/index", "/home/user/index.html")) {
                            response.close()
                            return fetch(next)
                        }
                        if (form != null || upload != null) {
                            // A new login origin needs a fresh CAPTCHA. Never replay a password or a comment POST.
                            if (target.encodedPath == "/home/auth/login.html" && form?.containsKey("password") == true) {
                                cookies.selectBase(next)
                                throw PicaException(PicaException.Kind.CREDENTIALS, "登录线路已切换，请使用新的验证码重新登录")
                            }
                            throw PicaException(PicaException.Kind.SERVICE, "请求发生跳转，请刷新后重试", httpStatus = response.code)
                        }
                        if (redirects >= 3) throw PicaException(PicaException.Kind.SERVICE, "香香腐宅跳转次数过多，请稍后重试")
                        response.close()
                        return fetch(next, redirects = redirects + 1)
                    }
                    !response.isSuccessful -> throw PicaException(PicaException.Kind.SERVICE, "香香腐宅暂时无法连接（HTTP ${response.code}）", httpStatus = response.code)
                }
                val body = response.body?.source() ?: throw PicaException(PicaException.Kind.DATA, "香香腐宅返回了空数据")
                body.request(MAX_BODY + 1)
                if (body.buffer.size > MAX_BODY) throw PicaException(PicaException.Kind.DATA, "香香腐宅返回的数据过大")
                body.readByteArray().also { cookies.selectBase(target) }
            }
        } catch (e: IOException) { throw PicaException(PicaException.Kind.NETWORK, "无法连接香香腐宅，请检查网络后重试") }
    }
    private suspend fun json(path: String, query: Map<String, String> = emptyMap(), form: Map<String, String>? = null): JSONObject {
        val content = bytes(path, query, form).toString(Charsets.UTF_8)
        return try { JSONObject(content) } catch (_: Exception) { throw PicaException(PicaException.Kind.DATA, "香香腐宅接口格式已变化，请稍后重试") }
    }
    private suspend fun document(path: String): Document = Jsoup.parse(bytes(path).toString(Charsets.UTF_8), cookies.base().toString())
    private fun checked(value: JSONObject): JSONObject {
        val message = plain(value.optString("msg").ifBlank { value.optString("info") })
        if (message.contains("登录") || message.contains("登入")) throw PicaException(PicaException.Kind.AUTH, "请重新登录香香腐宅")
        val result = value.optJSONObject("result")
        val empty = result?.optBoolean("lastPage") == true && result.optJSONArray("list")?.length() == 0
        if (value.has("succ") && !value.optBoolean("succ") && !empty) throw PicaException(PicaException.Kind.SERVICE, message.ifBlank { "香香腐宅请求未成功" }.take(160))
        return value
    }
    override suspend fun captcha(): ByteArray = bytes("captcha.html", mapOf("_" to System.nanoTime().toString()))
    override suspend fun signIn(account: String, password: String, captcha: String): String {
        require(account.isNotBlank() && password.isNotEmpty()) { "请填写账号和密码" }
        require(captcha.isNotBlank()) { "请填写验证码" }
        val result = json("home/auth/login.html", form = mapOf("username" to account, "password" to password, "vfycode" to captcha.trim(), "type" to "login"))
        if (result.optString("status") != "0") throw PicaException(PicaException.Kind.CREDENTIALS,
            plain(result.optString("info")).ifBlank { "登录未成功，请检查账号和验证码" }.take(160))
        val session = cookies.snapshot(account)
        if (JSONObject(session).getJSONArray("cookies").length() == 0) throw PicaException(PicaException.Kind.DATA, "登录响应缺少网站会话")
        return session
    }
    override suspend fun profile(): UserProfile {
        val doc = document("home/user/index")
        // The authenticated user center also embeds a hidden login form. Only a dedicated
        // authentication page (or the HTTP redirect handled above) means the session expired.
        if (doc.title().contains("用户认证"))
            throw PicaException(PicaException.Kind.AUTH, "香香腐宅登录已失效，请重新登录")
        val account = cookies.account().ifBlank { "香香读者" }
        val block = doc.selectFirst(".user-block")
        val name = block?.selectFirst(".user > .name")?.clone()?.apply { children().remove() }?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: doc.selectFirst("input[name=nickname]")?.attr("value")?.takeIf(String::isNotBlank)
            ?: doc.selectFirst(".user-name, .user_name, .nickname, #nickname")?.text()?.takeIf(String::isNotBlank) ?: account
        val uid = Regex("__PC_HISTORY_UID\\s*=\\s*['\"]?(\\d+)").find(doc.html())?.groupValues?.get(1)
            ?: Regex("(?:__UID|user_id)\\s*=\\s*['\"]([0-9]+)['\"]").find(doc.html())?.groupValues?.get(1) ?: account
        val avatar = (block?.selectFirst("#header_avatar_wrap img.avatar") ?: doc.selectFirst(".user-avatar img, .user_avatar img, img.headimage"))
            ?.let { media(it.attr("src")) }.orEmpty()
        val membership = block?.selectFirst(".vipiconWord")?.text()?.trim()?.replace("會員", "会员")?.takeIf(String::isNotBlank)
            ?: if (block != null) "普通会员" else "香香腐宅"
        val summary = block?.selectFirst(".edit-block1")?.text().orEmpty()
        fun number(pattern: String): Long? = Regex(pattern).find(summary)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
        val exp = number("(?:經驗值|经验值|經驗|经验)\\s*[：:]\\s*([0-9,]+)")
        val gold = number("FU\\s*金\\s*[：:]\\s*([0-9,]+)")
        val days = block?.selectFirst(".edit-block")?.text()?.let { Regex("[：:]\\s*(\\d+)\\s*天").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val stats = buildList {
            gold?.let { add(ProfileStat("FU金", it.toString())) }
            exp?.let { add(ProfileStat("经验", it.toString())) }
            days?.let { add(ProfileStat("自动签到", "$it 天")) }
        }
        return UserProfile(uid, name, avatar, title = membership, exp = exp, stats = stats)
    }
    fun currentOrigin(): HttpUrl = cookies.base()
    suspend fun selectSite(site: XiangxiangSite) = selectSite(site.baseUrl.toHttpUrl())
    internal suspend fun selectSite(target: HttpUrl) {
        require(origins.accepts(target)) { "请选择受支持的访问站点" }
        val previous = cookies.base()
        try {
            fetch(target)
            if (token() != null && cookies.base() != previous) {
                // A separate origin needs its own login; never copy authentication cookies to it.
                cookies.selectBase(previous)
                throw PicaException(PicaException.Kind.CREDENTIALS, "此站点需要单独登录，请在退出登录后切换")
            }
        } catch (e: Exception) { cookies.selectBase(previous); throw e }
    }
    override suspend fun checkInStatus(): DailyCheckIn {
        val doc = document("home/signup")
        if (doc.title().contains("用户认证")) throw PicaException(PicaException.Kind.AUTH, "请重新登录香香腐宅")
        val encoded = Regex("calendarData\\s*=\\s*JSON\\.parse\\('([^']*)'\\)").find(doc.html())?.groupValues?.get(1)
            ?: throw PicaException(PicaException.Kind.DATA, "签到状态暂时无法读取，请刷新后重试")
        val calendar = try { JSONObject(encoded) } catch (_: Exception) { throw PicaException(PicaException.Kind.DATA, "签到日历格式已变化") }
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei"))
        val days = calendar.optJSONArray(today.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))) ?: JSONArray()
        val signed = (0 until days.length()).any { days.optString(it).toIntOrNull() == today.dayOfMonth }
        return DailyCheckIn(signed, today.toString(), days.length())
    }
    override suspend fun checkIn(): DailyCheckIn {
        val before = checkInStatus()
        if (before.signed) return before
        val result = json("home/Api/signupNew.html", form = mapOf("td" to "", "auto" to "true", "type" to "1", "autoSign" to "false"))
        val confirmed = checkInStatus()
        if (confirmed.signed) return confirmed
        if (result.optInt("code") != 2000) throw PicaException(PicaException.Kind.SERVICE,
            plain(result.optString("msg")).ifBlank { "签到未成功，请稍后重试" }.take(160))
        throw PicaException(PicaException.Kind.DATA, "签到结果尚未确认，请刷新签到状态后查看")
    }
    private fun embeddedObject(doc: Document): JSONObject {
        val html = doc.html()
        val start = Regex("(?:let|var|const)\\s+data\\s*=\\s*JSON\\.parse\\(\\s*").find(html)?.range?.last?.plus(1)
            ?: throw PicaException(PicaException.Kind.DATA, "首页内容格式已变化，请刷新后重试")
        if (html.getOrNull(start) != '"') throw PicaException(PicaException.Kind.DATA, "首页数据格式已变化")
        // Real homepages embed hundreds of books in one JS string. Scan escapes linearly;
        // a repeated regex alternation over that string can overflow the Java regex stack.
        var end = start + 1
        while (end < html.length) {
            if (html[end] == '\\') { end += 2; continue }
            if (html[end] == '"') break
            end++
        }
        if (end >= html.length) throw PicaException(PicaException.Kind.DATA, "首页数据不完整")
        val literal = html.substring(start, end + 1)
        return try { JSONObject(JSONArray("[$literal]").getString(0)) }
        catch (_: Exception) { throw PicaException(PicaException.Kind.DATA, "首页数据暂时无法读取") }
    }
    override suspend fun homepage(): HomeFeed {
        val doc = document("")
        val rows = embeddedObject(doc).optJSONArray("data")?.objects()
            ?: throw PicaException(PicaException.Kind.DATA, "首页分区暂时无法读取")
        val sections = rows.filter { it.optString("name") in HOME_KEYS }.map { section ->
            HomeSection(section.getString("name"), plain(section.getString("title")), section.optJSONArray("data")?.objects().orEmpty().map { parseComic(it) }.distinctBy(Comic::id))
        }
        if (sections.isEmpty()) throw PicaException(PicaException.Kind.DATA, "首页缺少漫画分区")
        val banners = doc.select(".index-slide a[href] img[src]").mapNotNull { image ->
            val target = cookies.base().resolve(image.parent()?.attr("href").orEmpty()) ?: return@mapNotNull null
            if (!origins.accepts(target)) return@mapNotNull null
            val comicId = Regex("^/home/book/index/id/(\\d+)$").matchEntire(target.encodedPath)?.groupValues?.get(1) ?: return@mapNotNull null
            HomeBanner(id(comicId), media(image.attr("src")))
        }.distinctBy(HomeBanner::comicId)
        val articles = rows.firstOrNull { it.optString("name") == "article" }?.optJSONArray("data")?.objects().orEmpty().map { article ->
            HomeArticle(id(article.optString("id")), plain(article.optString("title")), url("home/article/content/${id(article.optString("id"))}").toString(), article.optString("createdAt"))
        }
        return HomeFeed(sections, banners, articles)
    }
    override suspend fun homeSection(key: String, page: Int): ComicResults {
        require(key in HOME_KEYS && page >= 1)
        if (key == "newest") return dailyUpdates(page)
        return results(checked(json("home/api/getpage/tp/1-$key-${page - 1}")), page)
    }
    private suspend fun dailyUpdates(page: Int): ComicResults {
        val root = json("home/Api/getDailyUpdate.html", form = mapOf("widx" to "11", "limit" to "18", "page" to page.toString(), "lastpage" to "0"))
        if (root.optInt("code") != 200) throw PicaException(PicaException.Kind.SERVICE, "每日更新暂时无法读取")
        val rows = root.optJSONArray("result")?.objects()?.map { parseComic(it) }
            ?: throw PicaException(PicaException.Kind.DATA, "每日更新格式已变化")
        val total = root.optJSONObject("pcPagi")?.optInt("page_cnt", page) ?: page
        return ComicResults(rows, page, if (rows.isEmpty()) page else total.coerceAtLeast(page))
    }
    private fun unsupported(): Nothing = throw PicaException(PicaException.Kind.PROTOCOL, "请在香香腐宅网站管理此项资料")
    override suspend fun register(form: Registration) = unsupported()
    override suspend fun requestRegistrationCode(email: String) {
        XiangxiangRegistration.validateEmail(email)
        val result = json("home/auth/regGetVerify.html", form = mapOf("email" to email.trim()))
        if (result.optString("status") != "0") throw PicaException(PicaException.Kind.CREDENTIALS,
            plain(result.optString("info")).ifBlank { "验证码发送未成功，请稍后重试" }.take(160))
    }
    override suspend fun registerXiangxiang(form: XiangxiangRegistration) {
        form.validate()
        val result = json("home/auth/login.html", form = mapOf("type" to "register", "username" to form.account.trim(),
            "password" to form.password, "password2" to form.confirmation, "email" to form.email.trim(), "reset_token" to form.emailCode.trim(), "vfycode" to form.captcha.trim()))
        if (result.optString("status") != "0") throw PicaException(PicaException.Kind.CREDENTIALS,
            plain(result.optString("info")).ifBlank { "注册未成功，请检查填写的信息" }.take(160))
    }
    override suspend fun updateSlogan(slogan: String) = unsupported()
    override suspend fun updateAvatar(jpeg: ByteArray) {
        require(jpeg.size in 4..500_000 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte()) { "请选择有效的头像图片" }
        val before = profile().avatarUrl
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("is_avatar", "")
            .addFormDataPart("imgInp", "avatar.jpg", jpeg.toRequestBody("image/jpeg".toMediaType())).build()
        fetch(url("home/user/cgavatar.html"), upload = body)
        val after = profile().avatarUrl
        if (after.isBlank() || after == before) throw PicaException(PicaException.Kind.DATA, "头像更新尚未确认，请刷新个人资料后查看")
    }
    override suspend fun myComments(page: Int): RecentCommentResults {
        val root = json("home/user/getComment", form = mapOf("page" to (page - 1).coerceAtLeast(0).toString(), "loadMod" to "new"))
        if (root.optJSONArray("result")?.length() == 0 && root.optInt("code", -1) == 0) return RecentCommentResults(emptyList(), page, page, 0)
        val result = checked(root).optJSONObject("result") ?: throw PicaException(PicaException.Kind.DATA, "历史留言暂时无法读取")
        val rows = result.optJSONArray("list")?.objects().orEmpty().map { o ->
            val key = id(o.optString("id"))
            val comicId = o.optString("manhua_id").takeIf { it.matches(numeric) && it != "0" }
            val parent = o.optString("reply_to").takeIf { it.matches(numeric) && it != "0" }
            val hidden = o.has("status") && o.optInt("status") != 1
            val author = plain(o.optString("nickname").takeUnless { it == "null" || it.isBlank() } ?: o.optString("username"))
            if (comicId != null) commentContexts[key] = CommentContext(comicId, parent)
            RecentComment(key, if (hidden) "这条评论已隐藏" else plain(o.optString("content")),
                o.optString("updated_at").ifBlank { o.optString("created_at") }, o.optInt("thumbsCnt"), o.optInt("reply_count"),
                comicId, plain(o.optString("manhua_title")), parent, hidden, o.optInt("thumbsUid") == 1,
                user = UserProfile(o.optString("member_id"), author, media(o.optString("headimage"))))
        }
        return RecentCommentResults(rows, page, if (result.optInt("is_last") == 1 || rows.isEmpty()) page else page + 1,
            result.optInt("comment_count", rows.size))
    }
    override suspend fun comics(page: Int): ComicResults = category("", ComicSort.UPDATED, page)
    override suspend fun categories(): List<ComicCategory> {
        val doc = document("home/book/cate.html")
        val tags = doc.select("[data-str=tag] a[data-value]").map { it.attr("data-value").trim() }.filter { it.isNotBlank() && it != "0" }.distinct()
        if (tags.isEmpty()) throw PicaException(PicaException.Kind.DATA, "香香腐宅分类暂时无法读取")
        return (listOf("韩漫", "日漫", "国漫", "连载中", "已完结") + tags).distinct().map { ComicCategory("xx-${digest(it.toByteArray()).take(12)}", it) }
    }
    override suspend fun category(title: String, sort: ComicSort, page: Int): ComicResults {
        require(sort in capabilities.comicSorts) { "香香腐宅暂不支持此排序" }
        val origin = mapOf("韩漫" to "1", "日漫" to "2", "国漫" to "3")[title]
        val progress = when (title) { "连载中" -> "0"; "已完结" -> "1"; else -> "2" }
        val tag = if (origin != null || progress != "2") "0" else title.ifBlank { "0" }
        val target = url("home/api/cate/tp/").newBuilder().addPathSegment("1-$tag-$progress-1-${page.coerceAtLeast(1)}-0-1-2").addQueryParameter("mt", origin ?: "0").build()
        return results(checked(JSONObject(fetch(target).toString(Charsets.UTF_8))), page)
    }
    override suspend fun search(keyword: String, page: Int, sort: SearchSort, categories: Set<String>): ComicResults {
        require(sort in capabilities.searchSorts && categories.isEmpty()) { "香香腐宅搜索暂不支持此筛选" }
        if (keyword.isBlank()) return comics(page)
        return results(checked(json("home/api/searchk", mapOf("keyword" to keyword, "type" to "1", "pageNo" to page.toString()))), page)
    }
    override suspend fun ranking(period: RankingPeriod): List<Comic> {
        require(period in capabilities.rankingPeriods)
        val result = checked(json("home/api/rank/type/1")).getJSONObject("result")
        val channel = when (period) { RankingPeriod.DAY -> "most_clicks"; RankingPeriod.WEEK -> "most_favorites"; RankingPeriod.MONTH -> "most_search" }
        val list = result.optJSONArray(channel) ?: throw PicaException(PicaException.Kind.DATA, "排行榜格式已变化，请刷新后重试")
        return list.objects().map { parseComic(it) }
    }
    override suspend fun favourites(page: Int, sort: ComicSort): ComicResults {
        val root = json("home/api/favorite", mapOf("tp" to "1-$page", "apimhstatus" to "all"))
        // The favourite endpoint also returns code=0/msg=ok with succ=false on populated
        // pages. Validate its actual payload instead of treating that flag as a failure.
        if (!favouriteOkEnvelope(root)) checked(root)
        val result = root.optJSONObject("result") ?: throw PicaException(PicaException.Kind.DATA, "云收藏暂时无法读取")
        val data = result.optJSONArray("data") ?: throw PicaException(PicaException.Kind.DATA, "云收藏格式已变化，请刷新后重试")
        if (result.opt("lastPage") !is Boolean) throw PicaException(PicaException.Kind.DATA, "云收藏分页格式已变化，请刷新后重试")
        val rows = data.objects().map { o -> parseComic(o, "bid").copy(isFavourite = true) }
        return ComicResults(rows, page, if (result.optBoolean("lastPage") || rows.isEmpty()) page else page + 1)
    }
    private fun favouriteOkEnvelope(root: JSONObject): Boolean = root.optInt("code", -1) == 0 && plain(root.optString("msg")).equals("ok", ignoreCase = true)
    override suspend fun toggleFavourite(id: String): Boolean {
        val selected = !comic(id).isFavourite
        try {
            val result = json("home/api/dosubscribe", mapOf("action" to if (selected) "add" else "del", "article_id" to id(id)))
            if (!favouriteOkEnvelope(result)) checked(result)
        } catch (e: PicaException) {
            // A transport failure can occur after the site applied the change. Only read
            // back the state; never repeat the add/delete request automatically.
            if (e.kind != PicaException.Kind.NETWORK) throw e
            val confirmed = try { comic(id).isFavourite } catch (_: PicaException) { throw e }
            if (confirmed == selected) return selected
            throw e
        }
        if (comic(id).isFavourite != selected) throw PicaException(PicaException.Kind.DATA, "收藏状态尚未确认，请刷新作品后查看")
        return selected
    }
    override suspend fun comic(id: String): Comic {
        val doc = document("home/book/index/id/${id(id)}")
        val detail = doc.selectFirst(".stui-content__detail") ?: throw PicaException(PicaException.Kind.DATA, "作品资料暂时无法读取，可能需要登录或会员权限")
        val title = detail.selectFirst("h1")?.text().orEmpty()
        if (title.isBlank()) throw PicaException(PicaException.Kind.DATA, "作品资料缺少标题")
        val data = detail.select("p.data")
        fun field(vararg labels: String): String = data.firstOrNull { row -> labels.any { row.text().startsWith(it) } }?.text()?.substringAfter('：')?.trim().orEmpty()
        val tags = detail.select("a.taga[href*='searchkw']").map { it.text().trim() }.filter(String::isNotBlank).distinct()
        val finished = data.firstOrNull()?.text().orEmpty()
        val raw = doc.html()
        val subscribed = Regex("let data = JSON\\.parse\\(\"([^\\n]+)\"\\);").find(raw)?.groupValues?.get(1)?.let { encoded ->
            runCatching { JSONArray(JSONArray("[\"$encoded\"]").getString(0)).optString(1) == "200" }.getOrDefault(false)
        } ?: false
        return Comic(id, title, field("作者"), doc.selectFirst(".stui-content__thumb [data-original]")?.let { media(it.attr("data-original")) }.orEmpty(),
            description = detail.selectFirst(".detail-text")?.wholeText()?.trim().orEmpty(), tags = tags,
            chapterCount = chapterCache[id]?.size ?: 0, finished = finished.contains("完结") || finished.contains("完結"), isFavourite = subscribed,
            categories = tags, completionKnown = finished.contains("连载") || finished.contains("連載") || finished.contains("完"),
            categoriesAvailable = true, tagsAvailable = true, views = field("觀看數", "观看数").replace(",", "").toLongOrNull())
    }
    override suspend fun chapters(id: String): List<Chapter> {
        val result = checked(json("home/api/chapter_list/tp/${id(id)}")).getJSONObject("result")
        val rows = result.optJSONArray("list")?.objects().orEmpty().distinctBy { it.optString("id") }
        if (rows.isEmpty()) throw PicaException(PicaException.Kind.DATA, "暂无可读取章节，请检查登录或会员权限")
        // This endpoint returns the entire list, even though its legacy totalPage says otherwise.
        return rows.mapIndexed { index, o -> Chapter(id(o.optString("id")), plain(o.getString("title")), index + 1) }.also { chapterCache[id] = it }
    }
    override suspend fun pages(id: String, order: Int): List<PageImage> {
        val chapter = (chapterCache[id] ?: chapters(id)).firstOrNull { it.order == order } ?: error("章节不存在")
        val doc = document("home/book/capter/id/${id(chapter.id)}")
        val slices = (Regex("randomClass\\s*=\\s*(\\d+)").find(doc.html()) ?: Regex("var\\s+num\\s*=\\s*(\\d+)").find(doc.html()))?.groupValues?.get(1)?.toIntOrNull()
        val images = doc.select(".reader-cartoon-image img.lazy[data-original]").mapIndexed { index, img ->
            var link = media(img.attr("data-original"))
            if (img.attr("style").replace(" ", "").contains("display:none") && "?w=" in link) {
                val count = slices?.takeIf { it in 2..64 } ?: throw PicaException(PicaException.Kind.DATA, "章节图片格式已变化，暂时无法还原")
                link = XiangxiangImages.mark(link, count)
            }
            PageImage("${chapter.id}-$index", index, link)
        }
        if (images.isEmpty()) throw PicaException(PicaException.Kind.DATA, "本章无法读取，请检查登录、会员权限或网站限制")
        return images
    }
    override suspend fun comments(comicId: String, page: Int): CommentResults = parseComments(json("home/api/getComment",
        form = mapOf("manhua_id" to id(comicId), "page" to (page - 1).coerceAtLeast(0).toString(), "loadMod" to "hot")), comicId, page)
    override suspend fun replies(commentId: String, page: Int): CommentResults {
        val context = commentContexts[commentId] ?: throw PicaException(PicaException.Kind.DATA, "请先刷新评论后展开回复")
        return parseComments(json("home/api/getSubComment", form = mapOf("manhua_id" to context.comicId, "comment_id" to id(commentId),
            "page" to (page - 1).coerceAtLeast(0).toString())), context.comicId, page, commentId)
    }
    override suspend fun postComment(comicId: String, content: String) { send(mapOf("manhua_id" to id(comicId), "content" to commentText(content))) }
    override suspend fun postReply(commentId: String, content: String) { send(mapOf("comment_id" to id(commentId), "content" to commentText(content))) }
    private suspend fun send(form: Map<String, String>) {
        val result = checked(json("home/api/addComment", form = form))
        if (result.optInt("code") != 1) throw PicaException(PicaException.Kind.SERVICE, plain(result.optString("msg")).ifBlank { "评论发送未确认，请刷新查看" }.take(160))
    }
    override suspend fun toggleCommentLike(commentId: String): Boolean {
        val context = commentContexts[commentId] ?: throw PicaException(PicaException.Kind.DATA, "请先刷新评论")
        val result = checked(json("home/book/thumbs", form = buildMap {
            put("id", context.comicId); put("comment_id", id(commentId)); context.parent?.let { put("reply_to", it) }
        }))
        if (result.optString("status") != "200" || !result.has("faIcon"))
            throw PicaException(PicaException.Kind.DATA, "点赞状态未确认，请刷新评论")
        return result.optString("faIcon") == "up"
    }
    private fun parseComments(root: JSONObject, comicId: String, page: Int, parent: String? = null): CommentResults {
        if (root.optJSONArray("result")?.length() == 0 && root.optInt("code", -1) == 0) return CommentResults(emptyList(), page, page, 0)
        checked(root)
        val result = root.optJSONObject("result") ?: throw PicaException(PicaException.Kind.DATA, "评论暂时无法读取")
        val rows = result.optJSONArray("list")?.objects().orEmpty().map { o ->
            val key = id(o.optString("id")); commentContexts[key] = CommentContext(comicId, parent)
            val author = plain(o.optString("nickname").takeUnless { it == "null" || it.isBlank() } ?: o.optString("username"))
            val hidden = o.has("status") && o.optInt("status") != 1
            ComicComment(key, author, if (hidden) "这条评论已隐藏" else plain(o.optString("content")), o.optString("created_at"),
                o.optInt("thumbsCnt").coerceAtLeast(0), o.optInt("reply_count").coerceAtLeast(0), o.optInt("thumbsUid") == 1,
                user = UserProfile(o.optString("member_id"), author, media(o.optString("headimage"))), parentId = parent, hidden = hidden)
        }
        val last = result.optInt("is_last") == 1 || rows.isEmpty()
        return CommentResults(rows, page, if (last) page else page + 1, result.optInt("comment_count", rows.size))
    }
    private fun results(root: JSONObject, page: Int): ComicResults {
        val result = root.optJSONObject("result") ?: throw PicaException(PicaException.Kind.DATA, "作品列表暂时无法读取")
        val list = result.optJSONArray("list") ?: if (result.optBoolean("firstPage") && result.optBoolean("lastPage") && result.has("code") && result.optInt("code") == 0) JSONArray()
            else throw PicaException(PicaException.Kind.DATA, "作品列表格式已变化")
        val rows = list.objects().map { parseComic(it) }
        return ComicResults(rows, page, if (result.optBoolean("lastPage") || rows.isEmpty()) page else page + 1)
    }
    private fun parseComic(o: JSONObject, idField: String = "id"): Comic {
        val tags = o.optString("keyword").split(',', '，').map(String::trim).filter(String::isNotBlank).distinct()
        return Comic(id(o.optString(idField)), plain(o.getString("title")), plain(o.optString("auther")), media(o.optString("image")),
            plain(o.optString("desc")), tags, finished = o.optInt("mhstatus") == 1, allowComment = o.optInt("comment", 1) == 1,
            categories = tags, completionKnown = o.has("mhstatus"), tagsAvailable = o.has("keyword"), categoriesAvailable = o.has("keyword"),
            views = o.takeIf { it.has("watchtimes") }?.optLong("watchtimes"), latestChapter = plain(o.optString("last_chapter_title").ifBlank { o.optString("lastcapter") }))
    }
    private fun media(path: String): String {
        if (path.isBlank() || path == "null") return ""
        val base = cookies.base()
        val imageBase = if (base.host in setOf("localhost", "127.0.0.1", "10.0.2.2")) base
            else base.newBuilder().host("img.${base.host}").build()
        val target = (if (path.startsWith("/")) imageBase else base).resolve(path)
        return config.validateMedia(target?.toString() ?: throw PicaException(PicaException.Kind.DATA, "图片地址无效"))
    }
    private fun commentText(content: String) = content.trim().also { require(it.length in 1..1000) { "评论须为 1–1000 字" } }
    private fun plain(value: String): String = Jsoup.parse(value.replace("\r\n", "<br>")).wholeText().trim()
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    companion object {
        private const val MAX_BODY = 8L * 1024 * 1024
        private val HOME_KEYS = setOf("newest", "siangsiang", "recommend", "topestmh", "cnxh")
    }
}

/** Only the configured origin and verified site aliases can receive follow-up requests. */
internal class XiangxiangOrigins(configured: HttpUrl, mirrors: List<HttpUrl>) {
    val initial: HttpUrl = baseOf(configured)
    private val allowed = (listOf(initial) + mirrors.map(::baseOf)).toSet()
    fun accepts(url: HttpUrl): Boolean = url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && baseOf(url) in allowed
    companion object {
        // The announcement board lists three aliases; their observed canonical redirect targets boylove.cc.
        private val publicBases = listOf("fulovehouse.net", "fuzhaiboylove7.work", "fuzhaiboylove8.work", "boylove.cc")
            .map { "https://$it/".toHttpUrl() }
        fun baseOf(url: HttpUrl): HttpUrl = url.newBuilder().encodedPath("/").query(null).fragment(null).build()
        fun mirrorsFor(configured: HttpUrl): List<HttpUrl> = if (baseOf(configured) in publicBases) publicBases else emptyList()
    }
}

private class SourceCookies(private val origins: XiangxiangOrigins, private val token: () -> String?, private val persist: (String) -> Unit) : CookieJar {
    // Keep separate jars even when test origins share a hostname on different ports.
    private val values = linkedMapOf<HttpUrl, LinkedHashMap<String, Cookie>>()
    private var loaded: String? = null
    private var label = ""
    private var selected = origins.initial
    private fun sync() {
        val current = token()
        if (current == loaded) return
        values.clear(); label = ""; loaded = current; selected = origins.initial
        if (current != null) runCatching {
            val root = JSONObject(current)
            val savedBase = root.optString("baseUrl").takeIf(String::isNotBlank)?.toHttpUrlOrNull() ?: origins.initial
            if (!origins.accepts(savedBase)) return@runCatching
            selected = XiangxiangOrigins.baseOf(savedBase); label = root.optString("account")
            val list = root.getJSONArray("cookies")
            val jar = values.getOrPut(selected) { linkedMapOf() }
            for (i in 0 until list.length()) Cookie.parse(selected, list.getString(i))?.let { jar["${it.name}:${it.path}"] = it }
        }
    }
    @Synchronized fun base(): HttpUrl { sync(); return selected }
    @Synchronized fun selectBase(url: HttpUrl) {
        sync()
        require(origins.accepts(url))
        val base = XiangxiangOrigins.baseOf(url)
        if (selected != base) { selected = base; if (loaded != null) persist(snapshot(label)) }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        sync()
        return values[XiangxiangOrigins.baseOf(url)]?.values.orEmpty().filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    }
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        sync()
        if (!origins.accepts(url)) return
        val jar = values.getOrPut(XiangxiangOrigins.baseOf(url)) { linkedMapOf() }
        cookies.forEach { jar["${it.name}:${it.path}"] = it }
        if (loaded != null) persist(snapshot(label))
    }
    @Synchronized fun snapshot(account: String): String {
        sync()
        return JSONObject().put("account", account).put("baseUrl", selected.toString())
            .put("cookies", JSONArray(values[selected]?.values.orEmpty().map { it.toString() })).toString()
    }
    @Synchronized fun account(): String { sync(); return label }
}

private suspend fun Call.awaitSource(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { continuation.resume(response, onCancellation = { _, value, _ -> value.close() }) }
    })
}
