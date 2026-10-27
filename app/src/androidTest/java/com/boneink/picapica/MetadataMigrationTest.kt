package com.boneink.picapica

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.data.*
import com.boneink.picapica.network.digest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MetadataMigrationTest {
    @Test fun upgradeFromVersionOneKeepsDownloadsProgressTagsAndEncryptedSession() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val name = "migration-${System.nanoTime()}"
        val dbFile = app.getDatabasePath("$name.db").also { it.parentFile!!.mkdirs() }
        val page = File(app.filesDir, "$name/downloads/page.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(3, 1, 4, 1, 5)) }
        val cover = File(app.filesDir, "$name/downloads/cover.png").apply { writeBytes(byteArrayOf(2, 7, 1, 8)) }
        val originalPage = page.readBytes(); val originalCover = cover.readBytes()
        val sessions = SessionStore(app, "$name.session").apply { saveToken(FixtureServer.TOKEN) }
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("com.boneink.picapica.data.LibraryDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO comics VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>("source:book", "source", "book", "Legacy story", "Author", "https://example.invalid/cover.png", "Intro", "[\"magic\"]", 1, 1, 1))
            db.execSQL("INSERT INTO chapters VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>("source:book:1", "source:book", "ep1", "Chapter one", 1))
            db.execSQL("INSERT INTO pages VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>("source:book:1", 0, "page1", "https://example.invalid/page.png", page.absolutePath, digest(originalPage), originalPage.size, 1, 1))
            db.execSQL("INSERT INTO downloads VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>("source:book:1", "source:book", "source", "book", "Legacy story", "Chapter one", 1, "https://example.invalid/cover.png", DownloadRecord.COMPLETE, 1, 1, null, 123L))
            db.execSQL("INSERT INTO progress VALUES (?, ?, ?, ?)", arrayOf<Any?>("source:book", 1, 0, 123L))
            db.version = 1
        }
        val db = Room.databaseBuilder(app, LibraryDatabase::class.java, "$name.db").addMigrations(MIGRATION_1_2).build()
        try {
            val dao = db.library()
            val comic = requireNotNull(dao.comic("source:book")).comic()
            assertEquals(listOf("magic"), comic.tags); assertTrue(comic.finished); assertTrue(comic.categories.isEmpty())
            assertEquals(1, dao.chapters("source:book").size)
            assertEquals(DownloadRecord.COMPLETE, dao.downloads("source").first().single().state)
            assertEquals(0, dao.progress("source:book")!!.pageIndex)
            val savedPage = dao.pages("source:book:1").single()
            assertEquals(page.absolutePath, savedPage.localPath); assertEquals(digest(originalPage), savedPage.sha256)
            assertArrayEquals(originalPage, page.readBytes()); assertArrayEquals(originalCover, cover.readBytes())
            assertEquals(FixtureServer.TOKEN, sessions.readToken())
            assertEquals(2, db.openHelper.readableDatabase.version)
            dao.putComic(ComicRecord.from(com.boneink.picapica.network.SourceConfig(), comic.copy(categories = listOf("生肉"))))
        } finally { db.close(); app.deleteDatabase("$name.db"); page.parentFile!!.deleteRecursively(); sessions.clear() }
    }
}
