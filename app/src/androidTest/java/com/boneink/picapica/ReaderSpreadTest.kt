package com.boneink.picapica

import android.app.UiAutomation
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.data.AppSettings
import com.boneink.picapica.data.ReadingMode
import com.boneink.picapica.network.SourceConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSpreadTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun horizontalModePairsFullHeightPagesWithSynchronizedZoom() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val original = app.container
        val storage = "reader-spread-${System.nanoTime()}"
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

        fun rotate(landscape: Boolean) {
            val rotation = if (landscape) UiAutomation.ROTATION_FREEZE_90 else UiAutomation.ROTATION_FREEZE_0
            val orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            assertTrue(automation.setRotation(rotation))
            compose.waitUntil(15_000) { app.resources.configuration.orientation == orientation }
        }

        FixtureServer(chapterPageCounts = mapOf(2 to 4)).use { fixture ->
            val container = AppContainer(app, SourceConfig(fixture.server.url("/")), storage)
            try {
                container.readerSettings.setMode(ReadingMode.HORIZONTAL)
                container.settings.update { it.copy(volumePaging = true) }
                runBlocking { container.repo.login(FixtureServer.ACCOUNT, FixtureServer.PASSWORD) }
                app.installTestContainer(container)
                rotate(landscape = false)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    compose.onNodeWithTag("nav-profile").performClick()
                    compose.onNodeWithTag("open-settings").performClick()
                    compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("setting-double-page"))
                    compose.onNodeWithTag("setting-double-page").assertIsOff().performClick().assertIsOn()
                    assertTrue(AppSettings(app, "$storage.settings").values.value.doublePage)
                    compose.onNodeWithContentDescription("返回").performClick()
                    compose.onNodeWithTag("nav-home").performClick()
                    waitTag("comic-comic1")
                    compose.onNodeWithTag("comic-comic1").performClick()
                    continueReading()
                    waitTag("reader-pager")
                    rotate(landscape = false)
                    waitReaderOrientation(landscape = false)
                    waitPosition("1–2 / 3")
                    assertSpreadGeometry()
                    compose.onNodeWithTag("reader-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
                    compose.onNodeWithTag("reader-mode-vertical").performClick()
                    waitPosition("2 / 3")
                    compose.onNodeWithTag("reader-pages").assertIsDisplayed()
                    compose.onNodeWithTag("reader-mode-horizontal").performClick()
                    waitPosition("1–2 / 3")

                    rotate(landscape = true)
                    waitReaderOrientation(landscape = true)
                    waitPosition("1–2 / 3")
                    assertSpreadGeometry()
                    compose.waitUntil(15_000) {
                        (1..2).all { page -> compose.onAllNodesWithContentDescription("第 $page 页已加载", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                    }
                    compose.onNodeWithContentDescription("第 1 页已加载", useUnmergedTree = true).assertIsDisplayed()
                    compose.onNodeWithContentDescription("第 2 页已加载", useUnmergedTree = true).assertIsDisplayed()
                    capture(app, "reader-full-height-spread")

                    val firstPage = compose.onNodeWithTag("reader-page-0", useUnmergedTree = true)
                    val secondPage = compose.onNodeWithTag("reader-page-1", useUnmergedTree = true)
                    val zoomed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已放大")
                    secondPage.performTouchInput { doubleClick() }.assert(zoomed)
                    firstPage.assert(zoomed)
                    secondPage.performTouchInput { swipeLeft() }
                    waitPosition("1–2 / 3")
                    firstPage.performTouchInput { doubleClick() }.assert(!zoomed)
                    secondPage.assert(!zoomed)
                    rotate(landscape = false)
                    waitReaderOrientation(landscape = false)
                    waitPosition("1–2 / 3")
                    compose.runOnIdle { container.settings.update { it.copy(doublePage = false) } }
                    waitPosition("2 / 3")
                    compose.runOnIdle { container.settings.update { it.copy(doublePage = true) } }

                    rotate(landscape = true)
                    waitReaderOrientation(landscape = true)
                    waitPosition("1–2 / 3")
                    compose.onNodeWithTag("reader-pager").performTouchInput { swipeLeft() }
                    waitPosition("3 / 3")
                    assertEquals(bounds("reader-pager").width, bounds("reader-page-2").width, 1f)

                    compose.onNodeWithText("下一章").performClick()
                    waitPosition("1–2 / 4")
                    compose.onNodeWithTag("reader-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
                    waitPosition("3–4 / 4")
                    compose.waitUntil(10_000) {
                        runBlocking { container.repo.chapterProgress("comic1", 2)?.let { it.completed && it.pageIndex == 2 } == true }
                    }
                    scenario.onActivity {
                        it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
                        it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP))
                    }
                    waitPosition("1–2 / 4")
                    compose.onNodeWithTag("reader-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
                    waitPosition("3–4 / 4")
                    compose.onNodeWithTag("reader-back").performClick()
                    continueReading()
                    waitPosition("3–4 / 4")
                    compose.runOnIdle { container.settings.update { it.copy(doublePage = false) } }
                    waitPosition("3 / 4")
                    assertTrue(fixture.violations.toString(), fixture.violations.isEmpty())
                }
            } finally {
                automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
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

    private fun continueReading() {
        val enabledButton = hasTestTag("read-continue") and isEnabled()
        compose.waitUntil(15_000) { compose.onAllNodes(enabledButton).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(enabledButton).performClick()
    }

    private fun waitPosition(text: String) {
        try {
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasTestTag("reader-position") and hasText(text)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("Expected reader position $text\n${compose.onRoot().printToString(maxDepth = 4)}", error)
        }
    }

    private fun waitReaderOrientation(landscape: Boolean) {
        compose.waitUntil(15_000) {
            val reader = compose.onAllNodesWithTag("reader", useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            reader != null && (reader.width > reader.height) == landscape
        }
    }

    private fun assertSpreadGeometry() {
        val reader = bounds("reader")
        val pager = bounds("reader-pager")
        val first = bounds("reader-page-0")
        val second = bounds("reader-page-1")
        assertEquals(reader.height, first.height, 1f)
        assertEquals(reader.height, second.height, 1f)
        assertEquals(pager.width / 2, first.width, 1f)
        assertEquals(pager.width / 2, second.width, 1f)
        assertEquals(first.right, second.left, 1f)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
}
