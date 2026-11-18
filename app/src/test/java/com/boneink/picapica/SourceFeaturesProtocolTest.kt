package com.boneink.picapica

import com.boneink.picapica.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SourceFeaturesProtocolTest {
    private fun response(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)
    private fun calendar(signed: Boolean): String {
        val date = LocalDate.now(ZoneId.of("Asia/Taipei"))
        val days = JSONArray().apply { if (signed) put(date.dayOfMonth.toString().padStart(2, '0')) }
        val data = JSONObject().put(date.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")), days)
        return "<script>var calendarData = JSON.parse('$data');</script>"
    }
    @Test fun picaCheckInVerifiesServerStateAndDoesNotRepeatAnAlreadySignedDay() = runTest {
        MockWebServer().use { server ->
            val api = PicaClient(SourceConfig(server.url("/")), { "fixture-token" })
            fun profile(signed: Boolean) = response("""{"code":200,"data":{"user":{"_id":"1","name":"Reader","isPunched":$signed}}}""")
            server.enqueue(profile(false)); server.enqueue(response("""{"code":200,"data":{"res":{"status":"ok"}}}""")); server.enqueue(profile(true))
            assertTrue(api.checkIn().signed)
            assertEquals("GET", server.takeRequest().method)
            val punch = server.takeRequest(); assertEquals("/users/punch-in", punch.path); assertEquals("POST", punch.method)
            assertEquals("fixture-token", punch.getHeader("authorization")); server.takeRequest()
            server.enqueue(profile(true)); assertTrue(api.checkIn().signed)
            assertEquals("GET", server.takeRequest().method); assertEquals(4, server.requestCount)
        }
    }
    @Test fun xiangxiangCheckInUsesManualFormAndConfirmsItsCalendar() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(SourceConfig(server.url("/"), SourceKind.XIANGXIANG), { null })
            server.enqueue(response(calendar(false))); server.enqueue(response("""{"code":2000}""")); server.enqueue(response(calendar(true)))
            val result = api.checkIn(); assertTrue(result.signed); assertEquals(1, result.monthDays)
            server.takeRequest(); val post = server.takeRequest()
            assertEquals("/home/Api/signupNew.html", post.path); assertEquals("POST", post.method)
            val form = post.body.readUtf8(); assertTrue(form.contains("autoSign=false")); assertTrue(form.contains("type=1")); assertTrue(form.contains("td="))
            server.takeRequest(); server.enqueue(response(calendar(true))); assertTrue(api.checkIn().signed)
            assertEquals("GET", server.takeRequest().method); assertEquals(4, server.requestCount)
        }
    }
    @Test fun successfulCheckInResponseWithoutCalendarConfirmationIsNotReportedAsSigned() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response(calendar(false))); server.enqueue(response("""{"code":2000}""")); server.enqueue(response(calendar(false)))
            try { XiangxiangClient(SourceConfig(server.url("/"), SourceKind.XIANGXIANG), { null }).checkIn(); fail() }
            catch (e: PicaException) { assertEquals(PicaException.Kind.DATA, e.kind) }
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun homepageUsesWebsiteSectionsFiltersExternalBannersAndPreservesEscapedTitles() = runTest {
        MockWebServer().use { server ->
            val rows = JSONArray().put(JSONObject().put("name", "newest").put("title", "每日最新").put("data", JSONArray()
                .put(JSONObject().put("id", 12).put("title", "Reader's story").put("desc", "首页长数据".repeat(30_000)).put("image", "/cover.png").put("last_chapter_title", "第3话"))))
                .put(JSONObject().put("name", "article").put("title", "腐宅快报").put("data", JSONArray().put(JSONObject().put("id", 99).put("title", "站点公告"))))
            val encoded = JSONObject.quote(JSONObject().put("data", rows).toString()).replace("'", "\\'")
            server.enqueue(response("<script>let data = JSON.parse($encoded);</script><div class='index-slide'><a href='/home/book/index/id/12'><img src='/banner.jpg'></a><a href='https://advertising.invalid/'><img src='/ad.jpg'></a></div>"))
            val api = XiangxiangClient(SourceConfig(server.url("/"), SourceKind.XIANGXIANG), { null })
            val feed = api.homepage(); assertEquals("newest", feed.sections.single().key)
            assertEquals("Reader's story", feed.sections.single().comics.single().title)
            assertEquals("第3话", feed.sections.single().comics.single().latestChapter)
            assertEquals("12", feed.banners.single().comicId); assertTrue(feed.articles.single().url.endsWith("/home/article/content/99"))
            server.takeRequest(); server.enqueue(response("""{"succ":true,"result":{"lastPage":false,"list":[{"id":13,"title":"分区故事"}]}}"""))
            assertEquals(3, api.homeSection("siangsiang", 2).pages)
            assertEquals("/home/api/getpage/tp/1-siangsiang-1", server.takeRequest().path)
            server.enqueue(response("""{"code":200,"result":[{"id":14,"title":"每日更新"}],"pcPagi":{"page_cnt":20}}"""))
            assertEquals(20, api.homeSection("newest", 2).pages)
            val request = server.takeRequest(); assertEquals("POST", request.method); assertTrue(request.body.readUtf8().contains("page=2"))
        }
    }
    @Test fun registrationUsesSeparateMailCodeAndCaptchaWithNoAutomaticReplay() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(SourceConfig(server.url("/"), SourceKind.XIANGXIANG), { null })
            server.enqueue(response("""{"status":0,"info":"sent"}""")); api.requestRegistrationCode("reader@example.invalid")
            val code = server.takeRequest(); assertEquals("/home/auth/regGetVerify.html", code.path); assertNull(code.getHeader("api-key"))
            assertTrue(code.body.readUtf8().contains("email=reader%40example.invalid"))
            server.enqueue(response("""{"status":0}"""))
            val form = XiangxiangRegistration("reader12", "password", "password", "reader@example.invalid", "mail123", "1234")
            api.registerXiangxiang(form)
            val request = server.takeRequest(); val body = request.body.readUtf8()
            assertEquals("/home/auth/login.html", request.path); assertTrue(body.contains("type=register")); assertTrue(body.contains("reset_token=mail123")); assertTrue(body.contains("vfycode=1234")); assertTrue(body.contains("password2=password"))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/changed")))
            try { api.registerXiangxiang(form); fail() } catch (e: PicaException) { assertEquals(PicaException.Kind.CREDENTIALS, e.kind) }
            assertEquals(3, server.requestCount)
            try { api.requestRegistrationCode("invalid"); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun siteSelectionFollowsKnownCanonicalRedirectWithoutForwardingSessionCookies() = runTest {
        MockWebServer().use { original -> MockWebServer().use { mirror ->
            val config = SourceConfig(original.url("/"), SourceKind.XIANGXIANG)
            val token = JSONObject().put("account", "Fixture").put("baseUrl", original.url("/").toString()).put("cookies", JSONArray().put("auth=fixture-secret; Path=/")).toString()
            val api = XiangxiangClient(config, { token }, mirrorBases = listOf(mirror.url("/")))
            mirror.enqueue(MockResponse().setResponseCode(301).setHeader("Location", original.url("/")))
            original.enqueue(response("<title>首页</title>")); api.selectSite(mirror.url("/"))
            assertNull(mirror.takeRequest().getHeader("Cookie")); assertTrue(original.takeRequest().getHeader("Cookie")!!.contains("auth=fixture-secret"))
            assertEquals(original.url("/"), api.currentOrigin())
            mirror.enqueue(response("<title>独立站点</title>"))
            try { api.selectSite(mirror.url("/")); fail() } catch (e: PicaException) { assertEquals(PicaException.Kind.CREDENTIALS, e.kind) }
            assertEquals(original.url("/"), api.currentOrigin())
        } }
    }
}
