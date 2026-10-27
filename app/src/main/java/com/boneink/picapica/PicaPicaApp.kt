package com.boneink.picapica

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.boneink.picapica.data.LibraryDatabase
import com.boneink.picapica.data.PicaRepository
import com.boneink.picapica.data.SessionStore
import com.boneink.picapica.download.DownloadScheduler
import com.boneink.picapica.download.LocalFiles
import com.boneink.picapica.network.SourceConfig
import com.boneink.picapica.network.NetworkStack
import coil.Coil
import coil.ImageLoader
import java.io.File

class AppContainer(context: Context, val config: SourceConfig = SourceConfig(), storageName: String = "picapica", sessionName: String = "$storageName.session") {
    val appContext: Context = context.applicationContext
    val blocking = com.boneink.picapica.data.ContentBlocking(context, "$storageName.blocking")
    val settings = com.boneink.picapica.data.AppSettings(context, "$storageName.settings")
    val readerSettings = com.boneink.picapica.data.ReaderSettings(context, "$storageName.reader")
    val network = NetworkStack(context, storageName, imageQuality = { settings.values.value.imageQuality.code })
    val db = Room.databaseBuilder(context, LibraryDatabase::class.java, "$storageName.db").addMigrations(com.boneink.picapica.data.MIGRATION_1_2).build()
    val repo = PicaRepository(config, db, SessionStore(context, sessionName), LocalFiles(File(context.filesDir, "$storageName/downloads"), network.imageHttp), network)
    val downloads = DownloadScheduler(context, repo)
}

class PicaPicaApp : Application() {
    lateinit var container: AppContainer
        private set
    override fun onCreate() { super.onCreate(); container = AppContainer(this); installImageLoader() }
    private fun installImageLoader() { Coil.setImageLoader(ImageLoader.Builder(this).okHttpClient(container.network.imageHttp).build()) }
    /** Only instrumentation can call this; release builds refuse overrides. */
    fun installTestContainer(value: AppContainer) { check(BuildConfig.DEBUG); container = value; installImageLoader() }
}
