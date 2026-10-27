package com.boneink.picapica.data

import androidx.room.withTransaction
import com.boneink.picapica.download.LocalFiles
import com.boneink.picapica.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PicaRepository(
    val config: SourceConfig,
    val db: LibraryDatabase,
    val sessions: SessionStore,
    val files: LocalFiles,
    private val network: NetworkStack,
) {
    val dao = db.library()
    private val token = MutableStateFlow(sessions.readToken())
    val signedIn = MutableStateFlow(token.value != null)
    private val name = MutableStateFlow("PicaPica 读者")
    val displayName = name.asStateFlow()
    private val profile = MutableStateFlow<UserProfile?>(null)
    val selfProfile = profile.asStateFlow()
    suspend fun refreshProfile(): UserProfile {
        val session = token.value
        return api.profile().also { if (session != null && token.value == session) { profile.value = it; name.value = it.name } }
    }
    val api = PicaClient(config, { token.value }, network.apiHttp, network.apiBackoff, imageQuality = network.imageQuality)
    val downloads = dao.downloads(config.key)
    val recent = dao.recent(config.key)

    suspend fun login(account: String, password: String) {
        require(account.isNotBlank() && password.isNotEmpty()) { "请填写账号和密码" }
        val newToken = api.signIn(account.trim(), password)
        sessions.saveToken(newToken)
        token.value = newToken
        signedIn.value = true
        try { refreshProfile() } catch (e: PicaException) {
            if (e.kind == PicaException.Kind.AUTH) { logout(); throw e }
        }
    }
    fun logout() { profile.value = null; sessions.clear(); token.value = null; signedIn.value = false; name.value = "PicaPica 读者" }
    fun invalidateSession() = logout()
    suspend fun home(page: Int = 1): ComicResults = api.comics(page)
    suspend fun search(keyword: String, page: Int = 1, sort: SearchSort = SearchSort.NEWEST, categories: Set<String> = emptySet()): ComicResults = api.search(keyword, page, sort, categories)

    suspend fun comic(id: String, offline: Boolean = false): Comic {
        val key = config.comicKey(id)
        if (offline) return dao.comic(key)?.comic() ?: error("本机没有这部作品的资料")
        return try { api.comic(id).also { dao.putComic(ComicRecord.from(config, it)) } }
        catch (e: Exception) {
            if (e is CancellationException || e is PicaException && e.kind == PicaException.Kind.AUTH) throw e
            dao.comic(key)?.comic() ?: throw e
        }
    }
    suspend fun chapters(id: String, offline: Boolean = false): List<Chapter> {
        val key = config.comicKey(id)
        if (offline) return dao.chapters(key).map { it.chapter() }
        return try {
            api.chapters(id).also { list -> dao.putChapters(list.map { ChapterRecord(config.chapterKey(id, it.order), key, it.id, it.title, it.order) }) }
        } catch (e: Exception) {
            if (e is CancellationException || e is PicaException && e.kind == PicaException.Kind.AUTH) throw e
            dao.chapters(key).takeIf { it.isNotEmpty() }?.map { it.chapter() } ?: throw e
        }
    }
    suspend fun cachePages(id: String, order: Int): List<PageRecord> {
        val key = config.chapterKey(id, order)
        val old = dao.pages(key)
        if (old.isNotEmpty()) return old
        val values = api.pages(id, order).map { PageRecord(key, it.index, it.id, it.url) }
        db.withTransaction { dao.putPages(values) }
        return values
    }
    suspend fun readerPages(id: String, order: Int, offline: Boolean): List<PageImage> {
        val values = if (offline) dao.pages(config.chapterKey(id, order)) else cachePages(id, order)
        if (values.isEmpty()) error("尚未下载此章节，请联网后重试")
        val result = values.map { record ->
            val valid = files.isValid(record)
            if (offline && !valid) error("本章离线文件不完整，请重新下载")
            record.image().copy(localPath = record.localPath.takeIf { valid })
        }
        return result
    }
    suspend fun progress(id: String) = dao.progress(config.comicKey(id))
    suspend fun saveProgress(id: String, order: Int, index: Int) =
        dao.putProgress(ReadingProgress(config.comicKey(id), order, index.coerceAtLeast(0)))

    suspend fun cacheDownloadCover(url: String): String? = try {
        files.cacheCover(url); null
    } catch (e: CancellationException) { throw e } catch (_: Exception) { "封面暂未保存，联网后可校验修复" }

    suspend fun prepareDownload(comic: Comic, chapter: Chapter): DownloadRecord {
        check(comic.allowDownload) { "此作品不允许下载" }
        dao.putComic(ComicRecord.from(config, comic))
        dao.putChapters(listOf(ChapterRecord(config.chapterKey(comic.id, chapter.order), config.comicKey(comic.id), chapter.id, chapter.title, chapter.order)))
        val key = config.chapterKey(comic.id, chapter.order)
        val previous = dao.download(key)
        val existingPages = dao.pages(key)
        if (previous != null && previous.state == DownloadRecord.COMPLETE && existingPages.isNotEmpty() && existingPages.size == previous.totalPages && existingPages.all { files.isValid(it) }) {
            val coverError = cacheDownloadCover(comic.coverUrl)
            return previous.copy(error = coverError).also { dao.putDownload(it) }
        }
        val value = DownloadRecord(key, config.comicKey(comic.id), config.key, comic.id, comic.title, chapter.title, chapter.order, comic.coverUrl,
            completedPages = previous?.completedPages ?: 0, totalPages = previous?.totalPages ?: 0)
        dao.putDownload(value)
        return value
    }
}
