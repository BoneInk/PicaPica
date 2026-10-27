package com.boneink.picapica.download

import com.boneink.picapica.data.DownloadRecord
import com.boneink.picapica.data.PicaRepository
import com.boneink.picapica.network.PicaException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Each completed page is checkpointed. An interrupted/retried job reuses verified files. */
class ChapterDownloader(private val repo: PicaRepository) {
    suspend fun download(key: String, timeBudgetMillis: Long = 8 * 60 * 1000): Boolean {
        val dao = repo.dao
        val start = System.currentTimeMillis()
        var record = dao.download(key) ?: error("下载任务不存在")
        check(record.source == repo.config.key) { "下载源不匹配" }
        suspend fun update(state: String, done: Int = record.completedPages, total: Int = record.totalPages, error: String? = null) {
            record = record.copy(state = state, completedPages = done, totalPages = total, error = error, updatedAt = System.currentTimeMillis())
            dao.putDownload(record)
        }
        try {
            update(DownloadRecord.RUNNING)
            val pages = repo.cachePages(record.remoteComicId, record.chapterOrder)
            var done = pages.count { repo.files.isValid(it) }
            update(DownloadRecord.RUNNING, done, pages.size)
            for (page in pages) {
                currentCoroutineContext().ensureActive()
                if (repo.files.isValid(page)) continue
                if (System.currentTimeMillis() - start >= timeBudgetMillis) {
                    update(DownloadRecord.QUEUED, error = "已保存进度，等待继续下载")
                    return false
                }
                dao.putPage(repo.files.fetch(page))
                done++
                update(DownloadRecord.RUNNING, done, pages.size)
            }
            // Do not advertise offline readiness until every file is verified.
            check(dao.pages(key).all { repo.files.isValid(it) }) { "离线文件校验失败" }
            val coverError = repo.cacheDownloadCover(record.coverUrl)
            update(DownloadRecord.COMPLETE, pages.size, pages.size, error = coverError)
            return true
        } catch (e: CancellationException) {
            throw e // Cancellation never becomes a successful download.
        } catch (e: Exception) {
            update(DownloadRecord.FAILED, error = when (e) {
                is PicaException -> e.message
                else -> e.message?.take(160) ?: "下载中断，请重试"
            })
            throw e
        }
    }
}
