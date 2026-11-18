package com.boneink.picapica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.boneink.picapica.data.AppSettings
import com.boneink.picapica.download.ChapterDownloadWorker
import com.boneink.picapica.network.SourceConfig
import com.boneink.picapica.ui.PicaViewModel
import com.boneink.picapica.ui.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContinuousReadingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val nextPath = "/comics/comic1/order/2/pages"
    private fun withReaderFixture(failOnce: Boolean = false, block: (FixtureServer, AppContainer, ActivityScenario<MainActivity>, PicaViewModel, String) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>(); val original = app.container
        val storage = "continuous-reader-${System.nanoTime()}"
        FixtureServer().use { fixture ->
            fixture.failNextChapterOnce.set(failOnce)
            val container = AppContainer(app, SourceConfig(fixture.server.url("/")), storage)
            runBlocking { container.repo.login(FixtureServer.ACCOUNT, FixtureServer.PASSWORD) }
            app.installTestContainer(container)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var model: PicaViewModel
                    scenario.onActivity { model = ViewModelProvider(it)[PicaViewModel::class.java] }
                    block(fixture, container, scenario, model, storage)
                }
            } finally { container.db.close(); app.installTestContainer(original); app.deleteDatabase("$storage.db") }
        }
    }
    private fun openReader(scenario: ActivityScenario<MainActivity>, model: PicaViewModel) {
        scenario.onActivity { model.openComic("comic1") }
        compose.waitUntil(15_000) { model.state.value.chapters.size == 2 && !model.state.value.loading }
        compose.onNodeWithTag("read-continue").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reader-pages").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitLoaded(slot: Int, page: Int) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("reader-page-$slot") and hasContentDescription("第 $page 页已加载")).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun leaveReader(scenario: ActivityScenario<MainActivity>, container: AppContainer) {
        val exitAt = System.currentTimeMillis()
        scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].goBack() }
        compose.waitUntil(10_000) { runBlocking { (container.repo.progress("comic1")?.updatedAt ?: 0) >= exitAt } }
    }
    @Test fun appendsWithNoGapWithoutMovingTheReaderAndRestoresTheCrossChapterBookmark() = withReaderFixture { fixture, container, scenario, model, _ ->
        openReader(scenario, model)
        compose.onNodeWithTag("reader-pages").performScrollToIndex(2)
        compose.waitUntil(15_000) { model.state.value.readerChapters.size == 2 && !model.state.value.nextChapterLoading }
        assertEquals(1, model.state.value.chapter?.order)
        assertNull(runBlocking { container.repo.chapterProgress("comic1", 2) })
        compose.onNodeWithContentDescription("隐藏工具栏").performClick()
        compose.onNodeWithTag("reader-pages").performScrollToIndex(2)
        waitLoaded(2, 3); waitLoaded(3, 1)
        val before = compose.onNodeWithTag("reader-page-2").fetchSemanticsNode().boundsInRoot
        val after = compose.onNodeWithTag("reader-page-3").fetchSemanticsNode().boundsInRoot
        assertTrue(before.height > 0 && after.height > 0)
        assertEquals("Chapter images meet without padding", before.bottom, after.top, 0.5f)
        capture(container.appContext, "continuous-reading-seam")
        compose.onNodeWithTag("reader-pages").performScrollToIndex(3)
        compose.waitUntil(10_000) { runBlocking { container.repo.progress("comic1")?.chapterOrder == 2 } }
        assertTrue(runBlocking { container.repo.chapterProgress("comic1", 1)!!.completed })
        compose.onNodeWithTag("reader-pages").performScrollToIndex(2)
        compose.waitUntil(10_000) { model.state.value.chapter?.order == 1 }
        compose.onNodeWithTag("reader-pages").performScrollToIndex(4)
        compose.waitUntil(10_000) { model.state.value.chapter?.order == 2 && model.state.value.initialPage == 1 }
        assertEquals(1, fixture.paths.count { it == nextPath })
        leaveReader(scenario, container)
        assertEquals(2, runBlocking { container.repo.progress("comic1")!!.chapterOrder })
        assertEquals(1, runBlocking { container.repo.progress("comic1")!!.pageIndex })
        compose.onNodeWithTag("read-continue").performClick()
        compose.waitUntil(15_000) { model.state.value.screen == Screen.READER && model.state.value.chapter?.order == 2 && !model.state.value.loading }
        compose.onNodeWithTag("reader-position").assertTextEquals("2 / 3")
        compose.onNodeWithTag("reader-mode-horizontal").performClick()
        compose.onNodeWithTag("reader-pager").assertExists()
        compose.onNodeWithTag("reader-position").assertTextEquals("2 / 3")
        leaveReader(scenario, container)
    }
    @Test fun failedAppendKeepsTheCurrentChapterReadableUntilAnExplicitRetry() = withReaderFixture(failOnce = true) { fixture, container, scenario, model, _ ->
        openReader(scenario, model)
        compose.onNodeWithTag("reader-pages").performScrollToIndex(2)
        compose.waitUntil(15_000) { model.state.value.nextChapterError != null && !model.state.value.nextChapterLoading }
        waitLoaded(2, 3)
        assertEquals(1, fixture.paths.count { it == nextPath })
        assertEquals(1, model.state.value.readerChapters.size)
        assertNull(model.state.value.error)
        compose.onNodeWithTag("reader-pages").performScrollToIndex(3)
        compose.onNodeWithTag("reader-next-retry").performScrollTo().performClick()
        compose.waitUntil(15_000) { model.state.value.readerChapters.size == 2 && !model.state.value.nextChapterLoading }
        assertNull(model.state.value.nextChapterError)
        assertEquals(2, fixture.paths.count { it == nextPath })
        assertNull(runBlocking { container.repo.chapterProgress("comic1", 2) })
        leaveReader(scenario, container)
    }
    @Test fun turningOffAutoLoadPersistsAndLeavesTheManualNextChapterButton() = withReaderFixture { fixture, container, scenario, model, storage ->
        scenario.onActivity { model.navigate(Screen.SETTINGS) }
        compose.onNodeWithTag("settings-list").performScrollToIndex(3)
        compose.onNodeWithTag("setting-auto-next-chapter").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("setting-auto-next-chapter").assertIsOff()
        assertFalse(AppSettings(container.appContext, "$storage.settings").values.value.autoLoadNextChapter)
        openReader(scenario, model)
        compose.onNodeWithTag("reader-pages").performScrollToIndex(2)
        waitLoaded(2, 3)
        compose.onNodeWithTag("reader-pages").performScrollToIndex(3)
        compose.onNodeWithTag("reader-manual-next").assertExists()
        assertEquals(0, fixture.paths.count { it == nextPath })
        compose.onNodeWithTag("reader-manual-next").performScrollTo().performClick()
        compose.waitUntil(15_000) { model.state.value.chapter?.order == 2 && !model.state.value.loading }
        assertEquals(1, fixture.paths.count { it == nextPath })
        assertEquals(listOf(2), model.state.value.readerChapters.map { it.chapter.order })
        leaveReader(scenario, container)
    }
    @Test fun downloadedChaptersAppendWithTheServerStopped() = withReaderFixture { fixture, container, scenario, model, _ ->
        val repo = container.repo
        val records = runBlocking {
            val comic = repo.comic("comic1")
            repo.chapters(comic.id).map { chapter ->
                val record = repo.prepareDownload(comic, chapter)
                val result = TestListenableWorkerBuilder<ChapterDownloadWorker>(container.appContext)
                    .setInputData(workDataOf("sourceKey" to repo.config.key, "accountId" to repo.accountId, "chapterKey" to record.chapterKey)).build().doWork()
                assertEquals(ListenableWorker.Result.success(), result)
                repo.dao.download(record.chapterKey)!!
            }
        }
        fixture.close()
        scenario.onActivity { model.openDownloaded(records.first()) }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reader-pages").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader-pages").performScrollToIndex(2)
        compose.waitUntil(15_000) { model.state.value.readerChapters.size == 2 && !model.state.value.nextChapterLoading }
        assertTrue(model.state.value.offline)
        assertTrue(model.state.value.readerChapters.all { section -> section.pages.all { it.localPath != null } })
        compose.onNodeWithTag("reader-pages").performScrollToIndex(3)
        waitLoaded(3, 1)
        compose.waitUntil(10_000) { model.state.value.chapter?.order == 2 }
        leaveReader(scenario, container)
    }
}
