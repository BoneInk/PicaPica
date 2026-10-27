package com.boneink.picapica.download

import android.graphics.BitmapFactory
import com.boneink.picapica.data.PageRecord
import com.boneink.picapica.network.digest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class LocalFiles(val root: File, private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()) {
    init { check(root.isDirectory || root.mkdirs()) }

    fun resolve(relative: String): File {
        val candidate = File(root, relative).canonicalFile
        require(candidate.path.startsWith(root.canonicalPath + File.separator)) { "离线路径无效" }
        return candidate
    }
    fun isValid(record: PageRecord): Boolean {
        val path = record.localPath ?: return false
        val expected = record.sha256 ?: return false
        return runCatching {
            val file = resolve(path)
            file.isFile && file.length() == record.byteCount && sha256(file) == expected
        }.getOrDefault(false)
    }
    suspend fun fetch(record: PageRecord): PageRecord = withContext(Dispatchers.IO) {
        val relative = "${digest(record.chapterKey.toByteArray()).take(32)}/${record.pageIndex}.page"
        val target = resolve(relative)
        check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true)
        val part = File(target.path + ".part-" + java.util.UUID.randomUUID())
        try {
            http.newCall(Request.Builder().url(record.url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("图片下载失败（HTTP ${response.code}）")
                val body = response.body ?: throw IOException("图片内容为空")
                if (body.contentLength() > MAX_IMAGE_BYTES) throw IOException("图片超出单页大小限制")
                var bytes = 0L
                body.byteStream().use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        if (bytes > MAX_IMAGE_BYTES) throw IOException("图片超出单页大小限制")
                        output.write(buffer, 0, count)
                    }
                } }
                if (bytes == 0L || body.contentLength() >= 0 && bytes != body.contentLength()) throw IOException("图片下载不完整")
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(part.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 200_000_000) {
                throw IOException("下载结果不是有效图片")
            }
            val thumbnail = BitmapFactory.Options().apply {
                inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 128).coerceAtLeast(1)
            }
            BitmapFactory.decodeFile(part.path, thumbnail)?.recycle() ?: throw IOException("图片数据损坏")
            val hash = sha256(part)
            currentCoroutineContext().ensureActive()
            if (!part.renameTo(target)) throw IOException("无法保存离线文件")
            record.copy(localPath = relative, sha256 = hash, byteCount = target.length(), width = bounds.outWidth, height = bounds.outHeight)
        } finally { part.delete() }
    }
    private val coverVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val coverChanges = coverVersion.asStateFlow()
    private val coverLock = kotlinx.coroutines.sync.Mutex()
    private fun coverManifest(url: String) = resolve("covers/${digest(url.toByteArray())}.json")
    private fun coverRecord(url: String): PageRecord? = runCatching {
        val json = org.json.JSONObject(android.util.AtomicFile(coverManifest(url)).openRead().bufferedReader().use { it.readText() })
        PageRecord("cover:$url", -1, "cover", url, json.getString("localPath"), json.getString("sha256"), json.getLong("bytes"))
    }.getOrNull()
    /** Synchronous UI lookup only checks file presence/size; integrity is verified on download/repair. */
    fun coverFile(url: String): File? = coverRecord(url)?.let { row -> runCatching {
        resolve(row.localPath!!).takeIf { it.isFile && it.length() == row.byteCount }
    }.getOrNull() }
    suspend fun cacheCover(url: String) = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext
        coverLock.lock()
        try {
            if (coverRecord(url)?.let(::isValid) == true) return@withContext
            val row = fetch(PageRecord("cover:$url", -1, "cover", url))
            val target = coverManifest(url)
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            val atomic = android.util.AtomicFile(target)
            val output = atomic.startWrite()
            try {
                output.write(org.json.JSONObject().put("localPath", row.localPath).put("sha256", row.sha256)
                    .put("bytes", row.byteCount).toString().toByteArray())
                atomic.finishWrite(output)
            } catch (e: Exception) { atomic.failWrite(output); throw e }
            coverVersion.value++
        } finally { coverLock.unlock() }
    }
    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; md.update(buffer, 0, count) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    companion object { private const val MAX_IMAGE_BYTES = 50L * 1024 * 1024 }
}
