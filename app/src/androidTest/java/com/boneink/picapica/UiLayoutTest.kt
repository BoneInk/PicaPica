package com.boneink.picapica

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boneink.picapica.data.SessionStore
import com.boneink.picapica.network.SourceConfig
import com.boneink.picapica.ui.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Navigation and visual acceptance with synthetic artwork; never saves account content. */
@RunWith(AndroidJUnit4::class)
class UiLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitTag(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun channelsShortcutsAndContentRemainUsableInLightDarkAndLargeText() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val original = fixture.server.dispatcher
            val titles = listOf("雨后观测站", "星期天来信", "末班列车", "夏日回声", "海边的房间", "山间手记")
            val arts = titles.indices.map(::art)
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (path.startsWith("/static/showcase")) {
                        assertNull(request.getHeader("authorization"))
                        val index = path.removePrefix("/static/showcase").removeSuffix(".png").toInt()
                        return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(arts[index]))
                    }
                    if (path == "/comics/leaderboard" || path == "/comics" && request.requestUrl!!.queryParameter("c") == null) {
                        val docs = JSONArray()
                        titles.forEachIndexed { i, title -> docs.put(fixture.comic().put("_id", if (i == 0) "comic1" else "showcase$i").put("title", title)
                            .put("author", listOf("纸间工作室", "山川", "岸本晴")[i % 3])
                            .put("epsCount", 2 + i * 3).put("thumb", JSONObject().put("fileServer", fixture.server.url("/").toString()).put("path", "showcase$i.png"))) }
                        val comics = if (path.endsWith("leaderboard")) docs else JSONObject().put("docs", docs).put("page", 1).put("pages", 1).put("total", docs.length())
                        return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("code", 200).put("data", JSONObject().put("comics", comics)).toString())
                    }
                    return original.dispatch(request)
                }
            }
            val storage = "ui-layout-${System.nanoTime()}"
            SessionStore(app, "$storage.session").saveToken(FixtureServer.TOKEN)
            val container = AppContainer(app, SourceConfig(fixture.server.url("/")), storage)
            app.installTestContainer(container)
            val dark = mutableStateOf(false)
            val scale = mutableStateOf(1f)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[PicaViewModel::class.java]
                    activity.setContent {
                        val density = LocalDensity.current.density
                        CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) {
                            PicaTheme(darkTheme = dark.value) { PicaPicaScreen(vm) }
                        }
                    }
                }
                waitTag("comic-comic1")
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("remote-cover-showcase2", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                snap(app, "ui-home-light-0.9.0")
                compose.onNodeWithTag("nav-search").assertDoesNotExist()
                compose.onNodeWithTag("home-history").assertDoesNotExist()
                compose.onNodeWithTag("home-favourites").assertDoesNotExist()
                compose.onNodeWithTag("home-downloads").assertDoesNotExist()
                compose.onNodeWithTag("nav-library").performClick();compose.onNodeWithTag("library-history").performClick();compose.onNodeWithTag("library-history").assertIsSelected()
                compose.onNodeWithTag("library-downloads").performClick();compose.onNodeWithTag("library-downloads").assertIsSelected()
                compose.onNodeWithTag("library-cloud").performClick();compose.onNodeWithTag("library-cloud").assertIsSelected()
                compose.onNodeWithTag("nav-home").performClick()
                compose.onNodeWithTag("home-categories").performClick();waitTag("category-official-discussion")
                compose.onNodeWithTag("home-categories").assertIsSelected();snap(app, "ui-categories-light-0.9.0")
                compose.onNodeWithTag("home-rankings").performClick();waitTag("ranked-comic1")
                compose.onNodeWithTag("ranking-D7").performClick();compose.onNodeWithTag("ranking-D7").assertIsSelected()
                snap(app, "ui-ranking-light-0.9.0")
                compose.onNodeWithTag("nav-home").performClick();compose.onNodeWithTag("comic-comic1").performScrollTo().performClick();waitTag("chapter-1")
                snap(app, "ui-detail-light-0.9.0")
                compose.onNodeWithTag("open-comments").performScrollTo().performClick();waitTag("comment-c1");snap(app, "ui-comments-light-0.9.0")
                compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
                compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
                compose.onNodeWithTag("nav-profile").performClick();waitTag("recent-comment-mine1")
                compose.onNodeWithTag("profile-favourites").assertDoesNotExist();compose.onNodeWithTag("profile-history").assertDoesNotExist();compose.onNodeWithTag("profile-downloads").assertDoesNotExist()
                snap(app, "ui-profile-light-0.9.0")
                compose.runOnIdle { dark.value = true }
                compose.onNodeWithTag("nav-home").performClick();waitTag("comic-comic1");snap(app, "ui-home-dark-0.9.0")
                compose.runOnIdle { scale.value = 1.3f }
                compose.onNodeWithTag("home-categories").performClick();waitTag("category-official-discussion")
                compose.onNodeWithTag("home-categories").assertIsDisplayed();snap(app, "ui-categories-large-dark-0.9.0")
                compose.onNodeWithTag("category-official-discussion").performClick();waitTag("comment-boardRoot")
                compose.onNodeWithTag("comment-replies-boardRoot").performScrollTo().performClick();waitTag("comment-boardReply")
                compose.onNodeWithTag("comment-draft").performTextInput("仅布局验证，不发送")
                compose.onNodeWithTag("comment-send").assertIsEnabled()
                assertTrue(fixture.boardRepliesPosted.isEmpty())
                assertTrue(fixture.violations.toString(), fixture.violations.isEmpty())
            }
            container.db.close()
        }
    }
    private fun snap(app: PicaPicaApp, name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        capture(app, name)
    }
    private fun art(index: Int): ByteArray {
        val palettes = listOf(0xFF264955, 0xFFB56F72, 0xFF495675, 0xFF5E7768, 0xFF7373A4, 0xFFC28D64)
        val bitmap = Bitmap.createBitmap(480, 680, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap);val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(palettes[index].toInt())
        paint.color = 0xFFFBE5BB.toInt();canvas.drawCircle(120f + index * 19, 142f, 56f, paint)
        paint.color = 0x403C474F;canvas.save();canvas.rotate(if (index % 2 == 0) 18f else -18f, 240f, 400f)
        canvas.drawRect(200f, 210f, 550f, 760f, paint)
        paint.color = 0x4091B4B7;paint.strokeWidth = 3f
        repeat(8) { canvas.drawLine(200f, 240f + it * 42, 550f, 240f + it * 42, paint) };canvas.restore()
        paint.color = Color.WHITE;paint.textSize = 34f
        canvas.drawText(listOf("AFTER THE RAIN", "SUNDAY LETTER", "LAST TRAIN", "SUMMER ECHO", "BY THE SEA", "FIELD NOTES")[index], 32f, 558f, paint)
        paint.textSize = 18f;canvas.drawText("PICAPICA / ORIGINAL ART", 32f, 596f, paint)
        return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);bitmap.recycle();out.toByteArray() }
    }
}
