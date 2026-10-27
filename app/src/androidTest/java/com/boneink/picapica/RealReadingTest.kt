package com.boneink.picapica

import android.content.Context
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import coil.Coil
import coil.ImageLoader
import com.boneink.picapica.data.DownloadRecord
import com.boneink.picapica.network.*
import com.boneink.picapica.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real backend and real system-scheduled worker; dedicated DB/files, existing encrypted session. */
@RunWith(AndroidJUnit4::class)
class RealReadingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<PicaPicaApp>()
    private val args get() = InstrumentationRegistry.getArguments()
    private fun waitTag(tag: String) { compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitImage(index: Int) { compose.waitUntil(60_000) { compose.onAllNodesWithContentDescription("第 ${index + 1} 页已加载").fetchSemanticsNodes().isNotEmpty() } }
    private fun status(vararg values: Pair<String, String>) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { values.forEach { (k, v) -> putString(k, v) } })
    }

    @Test fun realOnlineReadAndDownload() = runBlocking {
        assumeTrue(args.getString("realComicFlow") == "true")
        val id = requireNotNull(args.getString("comicId"))
        assertTrue(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        assertTrue(app.container.repo.signedIn.value)
        assertTrue("Do not interfere with active user downloads", app.container.repo.downloads.first().none {
            it.state in setOf(DownloadRecord.RUNNING, DownloadRecord.QUEUED)
        })
        val storage = "real-reading-${System.currentTimeMillis()}"
        val container = AppContainer(app, storageName = storage, sessionName = "picapica.session")
        val comic = container.repo.api.comic(id)
        check((comic.title + comic.tags.joinToString()).contains("非H", ignoreCase = true) || args.getString("generalAudienceConfirmed") == "true") {
            "Use a confirmed non-explicit work for this test"
        }
        assertTrue("The service must permit downloading this work", comic.allowDownload)
        val chapters = container.repo.api.chapters(id)
        val chapter = chapters.first()
        val key = container.config.chapterKey(id, chapter.order)
        assertNull("Never replace a user's existing download", app.container.repo.dao.download(key))
        // Clear an interrupted run of this same isolated probe, never a production download.
        WorkManager.getInstance(app).cancelUniqueWork("picapica.download:$key").result.get()
        val pages = container.repo.api.pages(id, chapter.order)
        check(pages.size in 2..80) { "Choose a short chapter with 2 to 80 pages" }
        val allowed = (pages.map { it.url } + comic.coverUrl).toSet()
        app.installTestContainer(container)
        val imageRequests = AtomicInteger()
        // Only fetch the selected general-audience work. Disable caches to prove online loads.
        Coil.setImageLoader(ImageLoader.Builder(app).memoryCache(null).diskCache(null).okHttpClient(
            container.network.imageHttp.newBuilder().addInterceptor { chain ->
                if (chain.request().url.toString() !in allowed) Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(404).message("Not part of this test").body(ByteArray(0).toResponseBody()).build()
                else { imageRequests.incrementAndGet(); chain.proceed(chain.request()) }
            }.build()).build())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitTag("nav-library")
            scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].openComic(id) }
            waitTag("chapter-${chapter.order}")
            compose.onNodeWithTag("chapter-${chapter.order}").performScrollTo().performClick()
            waitImage(0)
            compose.onNodeWithTag("reader-pages").performScrollToIndex(1)
            waitImage(1)
            withTimeout(10_000) { container.repo.recent.first { it.any { row -> row.remoteId == id } } }
            compose.waitUntil(10_000) { runBlocking { container.repo.progress(id)?.pageIndex == 1 } }
            assertTrue(imageRequests.get() >= 2)
            compose.onNodeWithTag("reader-mode-horizontal").performClick()
            waitTag("reader-pager")
            compose.onNodeWithTag("reader-position").assertTextEquals("2 / ${pages.size}")
            capture(app, "real-reader-online")
            status("phase" to "real_online_page_two_loaded", "pages" to pages.size.toString())
            compose.onNodeWithTag("reader-back").performClick()
            compose.onNodeWithTag("download-chapters").performClick()
            compose.onNodeWithTag("download-confirm").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            status("phase" to "real_download_submitted")
            val row = withTimeout(240_000) {
                container.repo.downloads.first { rows -> rows.any { it.chapterKey == key && it.state in setOf(DownloadRecord.COMPLETE, DownloadRecord.FAILED) } }
                    .first { it.chapterKey == key }
            }
            assertEquals(row.error ?: "Real worker must complete", DownloadRecord.COMPLETE, row.state)
            val work = WorkManager.getInstance(app)
            compose.waitUntil(15_000) { work.getWorkInfosForUniqueWork("picapica.download:$key").get().let { rows -> rows.isNotEmpty() && rows.all { it.state.isFinished } } }
            val records = container.repo.dao.pages(key)
            assertEquals(pages.size, records.size)
            assertTrue(records.all { container.repo.files.isValid(it) })
            app.getSharedPreferences("real.reading.bootstrap", Context.MODE_PRIVATE).edit()
                .putString("storage", storage).putString("comicId", id).putInt("order", chapter.order).putInt("pages", pages.size).commit()
            val manifest = JSONObject().put("comicId", id).put("chapterOrder", chapter.order).put("pages", pages.size)
                .put("bytes", records.sumOf { it.byteCount }).put("all_sha256_valid", true).put("system_worker_without_test_driver", true)
            File(app.getExternalFilesDir(null), "real-reading-manifest.json").writeText(manifest.toString(2))
            compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("nav-library").performClick()
            waitTag("download-$id-${chapter.order}")
            capture(app, "real-download-complete")
            status("real_comic_id" to id, "downloaded_pages" to pages.size.toString(), "verified_bytes" to records.sumOf { it.byteCount }.toString(),
                "online_images_loaded" to imageRequests.get().toString(), "system_worker" to "actual_constraints_no_test_driver", "sha256" to "all_pages_verified")
        }
        container.db.close()
    }

    @Test fun repairPersistentCoverOfDownloadedChapter() = runBlocking {
        assumeTrue(args.getString("realCoverRepair") == "true")
        val prefs = app.getSharedPreferences("real.reading.bootstrap", Context.MODE_PRIVATE)
        val storage = requireNotNull(prefs.getString("storage", null))
        val id = requireNotNull(prefs.getString("comicId", null))
        val order = prefs.getInt("order", -1)
        val container = AppContainer(app, storageName = storage, sessionName = "picapica.session")
        try {
            val row = requireNotNull(container.repo.dao.download(container.config.chapterKey(id, order)))
            status("previous_cover_warning" to (row.error ?: "none"), "cover_file_before" to (container.repo.files.coverFile(row.coverUrl) != null).toString())
            try { container.repo.files.cacheCover(row.coverUrl) }
            catch (e: Exception) { status("cover_fetch_error" to (e.message?.take(160) ?: e.javaClass.simpleName)); throw e }
            assertNotNull(container.repo.files.coverFile(row.coverUrl))
            container.repo.dao.clearCoverWarning(row.coverUrl)
            status("cover_repair" to "passed", "cover_bytes" to container.repo.files.coverFile(row.coverUrl)!!.length().toString())
        } finally { container.db.close() }
    }

    @Test fun realOfflineReadAfterProcessRestart() = runBlocking {
        assumeTrue(args.getString("realOffline") == "true")
        assertNull("The offline phase requires all emulator networks disconnected",
            app.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork)
        val prefs = app.getSharedPreferences("real.reading.bootstrap", Context.MODE_PRIVATE)
        val storage = requireNotNull(prefs.getString("storage", null))
        val id = requireNotNull(prefs.getString("comicId", null))
        val order = prefs.getInt("order", -1)
        val count = prefs.getInt("pages", 0)
        val container = AppContainer(app, storageName = storage, sessionName = "picapica.session")
        val key = container.config.chapterKey(id, order)
        assertEquals(DownloadRecord.COMPLETE, container.repo.dao.download(key)!!.state)
        assertEquals(1, container.repo.progress(id)!!.pageIndex)
        assertTrue(container.repo.dao.pages(key).all { container.repo.files.isValid(it) })
        app.installTestContainer(container)
        val networkAttempts = AtomicInteger()
        val allImageAttempts = AtomicInteger()
        val chapterUrls = container.repo.dao.pages(key).map { it.url }.toSet()
        Coil.setImageLoader(ImageLoader.Builder(app).memoryCache(null).diskCache(null).okHttpClient(
            container.network.imageHttp.newBuilder().addInterceptor { chain ->
                allImageAttempts.incrementAndGet()
                if (chain.request().url.toString() in chapterUrls) networkAttempts.incrementAndGet()
                throw java.io.IOException("Offline proof forbids HTTP images")
            }.build()).build())
        ActivityScenario.launch(MainActivity::class.java).use {
            waitTag("nav-library")
            compose.onNodeWithTag("nav-library").performClick()
            waitTag("download-$id-$order")
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("offline-cover-loaded-$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            capture(app, "real-offline-cover-restart")
            compose.onNodeWithTag("download-$id-$order").performClick()
            waitImage(1)
            compose.onNodeWithText("离线阅读").assertIsDisplayed()
            compose.onNodeWithTag("reader-position").assertTextEquals("2 / $count")
            for (index in 0 until count) {
                if (index == 0) compose.onNodeWithTag("reader-pager").performTouchInput { swipeRight() }
                else compose.onNodeWithTag("reader-pager").performTouchInput { swipeLeft() }
                compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("reader-position").assertTextEquals("${index + 1} / $count") }.isSuccess }
                waitImage(index)
                compose.onNodeWithTag("reader-page-$index").assertIsDisplayed()
            }
            repeat(count - 2) { compose.onNodeWithTag("reader-pager").performTouchInput { swipeRight() } }
            waitImage(1)
            assertEquals("Offline covers must also use persistent files", 0, allImageAttempts.get())
            assertTrue(container.repo.readerPages(id, order, true).all { it.localPath != null })
            assertEquals("Chapter images must load from verified files", 0, networkAttempts.get())
            capture(app, "real-reader-offline-restart")
            status("offline_restart" to "passed", "restored_position" to "2 / $count", "offline_pages_rendered" to count.toString(), "http_chapter_image_requests" to "0", "image_caches" to "disabled", "reading_mode" to "horizontal", "offline_cover" to "rendered_from_persistent_file", "http_image_requests" to allImageAttempts.get().toString())
        }
        container.db.close()
    }
}
