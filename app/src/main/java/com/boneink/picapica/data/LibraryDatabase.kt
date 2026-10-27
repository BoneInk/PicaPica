package com.boneink.picapica.data

import androidx.room.*
import com.boneink.picapica.network.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

@Entity(tableName = "comics", indices = [Index("source")])
data class ComicRecord(
    @PrimaryKey val key: String, val source: String, val remoteId: String,
    val title: String, val author: String, val coverUrl: String, val description: String,
    val tagsJson: String, val chapterCount: Int, val finished: Boolean, val allowDownload: Boolean,
) {
    fun comic() = Comic(remoteId, title, author, coverUrl, description,
        JSONArray(tagsJson).let { a -> (0 until a.length()).map { a.getString(it) } }, chapterCount, finished, allowDownload)
    companion object {
        fun from(config: SourceConfig, c: Comic) = ComicRecord(config.comicKey(c.id), config.key, c.id, c.title,
            c.author, c.coverUrl, c.description, JSONArray(c.tags).toString(), c.chapterCount, c.finished, c.allowDownload)
    }
}
@Entity(tableName = "chapters", indices = [Index("comicKey")])
data class ChapterRecord(@PrimaryKey val key: String, val comicKey: String, val remoteId: String, val title: String, val chapterOrder: Int) {
    fun chapter() = Chapter(remoteId, title, chapterOrder)
}
@Entity(tableName = "pages", primaryKeys = ["chapterKey", "pageIndex"], indices = [Index("chapterKey")])
data class PageRecord(
    val chapterKey: String, val pageIndex: Int, val remoteId: String, val url: String,
    val localPath: String? = null, val sha256: String? = null, val byteCount: Long = 0,
    val width: Int = 0, val height: Int = 0,
) {
    fun image() = PageImage(remoteId, pageIndex, url, localPath, width, height)
}
@Entity(tableName = "downloads", indices = [Index("comicKey")])
data class DownloadRecord(
    @PrimaryKey val chapterKey: String, val comicKey: String, val source: String, val remoteComicId: String,
    val comicTitle: String, val chapterTitle: String, val chapterOrder: Int, val coverUrl: String,
    val state: String = QUEUED, val completedPages: Int = 0, val totalPages: Int = 0,
    val error: String? = null, val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val QUEUED = "QUEUED"
        const val RUNNING = "RUNNING"
        const val PAUSED = "PAUSED"
        const val FAILED = "FAILED"
        const val COMPLETE = "COMPLETE"
    }
}
@Entity(tableName = "progress", indices = [Index("updatedAt")])
data class ReadingProgress(@PrimaryKey val comicKey: String, val chapterOrder: Int, val pageIndex: Int, val updatedAt: Long = System.currentTimeMillis())

@Dao
interface LibraryDao {
    @Upsert suspend fun putComic(value: ComicRecord)
    @Query("SELECT * FROM comics WHERE `key` = :key") suspend fun comic(key: String): ComicRecord?
    @Upsert suspend fun putChapters(values: List<ChapterRecord>)
    @Query("SELECT * FROM chapters WHERE comicKey = :key ORDER BY chapterOrder") suspend fun chapters(key: String): List<ChapterRecord>
    @Upsert suspend fun putPages(values: List<PageRecord>)
    @Upsert suspend fun putPage(value: PageRecord)
    @Query("SELECT * FROM pages WHERE chapterKey = :key ORDER BY pageIndex") suspend fun pages(key: String): List<PageRecord>
    @Query("DELETE FROM pages WHERE chapterKey = :key") suspend fun clearPages(key: String)
    @Upsert suspend fun putDownload(value: DownloadRecord)
    @Query("SELECT * FROM downloads WHERE chapterKey = :key") suspend fun download(key: String): DownloadRecord?
    @Query("SELECT * FROM downloads WHERE source = :source ORDER BY updatedAt DESC") fun downloads(source: String): Flow<List<DownloadRecord>>
    @Query("SELECT * FROM downloads WHERE comicKey = :key ORDER BY chapterOrder") suspend fun comicDownloads(key: String): List<DownloadRecord>
    @Query("UPDATE downloads SET error = NULL WHERE coverUrl = :url AND state = 'COMPLETE' AND error = '封面暂未保存，联网后可校验修复'")
    suspend fun clearCoverWarning(url: String)
    @Upsert suspend fun putProgress(value: ReadingProgress)
    @Query("SELECT * FROM progress WHERE comicKey = :key") suspend fun progress(key: String): ReadingProgress?
    @Query("SELECT c.* FROM comics c JOIN progress p ON c.`key` = p.comicKey WHERE c.source = :source ORDER BY p.updatedAt DESC LIMIT 30") fun recent(source: String): Flow<List<ComicRecord>>
}

@Database(entities = [ComicRecord::class, ChapterRecord::class, PageRecord::class, DownloadRecord::class, ReadingProgress::class], version = 1, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() { abstract fun library(): LibraryDao }
