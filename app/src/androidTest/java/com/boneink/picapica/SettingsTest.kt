package com.boneink.picapica

import android.content.Context
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.data.*
import com.boneink.picapica.download.ChapterDownloader
import com.boneink.picapica.network.SourceConfig
import com.boneink.picapica.ui.PicaViewModel
import com.boneink.picapica.ui.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitTag(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun setup(app: PicaPicaApp, fixture: FixtureServer, storage: String): AppContainer {
        SessionStore(app, "$storage.session").saveToken(FixtureServer.TOKEN)
        return AppContainer(app, SourceConfig(fixture.server.url("/")), storage).also(app::installTestContainer)
    }
    private fun choose(tag: String, text: String) { compose.onNodeWithTag(tag).performScrollTo().performClick();compose.onNodeWithText(text).performClick() }
    @Test fun preferencesPersistAndReaderWindowAndVolumeKeysHaveScopedEffects() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val storage = "settings-persist-${System.nanoTime()}"
            val container = setup(app, fixture, storage)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: PicaViewModel
                var originalOrientation = 0;var originalBrightness = 0f;var originalAwake = 0
                scenario.onActivity { vm = ViewModelProvider(it)[PicaViewModel::class.java];originalOrientation = it.requestedOrientation
                    originalBrightness = it.window.attributes.screenBrightness;originalAwake = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON }
                waitTag("nav-profile");compose.onNodeWithTag("nav-profile").performClick();compose.onNodeWithTag("open-settings").performClick();waitTag("settings-list")
                choose("setting-mode", "横向翻页")
                choose("setting-orientation", "竖屏")
                compose.onNodeWithTag("setting-volume").performScrollTo().performClick();compose.onNodeWithTag("setting-volume").assertIsOn()
                compose.onNodeWithTag("setting-brightness").performScrollTo().performClick()
                compose.onNodeWithTag("brightness-follow-system").performClick()
                compose.onNodeWithTag("brightness-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(.35f) }
                compose.onNodeWithTag("brightness-confirm").performClick()
                choose("setting-quality", "低")
                container.repo.api.pages("comic1", 1);assertEquals("low", fixture.qualityHeaders.last())
                choose("setting-quality", "高")
                container.repo.api.pages("comic1", 1);assertEquals("high", fixture.qualityHeaders.last())
                choose("setting-theme", "深色")
                compose.onNodeWithTag("settings-list").performScrollToIndex(0);compose.waitForIdle();capture(app, "settings-dark-0.9.0")
                scenario.onActivity { vm.openComic("comic1") };waitTag("read-continue")
                compose.onNodeWithTag("read-continue").performClick();waitTag("reader-pager")
                compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("第 1 页已加载").fetchSemanticsNodes().isNotEmpty() }
                scenario.onActivity { assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, it.requestedOrientation)
                    assertEquals(.35f, it.window.attributes.screenBrightness, .01f)
                    assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0);assertNotNull(it.readerVolumeHandler) }
                compose.onNodeWithTag("reader-page-0").performTouchInput { doubleClick(center) }
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
                compose.waitUntil(10_000) { compose.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty() }
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)
                compose.waitUntil(10_000) { compose.onAllNodesWithText("1 / 3").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("reader-pager").performTouchInput { swipeLeft() }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty() }
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)
                compose.waitUntil(10_000) { compose.onAllNodesWithText("1 / 3").fetchSemanticsNodes().isNotEmpty() }
                val exitAt = System.currentTimeMillis()
                compose.onNodeWithTag("reader-back").performClick();waitTag("read-continue")
                compose.waitUntil(10_000) { runBlocking { container.repo.progress("comic1")?.let { it.pageIndex == 0 && it.updatedAt >= exitAt } == true } }
                scenario.onActivity { assertEquals(originalOrientation, it.requestedOrientation);assertEquals(originalBrightness, it.window.attributes.screenBrightness, .01f)
                    assertEquals(originalAwake, it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);assertNull(it.readerVolumeHandler) }
                compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick();waitTag("settings-list")
                scenario.recreate();waitTag("settings-list")
                val reloaded = AppSettings(app, "$storage.settings").values.value
                assertEquals(AppTheme.DARK, reloaded.theme);assertEquals(ImageQuality.HIGH, reloaded.imageQuality)
                assertEquals(ReaderOrientation.PORTRAIT, reloaded.orientation);assertTrue(reloaded.volumePaging);assertEquals(.35f, reloaded.brightness, .01f)
                assertEquals(ReadingMode.HORIZONTAL, ReaderSettings(app, "$storage.reader").mode.value)
                app.getSharedPreferences("settings-e2e.bootstrap", Context.MODE_PRIVATE).edit().putString("storage", storage).commit()
            }
            container.db.close()
        }
    }
    @Test fun clearingTemporaryCachePreservesDownloadsCoversSessionsAndProgress() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val storage = "settings-cache-${System.nanoTime()}"
            val container = setup(app, fixture, storage)
            val comic = container.repo.comic("comic1");val chapter = container.repo.chapters("comic1").first()
            val row = container.repo.prepareDownload(comic, chapter);assertTrue(ChapterDownloader(container.repo).download(row.chapterKey))
            container.repo.saveProgress("comic1", 1, 1)
            val cover = requireNotNull(container.repo.files.coverFile(comic.coverUrl));val coverBytes = cover.readBytes()
            val disk = coil.disk.DiskCache.Builder().directory(File(app.cacheDir, storage)).maxSizeBytes(10L * 1024 * 1024).build()
            disk.openEditor("cache-probe")!!.apply { data.toFile().writeText("temporary image");metadata.toFile().writeText("test");commit() }
            coil.Coil.setImageLoader(coil.ImageLoader.Builder(app).diskCache(disk).okHttpClient(container.network.imageHttp).build())
            ActivityScenario.launch(MainActivity::class.java).use {
                waitTag("nav-profile");compose.onNodeWithTag("nav-profile").performClick();compose.onNodeWithTag("open-settings").performClick();waitTag("settings-list")
                compose.onNodeWithTag("settings-list").performScrollToIndex(0);capture(app, "settings-light-0.9.0")
                compose.onNodeWithTag("setting-cache").performScrollTo().performClick();compose.onNodeWithTag("confirm-clear-cache").performClick()
                compose.waitUntil(15_000) { disk.openSnapshot("cache-probe")?.use { false } ?: true }
                assertEquals(FixtureServer.TOKEN, container.repo.sessions.readToken())
                assertEquals(DownloadRecord.COMPLETE, container.repo.dao.download(row.chapterKey)!!.state)
                assertTrue(container.repo.dao.pages(row.chapterKey).all { container.repo.files.isValid(it) })
                assertArrayEquals(coverBytes, cover.readBytes());assertEquals(1, container.repo.progress("comic1")!!.pageIndex)
            }
            coil.Coil.imageLoader(app).shutdown();container.db.close()
        }
    }
}
