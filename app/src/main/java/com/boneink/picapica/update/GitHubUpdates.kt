package com.boneink.picapica.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.boneink.picapica.BuildConfig
import com.boneink.picapica.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val REPO = "BoneInk/PicaPica"
private const val MAX_APK_BYTES = 200L * 1024 * 1024
private val releaseListUrl = "https://api.github.com/repos/$REPO/releases?per_page=100".toHttpUrl()
private val versionPattern = Regex("""v?(\d+)\.(\d+)\.(\d+)""")
private val digestPattern = Regex("sha256:([a-fA-F0-9]{64})")

internal data class UpdateRelease(val version: String, val url: HttpUrl, val bytes: Long, val sha256: String, val notesUrl: String)

internal class UpdateException(message: String) : Exception(message)

internal class GitHubUpdates(
    context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder().followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
) {
    private val app = context.applicationContext

    suspend fun check(): UpdateRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(releaseListUrl).header("Accept", "application/vnd.github+json").build()
        http.newCall(request).await().use { response ->
            if (response.code == 403 || response.code == 429) throw UpdateException("GitHub 暂时限制了请求，请稍后再试。")
            if (!response.isSuccessful) throw UpdateException("GitHub 暂时无法查询更新（HTTP ${response.code}）。")
            val releases = JSONArray(response.body?.string().orEmpty())
            selectUpdateRelease(releases, BuildConfig.VERSION_NAME, preview = app.packageName.endsWith(".debug"))
        }
    }

    suspend fun download(release: UpdateRelease, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(app.cacheDir, "app-updates").apply { mkdirs() }
        val target = File(directory, "${release.version}-${release.sha256}.apk")
        val part = File.createTempFile("update-part-", ".apk", directory)
        try {
            val request = Request.Builder().url(release.url).build()
            http.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw UpdateException("安装包下载失败（HTTP ${response.code}）。")
                val body = response.body ?: throw UpdateException("安装包下载为空。")
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                body.byteStream().use { input ->
                    part.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            received += count
                            if (received > release.bytes) throw UpdateException("安装包大小与发布记录不符。")
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            onProgress((received * 100 / release.bytes).toInt().coerceAtMost(99))
                        }
                    }
                }
                if (received != release.bytes || digest.digest().joinToString("") { "%02x".format(it) } != release.sha256)
                    throw UpdateException("安装包校验失败，请重新下载。")
            }
            ensureActive()
            verifyArchive(part, release.version)
            if (!part.renameTo(target)) throw UpdateException("无法保存已验证的安装包。")
            target
        } finally {
            part.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(file: File, version: String) {
        val manager = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val apk = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateException("无法读取安装包。")
        val installed = manager.getPackageInfo(app.packageName, flags)
        if (apk.packageName != installed.packageName) throw UpdateException("安装包与当前应用的包名不匹配。")
        if (PackageInfoCompat.getLongVersionCode(apk) <= PackageInfoCompat.getLongVersionCode(installed) ||
            apk.versionName?.substringBefore('-') != version) throw UpdateException("安装包的版本与发布记录不匹配。")
        if ((apk.applicationInfo?.minSdkVersion ?: 1) > Build.VERSION.SDK_INT)
            throw UpdateException("此版本需要更新的 Android 系统。")
        val signers = apk.signers()
        if (signers.isEmpty() || signers != installed.signers()) throw UpdateException("安装包与当前应用的签名不匹配。")
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signers(): Set<Signature> =
        (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures).orEmpty().toSet()
}

internal fun selectUpdateRelease(releases: JSONArray, installedVersion: String, preview: Boolean): UpdateRelease? {
    val installed = Version.parse(installedVersion.substringBefore('-'))
        ?: throw UpdateException("无法识别当前应用版本。")
    val newer = (0 until releases.length()).mapNotNull { index ->
        val release = releases.optJSONObject(index) ?: return@mapNotNull null
        if (release.optBoolean("draft") || (!preview && release.optBoolean("prerelease"))) return@mapNotNull null
        val version = Version.parse(release.optString("tag_name")) ?: return@mapNotNull null
        if (version <= installed) return@mapNotNull null
        version to release
    }.sortedByDescending { it.first }
    if (newer.isEmpty()) return null

    for ((version, release) in newer) {
        val tag = release.getString("tag_name")
        val assets = release.optJSONArray("assets") ?: continue
        val names = if (preview) listOf("PicaPica-$version-preview.apk", "PicaPica-$version-debug.apk")
            else listOf("PicaPica-$version.apk", "PicaPica-$version-release.apk")
        for (name in names) {
            val url = "https://github.com/$REPO/releases/download/$tag/$name".toHttpUrl()
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                if (asset.optString("name") != name || asset.optString("state") != "uploaded" ||
                    asset.optString("browser_download_url") != url.toString()) continue
                val bytes = asset.optLong("size")
                val hash = digestPattern.matchEntire(asset.optString("digest"))?.groupValues?.get(1)?.lowercase()
                if (bytes in 1..MAX_APK_BYTES && hash != null)
                    return UpdateRelease(version.toString(), url, bytes, hash, "https://github.com/$REPO/releases/tag/$tag")
            }
        }
    }
    throw UpdateException("有新版发布，当前渠道暂无可验证的安装包。")
}

private data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version) = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    override fun toString() = "$major.$minor.$patch"

    companion object {
        fun parse(value: String): Version? {
            val parts = versionPattern.matchEntire(value)?.destructured ?: return null
            val (major, minor, patch) = parts
            return Version(major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null, patch.toIntOrNull() ?: return null)
        }
    }
}
