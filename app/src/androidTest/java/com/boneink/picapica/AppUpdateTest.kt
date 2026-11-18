package com.boneink.picapica

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.ui.updateInstallerIntent
import com.boneink.picapica.update.AppUpdateState
import com.boneink.picapica.update.AppUpdateViewModel
import com.boneink.picapica.update.GitHubUpdates
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AppUpdateTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun downloadRejectsTamperingAndHandsVerifiedApkToAndroid() {
        val args = InstrumentationRegistry.getArguments()
        val apk = args.getString("updateApk")?.let(::File)
        assumeTrue(apk?.isFile == true)
        checkNotNull(apk)
        val version = args.getString("updateVersion") ?: error("Missing updateVersion")
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val digest = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
        val attempts = AtomicInteger()
        val name = "PicaPica-$version-preview.apk"
        val asset = JSONObject().put("name", name).put("state", "uploaded").put("size", apk.length())
            .put("digest", "sha256:$digest")
            .put("browser_download_url", "https://github.com/BoneInk/PicaPica/releases/download/v$version/$name")
        val json = JSONArray().put(JSONObject().put("tag_name", "v$version").put("prerelease", true).put("assets", JSONArray().put(asset))).toString()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.host == "api.github.com") json.toResponseBody("application/json".toMediaType())
            else if (attempts.getAndIncrement() != 0) apk.source().buffer().asResponseBody(contentLength = apk.length())
            else apk.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }.toResponseBody()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        lateinit var updater: AppUpdateViewModel
        val original = app.container
        val storage = "update-fixture-${System.nanoTime()}"
        val container = AppContainer(app, storageName = storage)
        app.installTestContainer(container)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    updater = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppUpdateViewModel(app, GitHubUpdates(app, client)) as T
                    })[AppUpdateViewModel::class.java]
                }
                compose.onNodeWithTag("nav-profile").performClick()
                compose.onNodeWithTag("open-settings").performClick()
                compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("setting-update"))
                compose.onNodeWithTag("setting-update").performClick()
                waitTag("app-update-download")
                compose.onNodeWithTag("app-update-download").performClick()
                waitTag("app-update-retry")
                compose.onNodeWithText("安装包校验失败，请重新下载。").assertIsDisplayed()
                compose.onNodeWithTag("app-update-retry").performClick()
                waitTag("app-update-install")
                compose.onNodeWithText("$version 已下载并验证，可以安装。").assertIsDisplayed()

                val ready = updater.state.value as AppUpdateState.Ready
                val installer = updateInstallerIntent(app, ready.apk)
                assertEquals("application/vnd.android.package-archive", installer.type)
                assertTrue(app.contentResolver.openFileDescriptor(checkNotNull(installer.data), "r")!!.use { it.statSize == apk.length() })
                if (args.getString("exerciseInstaller") == "true") {
                    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                    assertTrue("Grant install permission on the test emulator before running this check", app.packageManager.canRequestPackageInstalls())
                    compose.onNodeWithTag("app-update-install").performClick()
                    compose.waitUntil(15_000) { automation.rootInActiveWindow?.packageName?.contains("packageinstaller") == true }
                    ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent KEYCODE_BACK")).use {
                        it.readBytes()
                    }
                }
                ready.apk.delete()
            }
        } finally {
            app.installTestContainer(original)
            container.db.close()
            app.deleteDatabase("$storage.db")
        }
    }

    private fun waitTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

}
