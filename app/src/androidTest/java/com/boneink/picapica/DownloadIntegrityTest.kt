package com.boneink.picapica

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boneink.picapica.data.DownloadRecord
import com.boneink.picapica.download.ChapterDownloader
import com.boneink.picapica.network.SourceConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class DownloadIntegrityTest {
    @Test fun resumesPartialDownloadAndRepairsOnlyCorruptPage() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val container = AppContainer(app, SourceConfig(fixture.server.url("/")), "integrity-${System.currentTimeMillis()}")
            try {
                val repo = container.repo
                repo.login(FixtureServer.ACCOUNT, FixtureServer.PASSWORD)
                val comic = repo.comic("comic1")
                val chapter = repo.chapters("comic1").first()
                val row = repo.prepareDownload(comic, chapter)
                fixture.failSecondImageOnce.set(true)
                try { ChapterDownloader(repo).download(row.chapterKey); fail("Expected simulated interruption") } catch (_: IOException) { }
                assertEquals(DownloadRecord.FAILED, repo.dao.download(row.chapterKey)!!.state)
                assertEquals(1, repo.dao.download(row.chapterKey)!!.completedPages)
                assertTrue(ChapterDownloader(repo).download(row.chapterKey))
                assertEquals(1, fixture.imageCounts["/static/page1.png"]!!.get())
                assertEquals(2, fixture.imageCounts["/static/page2.png"]!!.get())
                val cover = requireNotNull(repo.files.coverFile(comic.coverUrl))
                assertTrue(cover.isFile)
                val coverBytes = cover.readBytes()
                cover.writeBytes(coverBytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
                repo.files.cacheCover(comic.coverUrl)
                assertArrayEquals(coverBytes, cover.readBytes())
                val pages = repo.dao.pages(row.chapterKey)
                val file = repo.files.resolve(pages[0].localPath!!)
                val damaged = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
                file.writeBytes(damaged)
                assertFalse(repo.files.isValid(pages[0]))
                assertTrue(ChapterDownloader(repo).download(row.chapterKey))
                assertEquals(2, fixture.imageCounts["/static/page1.png"]!!.get())
                assertEquals(1, fixture.imageCounts["/static/page3.png"]!!.get())
                assertTrue(repo.dao.pages(row.chapterKey).all { repo.files.isValid(it) })
                assertTrue(repo.files.root.walkTopDown().none { it.name.contains(".part-") })
                // A failed optional cover must not hide a fully downloaded chapter.
                cover.delete()
                fixture.failCoverOnce.set(true)
                assertTrue(ChapterDownloader(repo).download(row.chapterKey))
                assertEquals(DownloadRecord.COMPLETE, repo.dao.download(row.chapterKey)!!.state)
                assertNotNull(repo.dao.download(row.chapterKey)!!.error)
                val requestsBeforeRepair = fixture.imageCounts["/static/page3.png"]!!.get()
                assertNull(repo.prepareDownload(comic, chapter).error)
                assertNotNull(repo.files.coverFile(comic.coverUrl))
                assertEquals(requestsBeforeRepair, fixture.imageCounts["/static/page3.png"]!!.get())
                repo.logout()
                fixture.close()
                assertEquals(3, repo.readerPages("comic1", 1, offline = true).size)
                assertNull(repo.sessions.readToken())
                assertTrue(fixture.violations.toString(), fixture.violations.isEmpty())
                try { repo.files.resolve("../escape"); fail() } catch (_: IllegalArgumentException) { }
            } finally { container.db.close() }
        }
    }
}
