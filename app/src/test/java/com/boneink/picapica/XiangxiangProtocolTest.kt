package com.boneink.picapica

import com.boneink.picapica.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XiangxiangProtocolTest {
    private fun config(server: MockWebServer) = SourceConfig(server.url("/"), SourceKind.XIANGXIANG)
    private fun response(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")
    private val list = """{"code":0,"succ":true,"result":{"lastPage":false,"list":[{"id":12,"title":"日常故事","image":"/cover.webp","auther":"作者","keyword":"日常,校园","mhstatus":0}]}}"""
    @Test fun captchaLoginAndRestoredCookiesAreAccountScopedAndNeverContainPasswords() = runTest {
        MockWebServer().use { server ->
            var token: String? = null
            val api = XiangxiangClient(config(server), { token })
            server.enqueue(MockResponse().setBody("captcha").addHeader("Set-Cookie", "PHPSESSID=challenge; Path=/"))
            assertEquals("captcha", api.captcha()!!.toString(Charsets.UTF_8)); server.takeRequest()
            server.enqueue(response("""{"status":0,"info":"登录成功"}""").addHeader("Set-Cookie", "auth=account-A; HttpOnly; Path=/"))
            token = api.signIn("reader-A", "private-password", "1234")
            val request = server.takeRequest()
            assertEquals("POST", request.method); assertEquals("/home/auth/login.html", request.path)
            assertTrue(request.getHeader("Cookie")!!.contains("PHPSESSID=challenge"))
            assertTrue(request.body.readUtf8().contains("vfycode=1234"))
            assertNull(request.getHeader("authorization")); assertNull(request.getHeader("api-key")); assertNull(request.getHeader("signature"))
            assertFalse(token!!.contains("private-password")); assertTrue(token!!.contains("account-A"))
            val restored = XiangxiangClient(config(server), { token })
            server.enqueue(response("<html><title>我的</title><span class='nickname'>读者 A</span></html>"))
            assertEquals("读者 A", restored.profile().name)
            assertTrue(server.takeRequest().getHeader("Cookie")!!.contains("auth=account-A"))
            val other = XiangxiangClient(config(server), { null })
            server.enqueue(response(list)); other.comics(); assertNull(server.takeRequest().getHeader("Cookie"))
            token = null
            server.enqueue(response(list)); restored.comics(); assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }
    @Test fun tagsAndPaginationAreEncodedAsOneSegmentAndSourceKeysSurviveMirrorChanges() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(config(server), { null })
            server.enqueue(response(list))
            val result = api.category("日常 & 短篇/合集", page = 2)
            val request = server.takeRequest()
            assertEquals("1-日常 & 短篇/合集-2-1-2-0-1-2", request.requestUrl!!.pathSegments.last())
            assertEquals("0", request.requestUrl!!.queryParameter("mt"))
            assertEquals(2, result.page); assertEquals(3, result.pages)
            assertEquals(listOf("日常", "校园"), result.items.single().tags)
            assertEquals(config(server).key, SourceConfig(okhttp3.HttpUrl.Builder().scheme("https").host("fuzhaiboylove7.work").build(), SourceKind.XIANGXIANG).key)
            assertNotEquals(config(server).key, SourceConfig(server.url("/")).key)
        }
    }
    @Test fun searchUsesWebsiteKeywordAndDoesNotSilentlyIgnoreUnsupportedFilters() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(config(server), { null })
            server.enqueue(response(list)); assertEquals("12", api.search("日常 & 短篇", 3).items.single().id)
            val req = server.takeRequest(); assertEquals("日常 & 短篇", req.requestUrl!!.queryParameter("keyword"))
            assertEquals("1", req.requestUrl!!.queryParameter("type")); assertEquals("3", req.requestUrl!!.queryParameter("pageNo"))
            try { api.search("日常", categories = setOf("校园")); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun commentsRepliesAndLikesKeepTheCorrectComicContextAndZeroBasedPages() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(config(server), { null })
            server.enqueue(response("""{"succ":true,"result":{"list":[{"id":55,"member_id":9,"username":"读者","content":"第一行<br>第二行","created_at":"2026-11-02","thumbsCnt":3,"reply_count":2}],"is_last":0}}"""))
            val top = api.comments("12"); assertEquals(2, top.pages); assertEquals(2, top.items.single().replies)
            assertTrue(top.items.single().content.contains("第二行")); assertTrue(server.takeRequest().body.readUtf8().contains("page=0"))
            server.enqueue(response("""{"succ":true,"result":{"list":[{"id":56,"member_id":10,"username":"另一读者","content":"回复","thumbsUid":1}],"is_last":1}}"""))
            assertEquals("55", api.replies("55", 2).items.single().parentId)
            val replies = server.takeRequest().body.readUtf8(); assertTrue(replies.contains("manhua_id=12")); assertTrue(replies.contains("comment_id=55")); assertTrue(replies.contains("page=1"))
            server.enqueue(response("""{"status":"200","faIcon":"up"}""")); assertTrue(api.toggleCommentLike("56"))
            val like = server.takeRequest().body.readUtf8(); assertTrue(like.contains("id=12")); assertTrue(like.contains("reply_to=55")); assertTrue(like.contains("comment_id=56"))
            server.enqueue(response("""{"code":1,"succ":true}""")); api.postReply("55", "回复内容")
            assertTrue(server.takeRequest().body.readUtf8().contains("comment_id=55"))
        }
    }
    @Test fun readerOnlyAcceptsComicImagesAndFailsClosedForUnknownRearrangement() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(config(server), { null })
            server.enqueue(response("""{"succ":true,"result":{"totalPage":9,"list":[{"id":123,"title":"第一话"},{"id":124,"title":"第二话"}]}}"""))
            assertEquals(listOf(1, 2), api.chapters("12").map { it.order }); server.takeRequest()
            server.enqueue(response("""<script>var randomClass = 13;</script><img class='lazy' data-original='/ad.jpg'><div class='reader-cartoon-image'><img class='lazy' style='display:none' data-original='/page.webp?w=650_1320'></div>"""))
            val pages = api.pages("12", 1); assertEquals(1, pages.size); assertEquals(13, XiangxiangImages.slices(pages.single().url.toHttpUrl()))
            assertEquals("/home/book/capter/id/123", server.takeRequest().path)
            server.enqueue(response("""<div class='reader-cartoon-image'><img class='lazy' style='display:none' data-original='/page.webp?w=650_1320'></div>"""))
            try { api.pages("12", 2); fail() } catch (e: PicaException) { assertEquals(PicaException.Kind.DATA, e.kind) }
        }
    }
    @Test fun expiredSessionAndChangedListSchemaAreNotReportedAsEmptySuccess() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(config(server), { null })
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/home/auth/login.html"))
            try { api.profile(); fail() } catch (e: PicaException) { assertEquals(PicaException.Kind.AUTH, e.kind) }
            server.enqueue(response("""{"succ":true,"result":{}}"""))
            try { api.comics(); fail() } catch (e: PicaException) { assertEquals(PicaException.Kind.DATA, e.kind) }
        }
    }
    @Test fun knownEmptyResultsAreAcceptedWithoutHidingSchemaFailures() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(config(server), { null })
            server.enqueue(response("""{"code":0,"succ":true,"result":{"firstPage":true,"lastPage":true,"code":0}}"""))
            assertTrue(api.search("missing").items.isEmpty())
            server.enqueue(response("""{"code":0,"succ":false,"result":{"lastPage":true,"list":[]}}"""))
            assertTrue(api.category("missing").items.isEmpty())
            server.enqueue(response("""{"code":0,"succ":false,"result":[],"msg":"暫無留言"}"""))
            assertTrue(api.comments("12").items.isEmpty())
        }
    }
    @Test fun favouriteToggleReadsTheWebsiteStateAndUsesAnExplicitAction() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(config(server), { null })
            val subscribed = org.json.JSONArray(listOf("已收藏", "200", "")).toString()
            val detail = "<script>let data = JSON.parse(" + JSONObject.quote(subscribed) + ");</script>" +
                "<div class='stui-content__detail'><h1>日常故事</h1><p class='data'>已完结</p></div>"
            server.enqueue(response(detail)); server.enqueue(response("""{"succ":true}"""))
            server.enqueue(response(detail.replace("200", "1")))
            assertFalse(api.toggleFavourite("12"))
            assertEquals("GET", server.takeRequest().method)
            val action = server.takeRequest(); assertEquals("del", action.requestUrl!!.queryParameter("action")); assertEquals("12", action.requestUrl!!.queryParameter("article_id"))
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test fun captchaMirrorRedirectPinsLoginCookiesMediaAndRestoredSessionToTheSameOrigin() = runTest {
        MockWebServer().use { entry -> MockWebServer().use { mirror ->
            var token: String? = null
            val api = XiangxiangClient(config(entry), { token }, mirrorBases = listOf(mirror.url("/")))
            entry.enqueue(MockResponse().setResponseCode(301).setHeader("Location", mirror.url("/captcha.html"))
                .addHeader("Set-Cookie", "front=entry-only; Path=/"))
            mirror.enqueue(MockResponse().setBody("captcha").addHeader("Set-Cookie", "PHPSESSID=mirror-challenge; Path=/"))
            assertEquals("captcha", api.captcha().toString(Charsets.UTF_8))
            entry.takeRequest(); assertNull(mirror.takeRequest().getHeader("Cookie"))
            mirror.enqueue(response("""{"status":0}""").addHeader("Set-Cookie", "auth=mirror-account; Path=/; HttpOnly"))
            token = api.signIn("reader", "private-password", "1234")
            val login = mirror.takeRequest()
            assertEquals("POST", login.method); assertEquals("/home/auth/login.html", login.path)
            assertTrue(login.getHeader("Cookie")!!.contains("PHPSESSID=mirror-challenge"))
            assertFalse(login.getHeader("Cookie")!!.contains("front=entry-only"))
            assertEquals(mirror.url("/"), login.getHeader("Referer")!!.toHttpUrl())
            assertEquals(mirror.url("/").toString(), JSONObject(token!!).getString("baseUrl"))
            assertEquals(1, entry.requestCount)
            val restored = XiangxiangClient(config(entry), { token }, mirrorBases = listOf(mirror.url("/")))
            mirror.enqueue(response("<title>我的</title><span class='nickname'>镜像读者</span>"))
            assertEquals("镜像读者", restored.profile().name)
            assertTrue(mirror.takeRequest().getHeader("Cookie")!!.contains("auth=mirror-account"))
            mirror.enqueue(response(list)); assertTrue(restored.comics().items.single().coverUrl.startsWith(mirror.url("/").toString()))
            assertEquals(1, entry.requestCount)
        } }
    }
    @Test fun unknownOriginRedirectIsRejectedBeforeAnyCookiesOrPasswordsReachIt() = runTest {
        MockWebServer().use { entry -> MockWebServer().use { foreign ->
            val api = XiangxiangClient(config(entry), { null })
            entry.enqueue(MockResponse().setResponseCode(302).setHeader("Location", foreign.url("/captcha.html")))
            try { api.captcha(); fail() } catch (e: PicaException) { assertEquals(PicaException.Kind.SERVICE, e.kind) }
            assertEquals(0, foreign.requestCount)
        } }
    }
    @Test fun loginPostIsNeverReplayedAcrossOriginsAndNewCaptchaUsesTheNewOrigin() = runTest {
        MockWebServer().use { entry -> MockWebServer().use { mirror ->
            val api = XiangxiangClient(config(entry), { null }, mirrorBases = listOf(mirror.url("/")))
            entry.enqueue(MockResponse().setBody("captcha").setHeader("Set-Cookie", "PHPSESSID=entry-challenge; Path=/"))
            api.captcha(); entry.takeRequest()
            entry.enqueue(MockResponse().setResponseCode(307).setHeader("Location", mirror.url("/home/auth/login.html")))
            try { api.signIn("reader", "private-password", "1234"); fail() }
            catch (e: PicaException) { assertEquals(PicaException.Kind.CREDENTIALS, e.kind) }
            assertEquals(0, mirror.requestCount); assertEquals("POST", entry.takeRequest().method)
            mirror.enqueue(MockResponse().setBody("new-captcha").addHeader("Set-Cookie", "PHPSESSID=mirror-challenge; Path=/"))
            assertEquals("new-captcha", api.captcha().toString(Charsets.UTF_8))
            assertNull(mirror.takeRequest().getHeader("Cookie"))
            mirror.enqueue(response("""{"status":0}""").addHeader("Set-Cookie", "auth=mirror-account; Path=/"))
            api.signIn("reader", "private-password", "5678")
            val explicitLogin = mirror.takeRequest()
            assertTrue(explicitLogin.getHeader("Cookie")!!.contains("mirror-challenge"))
            assertTrue(explicitLogin.body.readUtf8().contains("vfycode=5678"))
        } }
    }
    @Test fun legacyCookiesWithoutOriginMetadataRemainUsableAndRedirectLoopsAreBounded() = runTest {
        MockWebServer().use { server ->
            val legacy = """{"account":"reader","cookies":["auth=legacy; Path=/"]}"""
            val api = XiangxiangClient(config(server), { legacy })
            server.enqueue(response("<title>我的</title><span class='nickname'>原有读者</span>"))
            assertEquals("原有读者", api.profile().name)
            assertTrue(server.takeRequest().getHeader("Cookie")!!.contains("auth=legacy"))
            repeat(4) { server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/captcha.html")) }
            try { api.captcha(); fail() } catch (e: PicaException) { assertEquals(PicaException.Kind.SERVICE, e.kind) }
            assertEquals(5, server.requestCount)
        }
    }
    @Test fun productionAliasPolicyRejectsDowngradesCredentialsAndUnknownHosts() {
        val root = "https://fulovehouse.net/".toHttpUrl()
        val origins = XiangxiangOrigins(root, XiangxiangOrigins.mirrorsFor(root))
        assertTrue(origins.accepts("https://boylove.cc/captcha.html?_=123".toHttpUrl()))
        assertFalse(origins.accepts("http://boylove.cc/captcha.html".toHttpUrl()))
        assertFalse(origins.accepts("https://user:secret@boylove.cc/captcha.html".toHttpUrl()))
        assertFalse(origins.accepts("https://boylove.cc.other.example/captcha.html".toHttpUrl()))
        assertFalse(origins.accepts("https://boylove.cc:8443/captcha.html".toHttpUrl()))
    }

    @Test fun authenticatedProfileCanContainTheSitesHiddenLoginForm() = runTest {
        MockWebServer().use { server ->
            val session = """{"account":"reader","cookies":["PHPSESSID=authenticated; Path=/"]}"""
            val api = XiangxiangClient(config(server), { session })
            server.enqueue(response("""<html><head><title>个人中心</title></head><body>
                <form hidden><input name="type" value="login"><input name="username"></form>
                <span class="nickname">已登录读者</span></body></html>"""))
            assertEquals("已登录读者", api.profile().name)
            assertTrue(server.takeRequest().getHeader("Cookie")!!.contains("PHPSESSID=authenticated"))
        }
    }
    @Test fun dedicatedAuthenticationPageStillInvalidatesAnExpiredProfile() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response("""<title>用户认证 - 香香腐宅</title><form><input name="type" value="login"></form>"""))
            try { XiangxiangClient(config(server), { null }).profile(); fail() }
            catch (e: PicaException) { assertEquals(PicaException.Kind.AUTH, e.kind) }
        }
    }

    @Test fun actualUserCenterSelectorsExposeMembershipAvatarCreditsAndExperience() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response("""<title>个人中心</title><input name="type" value="login">
                <div class="user-block"><a id="header_avatar_wrap"><img class="avatar" src="/avatar.jpg"></a>
                <ul class="user"><li class="name">测试昵称<span><img class="vipicon"><span class="vipiconWord">VIP會員</span></span></li>
                <li class="edit-block">剩餘自動簽到天數：0天</li><li class="edit-block1">FU金：1,780 / 經驗值：1,840</li></ul></div>
                <script>window.__PC_HISTORY_UID = 12345;</script>"""))
            val profile = XiangxiangClient(config(server), { null }).profile()
            assertEquals("测试昵称", profile.name); assertEquals("12345", profile.id)
            assertEquals("VIP会员", profile.title); assertEquals(server.url("/avatar.jpg").toString(), profile.avatarUrl)
            assertEquals(1840L, profile.exp); assertEquals(listOf("1780", "1840", "0 天"), profile.stats.map { it.value })
            assertNull(profile.level)
        }
    }
    @Test fun cloudFavouritesUseComicBidInsteadOfSubscriptionIdAndRequestAllCompletionStates() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(config(server), { null })
            server.enqueue(response("""{"code":0,"succ":true,"result":{"lastPage":false,"data":[{"id":87654,"bid":12,"title":"收藏故事","image":"/cover.webp","mhstatus":1}]}}"""))
            val result = api.favourites(2)
            assertEquals("12", result.items.single().id); assertTrue(result.items.single().isFavourite); assertTrue(result.items.single().finished)
            assertEquals(2, result.page); assertEquals(3, result.pages)
            val request = server.takeRequest(); assertEquals("/home/api/favorite", request.requestUrl!!.encodedPath)
            assertEquals("1-2", request.requestUrl!!.queryParameter("tp")); assertEquals("all", request.requestUrl!!.queryParameter("apimhstatus"))
            server.enqueue(response("""{"code":0,"succ":true,"result":{"lastPage":true,"data":[]}}"""))
            assertTrue(api.favourites(3).items.isEmpty())
        }
    }
    @Test fun commentHistoryKeepsAuthorComicParentAndReplyContext() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(config(server), { null })
            server.enqueue(response("""{"succ":true,"result":{"is_last":0,"comment_count":22,"list":[{"id":301,"member_id":10,"username":"另一位读者","headimage":"/avatar.jpg","manhua_id":12,"manhua_title":"日常故事","reply_to":300,"content":"留言","updated_at":"2026-11-02","reply_count":2}]}}"""))
            val result = api.myComments(2); val item = result.items.single()
            assertEquals("12", item.comicId); assertEquals("300", item.parentId); assertEquals("另一位读者", item.user!!.name)
            assertEquals(3, result.pages); assertEquals(22, result.total)
            assertTrue(server.takeRequest().body.readUtf8().contains("page=1"))
            server.enqueue(response("""{"succ":true,"result":{"is_last":1,"list":[]}}""")); api.replies("301")
            assertTrue(server.takeRequest().body.readUtf8().contains("manhua_id=12"))
        }
    }
    @Test fun avatarUsesMultipartFormAndConfirmsUpdatedProfileWithoutRepostingOnRedirect() = runTest {
        MockWebServer().use { server ->
            fun profile(path: String) = "<div class='user-block'><a id='header_avatar_wrap'><img class='avatar' src='$path'></a><ul class='user'><li class='name'>读者</li></ul></div>"
            server.enqueue(response(profile("/old.jpg")))
            server.enqueue(MockResponse().setResponseCode(303).setHeader("Location", "/home/user/index.html"))
            server.enqueue(response(profile("/new.jpg"))); server.enqueue(response(profile("/new.jpg")))
            XiangxiangClient(config(server), { null }).updateAvatar(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte()))
            assertEquals("GET", server.takeRequest().method)
            val upload = server.takeRequest(); assertEquals("POST", upload.method); assertEquals("/home/user/cgavatar.html", upload.path)
            assertTrue(upload.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
            val body = upload.body.readUtf8(); assertTrue(body.contains("name=\"imgInp\"; filename=\"avatar.jpg\"")); assertTrue(body.contains("name=\"is_avatar\""))
            assertEquals("GET", server.takeRequest().method); assertEquals("GET", server.takeRequest().method)
        }
    }
    @Test fun rankingTabsMatchWebsiteChannelsInsteadOfInventedTimePeriods() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(config(server), { null })
            for ((period, expected) in listOf(RankingPeriod.DAY to "1", RankingPeriod.WEEK to "2", RankingPeriod.MONTH to "3")) {
                server.enqueue(response("""{"succ":true,"result":{"most_clicks":[{"id":1,"title":"人气"}],"most_favorites":[{"id":2,"title":"收藏"}],"most_search":[{"id":3,"title":"搜索"}]}}"""))
                assertEquals(expected, api.ranking(period).single().id)
            }
            assertEquals(listOf("人气榜", "收藏榜", "搜索榜"), RankingPeriod.entries.map { api.capabilities.rankingTitles[it] })
        }
    }

}
