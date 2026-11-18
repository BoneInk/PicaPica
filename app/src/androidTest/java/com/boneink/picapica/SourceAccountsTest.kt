package com.boneink.picapica

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.boneink.picapica.data.*
import com.boneink.picapica.download.ChapterDownloadWorker
import com.boneink.picapica.network.*
import com.boneink.picapica.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class SourceAccountsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitTag(tag: String) { compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun switchLoginReadCommentDownloadAndRestartKeepSourcesAndSessionsSeparate() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { pica -> XiangxiangFixture().use { xx ->
            val storage = "sources-${System.currentTimeMillis()}"
            val configs = mapOf(SourceKind.XIANGXIANG to SourceConfig(xx.server.url("/"), SourceKind.XIANGXIANG))
            val container = AppContainer(app, SourceConfig(pica.server.url("/")), storage, sourceConfigs = configs)
            app.installTestContainer(container)
            runBlocking { container.repo.login(FixtureServer.ACCOUNT, FixtureServer.PASSWORD) }
            val picaRepo = container.repo
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitTag("comic-comic1")
                compose.onNodeWithTag("nav-profile").performClick()
                compose.onNodeWithTag("profile-accounts").performClick()
                waitTag("accounts-list"); capture(app, "source-accounts")
                compose.onNodeWithTag("account-xiangxiang").performClick()
                waitTag("empty-action"); compose.onNodeWithTag("empty-action").performClick()
                waitTag("login-captcha"); compose.waitUntil(10_000) { !xx.requests.none { it.startsWith("/captcha.html") } }
                compose.onNodeWithTag("login-account").performTextInput("xx-reader")
                compose.onNodeWithTag("login-password").performTextInput("xx-password")
                compose.onNodeWithTag("login-captcha").performTextInput("1234")
                capture(app, "source-xiangxiang-login")
                compose.onNodeWithTag("login-submit").performClick()
                waitTag("comic-101")
                assertEquals(SourceKind.XIANGXIANG, container.repo.config.kind)
                assertNotSame(picaRepo, container.repo)
                compose.onNodeWithTag("nav-categories").performClick()
                waitTag("categories-list"); compose.onNodeWithText("校园", useUnmergedTree = true).performClick()
                waitTag("comic-101"); compose.onNodeWithTag("comic-101").performClick()
                waitTag("read-continue"); capture(app, "source-xiangxiang-detail")
                compose.onNodeWithTag("read-continue").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("第 1 页已加载").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("reader-back").performClick()
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].openComments() }
                waitTag("comment-301")
                compose.onNodeWithTag("comment-replies-301").performClick()
                waitTag("inline-reply-302")
                compose.onNodeWithTag("comment-draft").performTextInput("源切换测试评论")
                compose.onNodeWithTag("comment-send").performClick()
                compose.waitUntil(10_000) { xx.posts.any { it.contains("manhua_id=101") } }
                val xxRepo = container.repo
                val key = runBlocking {
                    val comic = xxRepo.comic("101"); val chapter = xxRepo.chapters("101").first()
                    xxRepo.saveProgress("101", chapter.order, 0)
                    xxRepo.prepareDownload(comic, chapter).chapterKey
                }
                // Switch before running the actual WorkManager worker. It must still resolve the original account.
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].switchAccount("pica") }
                waitTag("comic-comic1")
                runBlocking {
                    val result = TestListenableWorkerBuilder<ChapterDownloadWorker>(app)
                        .setInputData(workDataOf("sourceKey" to xxRepo.config.key, "accountId" to "xiangxiang", "chapterKey" to key)).build().doWork()
                    assertEquals(androidx.work.ListenableWorker.Result.success(), result)
                    val row = xxRepo.dao.download(key)!!; assertEquals(DownloadRecord.COMPLETE, row.state)
                    val page = xxRepo.dao.pages(key).single(); assertTrue(xxRepo.files.isValid(page))
                    val bitmap = BitmapFactory.decodeFile(xxRepo.files.resolve(page.localPath!!).path)
                    assertEquals(XiangxiangFixture.color(0), bitmap.getPixel(0, 10)); assertEquals(XiangxiangFixture.color(12), bitmap.getPixel(125, 10)); bitmap.recycle()
                    assertTrue(picaRepo.recent.first().none { it.remoteId == "101" })
                }
                assertEquals(FixtureServer.TOKEN, picaRepo.sessions.readToken())
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].switchAccount("xiangxiang") }
                waitTag("comic-101")
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].tab(Screen.PROFILE) }
                waitTag("profile-accounts"); compose.onNodeWithTag("profile-accounts").performClick()
                compose.onNodeWithTag("add-account").performClick()
                compose.onNodeWithTag("add-source-XIANGXIANG").performClick()
                waitTag("empty-action")
                assertFalse(container.repo.signedIn.value)
                assertTrue(xxRepo.signedIn.value)
                assertEquals(3, container.accounts.accounts.value.size)
            }
            container.accounts.select("xiangxiang")
            val cipher = File(app.applicationInfo.dataDir, "shared_prefs/$storage.session.xiangxiang.xml").readText()
            assertFalse(cipher.contains("xx-password")); assertFalse(cipher.contains("xx-secret"))
            container.db.close()
            val restored = AppContainer(app, SourceConfig(pica.server.url("/")), storage, sourceConfigs = configs)
            assertEquals("xiangxiang", restored.accounts.active.value.id)
            assertTrue(restored.repo.signedIn.value)
            assertEquals(FixtureServer.TOKEN, restored.repository("pica").sessions.readToken())
            runBlocking {
                assertEquals("101", restored.repo.recent.first().single().remoteId)
                assertEquals(1, restored.repo.readerPages("101", 1, offline = true).size)
                restored.repo.logout()
            }
            assertTrue(restored.repository("pica").signedIn.value)
            assertTrue(xx.violations.toString(), xx.violations.isEmpty())
            assertTrue(pica.violations.toString(), pica.violations.isEmpty())
            restored.db.close()
        } }
    }
    @Test fun imageReconstructionPreservesEveryPixelIncludingRemainderAndLongImages() {
        for (width in listOf(130, 137)) {
            val original = Bitmap.createBitmap(width, 20, Bitmap.Config.ARGB_8888)
            for (x in 0 until width) for (y in 0 until 20) original.setPixel(x, y, Color.rgb(x, y, 70))
            val scrambled = Bitmap.createBitmap(width, 20, Bitmap.Config.ARGB_8888)
            val stripe = width / 13
            for (i in 0 until 13) {
                val count = if (i == 12) width - stripe * i else stripe
                val destination = if (i == 12) 0 else width - stripe * (i + 1)
                for (x in 0 until count) for (y in 0 until 20) scrambled.setPixel(destination + x, y, original.getPixel(stripe * i + x, y))
            }
            val restored = XiangxiangImages.restore(scrambled, 13)
            assertTrue(original.sameAs(restored)); original.recycle(); scrambled.recycle(); restored.recycle()
        }
        val longImage = Bitmap.createBitmap(130, 4000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val restored = XiangxiangImages.restore(longImage, 13); assertTrue(longImage.sameAs(restored)); longImage.recycle(); restored.recycle()
    }
}

internal class XiangxiangFixture : AutoCloseable {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<String>()
    val posts = CopyOnWriteArrayList<String>()
    val violations = CopyOnWriteArrayList<String>()
    val registrations = CopyOnWriteArrayList<String>()
    val mailRequests = CopyOnWriteArrayList<String>()
    var failRegistrationOnce = false
    var signedToday = false
    private var favourite = false
    private var avatarPath = "/cover.png"
    private val image: ByteArray = Bitmap.createBitmap(130, 100, Bitmap.Config.ARGB_8888).let { bitmap ->
        for (x in 0 until 130) for (y in 0 until 100) bitmap.setPixel(x, y, color(12 - x / 10))
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
    }
    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath; requests += request.path.orEmpty()
                if (request.getHeader("authorization") != null || request.getHeader("api-key") != null) violations += "PicACG credentials leaked to Xiangxiang"
                fun body(text: String) = MockResponse().setBody(text).setHeader("Content-Type", "application/json")
                return when {
                    path == "/" -> {
                        val book = JSONObject().put("id", 101).put("title", "香香日常故事").put("image", "/cover.png").put("last_chapter_title", "第1话")
                        val rows = org.json.JSONArray()
                        listOf("newest" to "每日最新", "siangsiang" to "香香汉化组", "recommend" to "无码专区", "topestmh" to "最多订阅", "cnxh" to "猜你喜欢").forEach { (key, title) ->
                            rows.put(JSONObject().put("name", key).put("title", title).put("data", org.json.JSONArray().put(book)))
                        }
                        body("<script>let data = JSON.parse(" + JSONObject.quote(JSONObject().put("data", rows).toString()) + ");</script>")
                    }
                    path == "/captcha.html" -> MockResponse().setBody(Buffer().write(image)).addHeader("Set-Cookie", "PHPSESSID=captcha; Path=/")
                    path == "/home/auth/regGetVerify.html" -> { mailRequests += request.body.readUtf8(); body("""{"status":0}""") }
                    path == "/home/auth/login.html" -> {
                        val text = request.body.readUtf8()
                        if (!text.contains("vfycode=1234") || !request.getHeader("Cookie").orEmpty().contains("PHPSESSID=captcha")) violations += "captcha session missing"
                        if (text.contains("type=register")) {
                            registrations += text
                            if (failRegistrationOnce) { failRegistrationOnce = false; body("""{"status":1,"info":"邮箱验证码无效"}""") } else body("""{"status":0}""")
                        } else body("""{"status":0}""").addHeader("Set-Cookie", "auth=xx-secret; Path=/; HttpOnly")
                    }
                    path == "/home/signup" -> {
                        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei"))
                        val key = today.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))
                        body("<script>var calendarData = JSON.parse('{\"$key\":[${if (signedToday) "\"${today.dayOfMonth.toString().padStart(2, '0')}\"" else ""}]}');</script>")
                    }
                    path == "/home/Api/signupNew.html" -> { signedToday = true; body("""{"code":2000}""") }
                    path.startsWith("/home/api/getpage/tp/") -> body("""{"succ":true,"result":{"lastPage":true,"list":[{"id":101,"title":"香香日常故事","image":"/cover.png"}]}}""")
                    path == "/home/Api/getDailyUpdate.html" -> body("""{"code":200,"result":[{"id":101,"title":"香香日常故事","image":"/cover.png"}],"pcPagi":{"page_cnt":1}}""")
                    path in setOf("/home/user/index", "/home/user/index.html") -> body("<title>我的</title><form hidden><input name='type' value='login'></form><div class='user-block'><a id='header_avatar_wrap'><img class='avatar' src='$avatarPath'></a><ul class='user'><li class='name'>香香测试读者<span><span class='vipiconWord'>VIP會員</span></span></li><li class='edit-block'>剩餘自動簽到天數：0天</li><li class='edit-block1'>FU金：25 / 經驗值：100</li></ul></div><script>window.__PC_HISTORY_UID=2;</script>")
                    path == "/home/user/getComment" -> body("""{"succ":true,"result":{"is_last":1,"comment_count":1,"list":[{"id":301,"member_id":2,"username":"香香测试读者","headimage":"/cover.png","manhua_id":101,"manhua_title":"香香日常故事","content":"测试评论","reply_count":1}]}}""")
                    path == "/home/api/favorite" -> body("""{"code":0,"msg":"ok","succ":false,"result":{"lastPage":true,"data":${if (favourite) "[{\"id\":999,\"bid\":101,\"title\":\"香香日常故事\",\"image\":\"/cover.png\"}]" else "[]"}}}""")
                    path == "/home/api/dosubscribe" -> { favourite = request.requestUrl!!.queryParameter("action") == "add"; body("""{"code":0,"msg":"ok","succ":false}""") }
                    path == "/home/user/cgavatar.html" -> { avatarPath = "/new-avatar.png"; MockResponse().setResponseCode(303).setHeader("Location", "/home/user/index.html") }
                    path == "/home/book/cate.html" -> body("<ul data-str='tag'><a data-value='日常'>日常</a><a data-value='校园'>校园</a></ul>")
                    path.startsWith("/home/api/cate/tp/") || path == "/home/api/searchk" -> body("""{"succ":true,"result":{"lastPage":true,"list":[{"id":101,"title":"香香日常故事","auther":"测试作者","image":"/cover.png","keyword":"日常,校园","mhstatus":0}]}}""")
                    path == "/home/book/index/id/101" -> body("<script>let data = JSON.parse(" + JSONObject.quote(org.json.JSONArray(listOf("state", if (favourite) "200" else "1", "")).toString()) + ");</script>" + """<div class='stui-content__thumb'><a data-original='/cover.png'></a></div><div class='stui-content__detail'><h1>香香日常故事</h1><p class='data'>连载中</p><p class='data'>作者：测试作者</p><a class='taga' href='/home/index/searchkw/?keywords=日常'>日常</a><span class='detail-text'>合成测试内容</span></div>""")
                    path == "/home/api/chapter_list/tp/101" -> body("""{"succ":true,"result":{"lastPage":true,"list":[{"id":201,"title":"第一话"}]}}""")
                    path == "/home/book/capter/id/201" -> body("""<script>var randomClass=13;</script><div class='reader-cartoon-image'><img class='lazy' style='display:none' data-original='/page.png?w=130_100'></div>""")
                    path == "/home/api/getComment" -> body("""{"succ":true,"result":{"is_last":1,"comment_count":1,"list":[{"id":301,"member_id":1,"username":"测试读者","content":"测试评论","reply_count":1}]}}""")
                    path == "/home/api/getSubComment" -> body("""{"succ":true,"result":{"is_last":1,"list":[{"id":302,"member_id":2,"username":"另一读者","content":"测试回复"}]}}""")
                    path == "/home/api/addComment" -> { posts += request.body.readUtf8(); body("""{"succ":true,"code":1}""") }
                    path.endsWith(".png") -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(image))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }
    override fun close() = server.close()
    companion object { fun color(index: Int) = Color.rgb(index * 18, 70, 120) }
}
