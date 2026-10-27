package com.boneink.picapica

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.data.DownloadRecord
import com.boneink.picapica.network.SourceConfig
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run separately AFTER CoreFlowTest and force-stop, with emulator radios disabled. */
@RunWith(AndroidJUnit4::class)
class OfflineRestartTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun opensVerifiedFilesAfterProcessRestartWithoutServerOrNetwork() {
        org.junit.Assume.assumeTrue("Run scripts/verify-emulator.sh for the process-restart phase",
            InstrumentationRegistry.getArguments().getString("offlineRestart") == "true")
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val prefs = app.getSharedPreferences("e2e.bootstrap", Context.MODE_PRIVATE)
        val base = requireNotNull(prefs.getString("base", null)) { "先运行 CoreFlowTest" }
        val storage = requireNotNull(prefs.getString("storage", null))
        val container = AppContainer(app, SourceConfig(base.toHttpUrl()), storage)
        app.installTestContainer(container)
        assertTrue(container.repo.signedIn.value)
        val key = container.config.chapterKey("comic1", 1)
        assertEquals(DownloadRecord.COMPLETE, runBlocking { container.repo.dao.download(key)!!.state })
        assertEquals(1, runBlocking { container.repo.progress("comic1")!!.pageIndex })
        // Reading must use persistent downloads, not Coil/HTTP cache.
        val imageRequests = java.util.concurrent.atomic.AtomicInteger()
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(app).memoryCache(null).diskCache(null).okHttpClient(
            container.network.imageHttp.newBuilder().addInterceptor { imageRequests.incrementAndGet(); throw java.io.IOException("Offline proof") }.build()).build())
        assertEquals(com.boneink.picapica.data.ReadingMode.HORIZONTAL, container.readerSettings.mode.value)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("nav-library").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("download-comic1-1").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("offline-cover-loaded-comic1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            capture(app, "offline-cover-after-restart-0.3.0")
            compose.onNodeWithTag("download-comic1-1").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("第 2 页已加载").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("离线阅读").assertIsDisplayed()
            compose.onNodeWithTag("reader-position").assertTextEquals("2 / 3")
            compose.onNodeWithTag("reader-pager").assertExists()
            for (index in 0..2) {
                if (index == 0) compose.onNodeWithTag("reader-pager").performTouchInput { swipeRight() }
                else compose.onNodeWithTag("reader-pager").performTouchInput { swipeLeft() }
                compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("reader-position").assertTextEquals("${index + 1} / 3") }.isSuccess }
            }
            assertEquals(0, imageRequests.get())
            capture(app, "reader-after-process-restart-airplane")
            val exitAt = System.currentTimeMillis()
            scenario.onActivity { androidx.lifecycle.ViewModelProvider(it)[com.boneink.picapica.ui.PicaViewModel::class.java].goBack() }
            compose.waitUntil(10_000) { runBlocking { container.repo.progress("comic1")?.let { it.pageIndex == 2 && it.updatedAt >= exitAt } == true } }
        }
        container.db.close()
    }
}
