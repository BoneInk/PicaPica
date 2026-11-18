package com.boneink.picapica

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.boneink.picapica.data.LibraryDatabase
import com.boneink.picapica.data.PicaRepository
import com.boneink.picapica.data.SessionStore
import com.boneink.picapica.download.DownloadScheduler
import com.boneink.picapica.download.LocalFiles
import com.boneink.picapica.data.ContentBlocking
import com.boneink.picapica.data.AppSettings
import com.boneink.picapica.data.ReaderSettings
import com.boneink.picapica.network.SourceKind
import com.boneink.picapica.network.SourceConfig
import com.boneink.picapica.network.NetworkStack
import coil.Coil
import coil.ImageLoader
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl

class AppContainer(context: Context, config: SourceConfig = SourceConfig(), private val storageName: String = "picapica", private val sessionName: String = "$storageName.session",
    private val sourceConfigs: Map<SourceKind, SourceConfig> = emptyMap()) {
    private val picaConfig = config
    val config: SourceConfig get() = repo.config
    val appContext: Context = context.applicationContext
    val accounts = com.boneink.picapica.data.AccountStore(context, "$storageName.accounts")
    private val blocks = mutableMapOf<SourceKind, ContentBlocking>()
    val blocking: ContentBlocking get() = synchronized(blocks) {
        val kind = accounts.active.value.source
        blocks.getOrPut(kind) { ContentBlocking(appContext,
            if (kind == SourceKind.PICA) "$storageName.blocking" else "$storageName.blocking.${kind.name}") }
    }
    val settings = AppSettings(context, "$storageName.settings")
    val readerSettings = ReaderSettings(context, "$storageName.reader")
    val network = NetworkStack(context, storageName, imageQuality = { settings.values.value.imageQuality.code })
    val db = Room.databaseBuilder(context, LibraryDatabase::class.java, "$storageName.db").addMigrations(com.boneink.picapica.data.MIGRATION_1_2, com.boneink.picapica.data.MIGRATION_2_3).build()
    private val localFiles = LocalFiles(File(context.filesDir, "$storageName/downloads"), network.imageHttp)
    private val repositories = mutableMapOf<String, PicaRepository>()
    val repo: PicaRepository get() = repository(accounts.active.value.id)
    val downloads: DownloadScheduler get() = DownloadScheduler(appContext, repo)
    @Synchronized fun repository(accountId: String): PicaRepository {
        return repositories.getOrPut(accountId) {
            val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: error("账号不存在")
            val source = sourceConfigs[account.source] ?: if (account.source == SourceKind.PICA) picaConfig
                else SourceConfig(network.xiangxiangSite.value.baseUrl.toHttpUrl(), account.source)
            PicaRepository(source, db, SessionStore(appContext, if (account.id == "pica") sessionName else "$sessionName.${account.id}"), localFiles, network, account.id)
        }
    }
    fun repositoryForDownload(sourceKey: String?, accountId: String?): PicaRepository? =
        if (accountId != null) accounts.accounts.value.firstOrNull { it.id == accountId }?.let { repository(it.id) }?.takeIf { it.config.key == sourceKey }
        else accounts.accounts.value.asSequence().map { repository(it.id) }.firstOrNull { it.config.key == sourceKey }
}

class PicaPicaApp : Application() {
    lateinit var container: AppContainer
        private set
    override fun onCreate() { super.onCreate(); container = AppContainer(this); installImageLoader() }
    private fun installImageLoader() { Coil.setImageLoader(ImageLoader.Builder(this).okHttpClient(container.network.imageHttp).build()) }
    /** Only instrumentation can call this; release builds refuse overrides. */
    fun installTestContainer(value: AppContainer) { check(BuildConfig.DEBUG); container = value; installImageLoader() }
}
