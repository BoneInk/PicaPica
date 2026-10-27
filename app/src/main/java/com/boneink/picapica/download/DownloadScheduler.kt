package com.boneink.picapica.download

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.*
import com.boneink.picapica.PicaPicaApp
import com.boneink.picapica.data.DownloadRecord
import com.boneink.picapica.data.PicaRepository
import com.boneink.picapica.network.Chapter
import com.boneink.picapica.network.Comic
import com.boneink.picapica.network.PicaException
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class DownloadScheduler(context: Context, private val repo: PicaRepository) {
    private val work = WorkManager.getInstance(context)
    suspend fun enqueue(comic: Comic, chapter: Chapter) {
        val row = repo.prepareDownload(comic, chapter)
        if (row.state != DownloadRecord.COMPLETE) schedule(row.chapterKey, ExistingWorkPolicy.KEEP)
    }
    suspend fun resume(key: String) {
        val old = repo.dao.download(key) ?: return
        repo.dao.putDownload(old.copy(state = DownloadRecord.QUEUED, error = null))
        schedule(key, ExistingWorkPolicy.REPLACE)
    }
    suspend fun pause(key: String) {
        work.cancelUniqueWork(workName(key)).result.get()
        repo.dao.download(key)?.let { repo.dao.putDownload(it.copy(state = DownloadRecord.PAUSED, error = null)) }
    }
    private fun schedule(key: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
            .setInputData(workDataOf("chapterKey" to key, "sourceKey" to repo.config.key))
            // A usable Internet network need not pass Android's external validation probes.
            // Let the OS track this request; do not poll or depend on Google's reachability check.
            .setConstraints(Constraints.Builder().setRequiredNetworkRequest(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                NetworkType.CONNECTED,
            ).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("picapica.download").build()
        work.enqueueUniqueWork(workName(key), policy, request)
    }
    private fun workName(key: String) = "picapica.download:$key"
}

class ChapterDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as PicaPicaApp).container.repo
        if (inputData.getString("sourceKey") != repo.config.key) return Result.failure()
        val key = inputData.getString("chapterKey") ?: return Result.failure()
        return try {
            if (ChapterDownloader(repo).download(key)) Result.success() else Result.retry()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val auth = e is PicaException && e.kind == PicaException.Kind.AUTH
            if (auth || runAttemptCount >= 4) Result.failure() else {
                repo.dao.download(key)?.let { repo.dao.putDownload(it.copy(state = DownloadRecord.QUEUED, error = "连接中断，将自动重试")) }
                Result.retry()
            }
        }
    }
}
