package com.boneink.picapica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boneink.picapica.data.*
import com.boneink.picapica.network.SourceConfig
import com.boneink.picapica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChapterSortTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun assertChapterAbove(first: Int, second: Int) {
        compose.onNodeWithTag("chapter-$second").performScrollTo()
        compose.waitUntil(5_000) {
            val a = compose.onNodeWithTag("chapter-$first").fetchSemanticsNode().boundsInRoot
            val b = compose.onNodeWithTag("chapter-$second").fetchSemanticsNode().boundsInRoot
            a.height > 0 && b.height > 0 && a.top < b.top
        }
    }
    @Test fun reverseDirectoryPersistsWhileContinueReadingAndNextChapterKeepTheirMeaning() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>(); val original = app.container
        val storage = "chapter-sort-${System.nanoTime()}"
        FixtureServer().use { fixture ->
            val config = SourceConfig(fixture.server.url("/")); var container = AppContainer(app, config, storage)
            container.readerSettings.setMode(ReadingMode.HORIZONTAL)
            runBlocking { container.repo.login(FixtureServer.ACCOUNT, FixtureServer.PASSWORD); container.repo.saveProgress("comic1", 1, 1, 3) }
            app.installTestContainer(container)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var model: PicaViewModel
                    scenario.onActivity { model = ViewModelProvider(it)[PicaViewModel::class.java]; model.openComic("comic1") }
                    compose.waitUntil(15_000) { !model.state.value.loading && model.state.value.chapters.size == 2 && model.state.value.lastRead != null }
                    compose.onNodeWithTag("chapter-sort").performScrollTo().assertTextContains("正序", substring = true)
                    assertChapterAbove(1, 2)
                    compose.onNodeWithTag("chapter-sort").performScrollTo().performClick()
                    compose.waitUntil(5_000) { model.state.value.chapterSort == ChapterSort.DESCENDING }
                    assertChapterAbove(2, 1)
                    assertEquals(listOf(1, 2), model.state.value.chapters.map { it.order })
                    capture(app, "chapter-directory-descending")
                    compose.onNodeWithTag("read-continue").performClick()
                    compose.waitUntil(15_000) { model.state.value.chapter?.order == 1 && !model.state.value.loading }
                    compose.onNodeWithTag("reader-position").assertTextEquals("2 / 3")
                    compose.onNodeWithText("下一章").performClick()
                    compose.waitUntil(15_000) { model.state.value.chapter?.order == 2 && !model.state.value.loading }
                    compose.onNodeWithTag("reader-position").assertTextEquals("1 / 3")
                    compose.onNodeWithTag("reader-back").performClick()
                    compose.waitUntil(10_000) { model.state.value.screen == Screen.DETAIL && model.state.value.lastRead?.chapterOrder == 2 }
                }
                container.db.close(); container = AppContainer(app, config, storage); app.installTestContainer(container)
                assertEquals(ChapterSort.DESCENDING, container.readerSettings.chapterSort.value)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var model: PicaViewModel
                    scenario.onActivity { model = ViewModelProvider(it)[PicaViewModel::class.java]; model.openComic("comic1") }
                    compose.waitUntil(15_000) { !model.state.value.loading && model.state.value.chapters.size == 2 }
                    compose.onNodeWithTag("chapter-sort").performScrollTo().assertTextContains("倒序", substring = true)
                    assertChapterAbove(2, 1)
                    compose.onNodeWithTag("chapter-sort").performScrollTo().performClick()
                    compose.waitUntil(5_000) { model.state.value.chapterSort == ChapterSort.ASCENDING }
                    assertChapterAbove(1, 2)
                    assertEquals(ChapterSort.ASCENDING, ReaderSettings(app, "$storage.reader").chapterSort.value)
                }
            } finally { container.db.close(); app.installTestContainer(original); app.deleteDatabase("$storage.db"); app.deleteSharedPreferences("$storage.reader") }
        }
    }
}
