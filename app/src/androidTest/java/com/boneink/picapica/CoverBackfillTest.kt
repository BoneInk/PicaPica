package com.boneink.picapica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boneink.picapica.data.DownloadRecord
import com.boneink.picapica.data.SessionStore
import com.boneink.picapica.download.ChapterDownloader
import com.boneink.picapica.network.SourceConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverBackfillTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun existingCompletedDownloadGetsCoverWithoutRedownloadingPages() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val storage = "cover-upgrade-${System.currentTimeMillis()}"
            SessionStore(app, "$storage.session").saveToken(FixtureServer.TOKEN)
            val source = SourceConfig(fixture.server.url("/"))
            val old = AppContainer(app, source, storage)
            val comic = old.repo.comic("comic1")
            val chapter = old.repo.chapters(comic.id).first()
            val row = old.repo.prepareDownload(comic, chapter)
            ChapterDownloader(old.repo).download(row.chapterKey)
            requireNotNull(old.repo.files.coverFile(comic.coverUrl)).delete()
            old.repo.dao.putDownload(old.repo.dao.download(row.chapterKey)!!.copy(error = "封面暂未保存，联网后可校验修复"))
            val pageRequests = (1..3).associateWith { fixture.imageCounts["/static/page$it.png"]!!.get() }
            old.db.close()
            val current = AppContainer(app, source, storage)
            app.installTestContainer(current)
            coil.Coil.setImageLoader(coil.ImageLoader.Builder(app).memoryCache(null).diskCache(null).okHttpClient(current.network.imageHttp).build())
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("nav-library").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("offline-cover-loaded-comic1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(DownloadRecord.COMPLETE, current.repo.dao.download(row.chapterKey)!!.state)
                assertNull(current.repo.dao.download(row.chapterKey)!!.error)
                assertTrue(current.repo.dao.pages(row.chapterKey).all { current.repo.files.isValid(it) })
                pageRequests.forEach { (index, count) -> assertEquals(count, fixture.imageCounts["/static/page$index.png"]!!.get()) }
                assertEquals(1, current.db.openHelper.readableDatabase.version)
                capture(app, "cover-backfill-0.3.0")
            }
            current.db.close()
        }
    }
}
