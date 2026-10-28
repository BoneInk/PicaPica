package com.boneink.picapica

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.SynchronousExecutor
import com.boneink.picapica.data.DownloadRecord
import com.boneink.picapica.network.SourceConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CoreFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun loginSearchReadDownloadAndOfflineWithPersistedProgress() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        FixtureServer().use { fixture ->
            val storage = "e2e-${System.currentTimeMillis()}"
            val container = AppContainer(app, SourceConfig(fixture.server.url("/")), storage)
            app.installTestContainer(container)
            app.getSharedPreferences("e2e.bootstrap", Context.MODE_PRIVATE).edit()
                .putString("base", fixture.server.url("/").toString()).putString("storage", storage).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithTag("empty-action").performClick()
                compose.onNodeWithTag("login-account").performTextInput(FixtureServer.ACCOUNT)
                compose.onNodeWithTag("login-password").performTextInput("wrong-password")
                compose.onNodeWithTag("login-submit").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("login-error").fetchSemanticsNodes().isNotEmpty() }
                capture(app, "login-retry-error-polished")
                assertNull(container.repo.sessions.readToken())
                compose.onNodeWithTag("login-password").performTextReplacement(FixtureServer.PASSWORD)
                compose.onNodeWithTag("login-submit").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("comic-comic1").fetchSemanticsNodes().isNotEmpty() }
                capture(app, "home")
                compose.onNodeWithTag("open-search").performClick()
                compose.onNodeWithTag("search-input").performTextInput("雨后")
                compose.onNodeWithTag("search-submit").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("comic-comic1").fetchSemanticsNodes().isNotEmpty() }
                capture(app, "search")
                compose.onNodeWithTag("comic-comic1").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("chapter-1").fetchSemanticsNodes().isNotEmpty() }
                capture(app, "detail")
                compose.onNodeWithTag("read-continue").performClick()
                waitImage(0)
                compose.onNodeWithTag("reader-pages").performScrollToIndex(1)
                waitImage(1)
                compose.waitUntil(10_000) { runBlocking { container.repo.progress("comic1")?.pageIndex == 1 } }
                capture(app, "reader-online")
                compose.onNodeWithTag("reader-back").performClick()
                compose.onNodeWithTag("download-chapters").performClick()
                compose.onNodeWithTag("download-confirm").performClick()
                val key = container.config.chapterKey("comic1", 1)
                // Loopback is reachable even when Android's Internet validation probe is blocked.
                // Supply only the network constraint through the official test driver; run the real worker.
                val work = WorkManager.getInstance(app)
                compose.waitUntil(10_000) { work.getWorkInfosForUniqueWork("picapica.download:$key").get().isNotEmpty() }
                val id = work.getWorkInfosForUniqueWork("picapica.download:$key").get().first().id
                WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(id)
                compose.waitUntil(30_000) { runBlocking { container.repo.dao.download(key)?.state == DownloadRecord.COMPLETE } }
                val rows = runBlocking { container.repo.dao.pages(key) }
                assertEquals(3, rows.size)
                assertTrue(rows.all { container.repo.files.isValid(it) })
                compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
                // Search is now a secondary screen; return to its home entry before switching tabs.
                compose.onNodeWithTag("search-input").assertExists()
                compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
                compose.onNodeWithTag("nav-library").performClick()
                compose.onNodeWithTag("download-status-1", useUnmergedTree = true).assertTextContains("离线可读", substring = true)
                capture(app, "library")
                fixture.close()
                compose.onNodeWithTag("download-comic1-1").performClick()
                compose.onNodeWithText("离线阅读").assertIsDisplayed()
                waitImage(1)
                compose.onNodeWithTag("reader-position").assertTextEquals("2 / 3")
                compose.onNodeWithTag("reader-mode-horizontal").performClick()
                compose.onNodeWithTag("reader-pager").assertExists()
                compose.onNodeWithTag("reader-position").assertTextEquals("2 / 3")
                capture(app, "reader-offline")
                assertTrue(fixture.violations.toString(), fixture.violations.isEmpty())
                assertTrue(fixture.paths.contains("/auth/sign-in"))
                assertTrue(fixture.paths.contains("/comics/advanced-search"))
                val exitAt = System.currentTimeMillis()
                scenario.onActivity { androidx.lifecycle.ViewModelProvider(it)[com.boneink.picapica.ui.PicaViewModel::class.java].goBack() }
                compose.waitUntil(10_000) { runBlocking { container.repo.progress("comic1")?.let { it.pageIndex == 1 && it.updatedAt >= exitAt } == true } }
            }
            assertEquals(FixtureServer.TOKEN, container.repo.sessions.readToken())
            val prefs = File(app.applicationInfo.dataDir, "shared_prefs/$storage.session.xml").readText()
            assertFalse(prefs.contains(FixtureServer.TOKEN))
            assertFalse(prefs.contains(FixtureServer.PASSWORD))
            container.db.close()
        }
    }
    private fun waitImage(index: Int) {
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("第 ${index + 1} 页已加载").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader-page-$index").assertIsDisplayed()
    }
}

fun capture(context: Context, name: String) {
    // Direct adb instrumentation does not apply Gradle's animationsDisabled option.
    // Wait for the keyboard/window exit animation so evidence shows the settled UI.
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    Thread.sleep(400)
    val directory = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
    val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
    File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}
