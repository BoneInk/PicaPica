package com.boneink.picapica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boneink.picapica.data.*
import com.boneink.picapica.network.*
import com.boneink.picapica.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingProgressTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitPosition(text: String) { compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("reader-position").assertTextEquals(text) }.isSuccess } }
    @Test fun eachChapterKeepsItsPageAndReadMarkersAfterDatabaseAndActivityRestart() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val original = app.container
        val storage = "reading-memory-${System.nanoTime()}"
        FixtureServer().use { fixture ->
            val config = SourceConfig(fixture.server.url("/"))
            var container = AppContainer(app, config, storage)
            container.readerSettings.setMode(ReadingMode.HORIZONTAL)
            runBlocking { container.repo.login(FixtureServer.ACCOUNT, FixtureServer.PASSWORD) }
            app.installTestContainer(container)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var model: PicaViewModel
                    scenario.onActivity { model = ViewModelProvider(it)[PicaViewModel::class.java]; model.openComic("comic1") }
                    compose.waitUntil(15_000) { model.state.value.chapters.size == 2 && !model.state.value.loading }
                    compose.onNodeWithTag("read-continue").assertTextContains("开始阅读", substring = true).performClick()
                    waitPosition("1 / 3")
                    compose.onNodeWithTag("reader-pager").performTouchInput { swipeLeft() }
                    waitPosition("2 / 3")
                    compose.waitUntil(10_000) { runBlocking { container.repo.chapterProgress("comic1", 1)?.pageIndex == 1 } }
                    compose.onNodeWithText("下一章").performClick()
                    waitPosition("1 / 3")
                    compose.waitUntil(10_000) { runBlocking { container.repo.progress("comic1")?.chapterOrder == 2 } }
                    compose.onNodeWithTag("reader-pager").performTouchInput { swipeLeft() }; waitPosition("2 / 3")
                    compose.onNodeWithTag("reader-pager").performTouchInput { swipeLeft() }; waitPosition("3 / 3")
                    compose.onNodeWithTag("reader-back").performClick()
                    compose.waitUntil(10_000) { model.state.value.chapterProgress[2]?.completed == true }
                    compose.onNodeWithTag("chapter-progress-1", useUnmergedTree = true).performScrollTo().assertTextContains("读至第 2 页", substring = true)
                    compose.onNodeWithTag("chapter-progress-2", useUnmergedTree = true).performScrollTo().assertTextContains("上次阅读  已读", substring = true)
                    capture(app, "chapter-reading-states")
                    compose.onNodeWithTag("chapter-1").performScrollTo().performClick(); waitPosition("2 / 3")
                    compose.onNodeWithTag("reader-back").performClick()
                    compose.waitUntil(10_000) { model.state.value.lastRead?.chapterOrder == 1 }
                    compose.onNodeWithTag("chapter-progress-2", useUnmergedTree = true).performScrollTo().assertTextContains("已读", substring = true)
                }
                container.db.close()
                container = AppContainer(app, config, storage); app.installTestContainer(container)
                assertTrue(container.repo.signedIn.value)
                runBlocking {
                    assertEquals(1, container.repo.chapterProgress("comic1", 1)!!.pageIndex)
                    assertTrue(container.repo.chapterProgress("comic1", 2)!!.completed)
                }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var model: PicaViewModel
                    scenario.onActivity { model = ViewModelProvider(it)[PicaViewModel::class.java]; model.openComic("comic1") }
                    compose.waitUntil(15_000) { model.state.value.chapterProgress.size == 2 && !model.state.value.loading }
                    compose.onNodeWithTag("read-continue").assertTextContains("继续阅读", substring = true).performClick(); waitPosition("2 / 3")
                    compose.onNodeWithTag("reader-mode-vertical").performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader-pages").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("reader-position").assertTextEquals("2 / 3")
                    compose.onNodeWithTag("reader-back").performClick()
                    compose.waitUntil(10_000) { !model.state.value.loading && model.state.value.screen == Screen.DETAIL }
                }
            } finally { container.db.close(); app.installTestContainer(original); app.deleteDatabase("$storage.db") }
        }
    }
    @Test fun skippedChaptersDownloadsAndOtherSourcesDoNotBecomeReadAndCompletionIsSticky() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>(); val storage = "reading-isolation-${System.nanoTime()}"
        val container = AppContainer(app, storageName = storage)
        try {
            val pica = container.repository("pica"); val xx = container.repository("xiangxiang")
            pica.saveProgress("101", 3, 1, 4)
            assertNull(pica.chapterProgress("101", 1)); assertNull(xx.chapterProgress("101", 3))
            xx.saveProgress("101", 3, 3, 4); xx.saveProgress("101", 3, 1, 4)
            assertTrue(xx.chapterProgress("101", 3)!!.completed); assertEquals(1, xx.chapterProgress("101", 3)!!.pageIndex)
            xx.saveProgress("101", 3, 1, 5)
            assertFalse("New pages added to a chapter must still be read", xx.chapterProgress("101", 3)!!.completed)
            xx.saveProgress("101", 4, 0, 4)
            xx.saveProgress("101", 3, 2, 4, makeCurrent = false)
            assertEquals(4, xx.progress("101")!!.chapterOrder)
            assertFalse(pica.chapterProgress("101", 3)!!.completed)
            pica.prepareDownload(Comic("download-only", "只下载"), Chapter("ep", "第一话", 1))
            assertNull(pica.chapterProgress("download-only", 1)); assertTrue(pica.dao.chapterProgress(pica.config.comicKey("download-only")).first().isEmpty())
        } finally { container.db.close(); app.deleteDatabase("$storage.db") }
    }
}
