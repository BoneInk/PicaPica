package com.boneink.picapica

import android.app.UiAutomation
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.data.ReadingMode
import com.boneink.picapica.network.SourceConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabletLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun tabletUsesSideNavigationAndKeepsTheReadingPageOnRotation() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        assumeTrue(app.resources.configuration.smallestScreenWidthDp >= 720)
        val original = app.container
        val storage = "tablet-layout-${System.nanoTime()}"
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val density = app.resources.displayMetrics.density

        FixtureServer().use { fixture ->
            val dispatcher = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl?.encodedPath != "/comics") return dispatcher.dispatch(request)
                    val docs = JSONArray()
                    for (i in 1..9) docs.put(fixture.comic().put("_id", "comic$i").put("title", "雨后观测站 $i"))
                    val data = JSONObject().put("comics", JSONObject().put("docs", docs).put("page", 1).put("pages", 1))
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(JSONObject().put("code", 200).put("data", data).toString())
                }
            }
            val container = AppContainer(app, SourceConfig(fixture.server.url("/")), storage)
            try {
                container.readerSettings.setMode(ReadingMode.VERTICAL)
                runBlocking { container.repo.login(FixtureServer.ACCOUNT, FixtureServer.PASSWORD) }
                app.installTestContainer(container)
                assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0))
                ActivityScenario.launch(MainActivity::class.java).use {
                    waitTag("comic-comic3")
                    compose.onNodeWithTag("side-navigation").assertIsDisplayed()
                    compose.onNodeWithTag("nav-home").assertIsSelected()
                    compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
                    assertEquals(bounds("comic-comic1").top, bounds("comic-comic3").top, 1f)
                    capture(app, "tablet-home-portrait")

                    compose.onNodeWithTag("comic-comic1").performClick()
                    waitTag("read-continue")
                    assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
                    compose.waitUntil(15_000) { app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                    waitTag("detail-summary-pane")
                    compose.onNodeWithTag("chapter-1").assertIsDisplayed()
                    assertTrue(bounds("detail-summary-pane").right <= bounds("detail-chapters").left)
                    capture(app, "tablet-detail-landscape")

                    compose.onNodeWithTag("chapter-1").performClick()
                    waitTag("reader-pages")
                    compose.onNodeWithTag("reader-pages").performScrollToIndex(1)
                    compose.onNodeWithTag("reader-position").assertTextEquals("2 / 3")
                    val viewport = bounds("reader-pages")
                    val page = bounds("reader-page-1")
                    assertEquals(720f * density, page.width, 1f)
                    assertEquals(viewport.center.x, page.center.x, 1f)
                    compose.onNodeWithTag("reader-mode-horizontal").assertIsDisplayed()
                    capture(app, "tablet-reader-landscape")

                    assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0))
                    compose.waitUntil(15_000) { app.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                    waitTag("reader-position")
                    compose.onNodeWithTag("reader-position").assertTextEquals("2 / 3")
                    compose.onNodeWithTag("reader-mode-horizontal").performClick()
                    waitTag("reader-pager")
                    compose.onNodeWithTag("reader-position").assertTextEquals("2 / 3")
                    compose.onNodeWithTag("reader-pager").performTouchInput { swipeLeft() }
                    compose.onNodeWithTag("reader-position").assertTextEquals("3 / 3")
                    assertTrue(fixture.violations.toString(), fixture.violations.isEmpty())
                }
            } finally {
                automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
                app.installTestContainer(original)
                container.db.close()
                app.deleteDatabase("$storage.db")
            }
        }
    }

    private fun waitTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
}
