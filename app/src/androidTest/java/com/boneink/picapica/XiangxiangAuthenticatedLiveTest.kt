package com.boneink.picapica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.ui.PicaViewModel
import com.boneink.picapica.ui.Screen
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/** Opt-in login/profile check. Credentials arrive through a temporary local bridge, never test files. */
@RunWith(AndroidJUnit4::class)
class XiangxiangAuthenticatedLiveTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun bridge(base: String, path: String, bytes: ByteArray? = null): JSONObject {
        val connection = URL(base + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 120_000
        try {
            if (bytes != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "image/png")
                connection.outputStream.use { it.write(bytes) }
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    @Test fun realLoginProfileAndEncryptedSessionSurviveReopeningStorage() {
        val endpoint = InstrumentationRegistry.getArguments().getString("credentialBridge")
        assumeTrue(endpoint != null)
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val original = app.container
        val storage = "xx-auth-live-${System.currentTimeMillis()}"
        var container: AppContainer? = null
        try {
            val credentials = bridge(endpoint!!, "credentials")
            container = AppContainer(app, storageName = storage)
            container.accounts.select("xiangxiang")
            app.installTestContainer(container)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithTag("empty-action").performClick()
                lateinit var model: PicaViewModel
                scenario.onActivity { model = ViewModelProvider(it)[PicaViewModel::class.java] }
                compose.waitUntil(45_000) { !model.state.value.captchaLoading && (model.state.value.captchaImage != null || model.state.value.captchaError != null) }
                assertNull(model.state.value.captchaError)
                bridge(endpoint, "captcha", requireNotNull(model.state.value.captchaImage))
                val answer = bridge(endpoint, "answer").getString("code")
                scenario.onActivity { model.login(credentials.getString("account"), credentials.getString("password"), answer) }
                compose.waitUntil(45_000) { !model.state.value.loading && (model.state.value.screen == Screen.HOME || model.state.value.error != null) }
                assertNull(model.state.value.error)
                assertTrue(container!!.repo.signedIn.value)
                assertNotNull(model.state.value.selfProfile)
                assertFalse(model.state.value.selfProfile!!.id.isBlank())
                assertNotNull(container!!.repo.sessions.readToken())
                val encrypted = File(app.applicationInfo.dataDir, "shared_prefs/$storage.session.xiangxiang.xml").readText()
                assertFalse("Password must not be stored", encrypted.contains(credentials.getString("password")))
            }
            container.db.close()
            container = AppContainer(app, storageName = storage)
            assertEquals("xiangxiang", container.accounts.active.value.id)
            assertTrue(container.repo.signedIn.value)
            val profile = runBlocking { container!!.repo.refreshProfile() }
            assertFalse(profile.id.isBlank())
            bridge(endpoint!!, "verified", JSONObject().put("login", true).put("profile", true).put("encryptedSessionRestart", true).toString().toByteArray())
        } finally {
            container?.repo?.logout()
            container?.db?.close()
            app.installTestContainer(original)
            app.deleteDatabase("$storage.db")
            listOf("accounts", "session", "session.xiangxiang", "settings", "network", "blocking", "blocking.XIANGXIANG", "reader").forEach {
                app.deleteSharedPreferences("$storage.$it")
            }
            File(app.filesDir, "$storage/downloads").deleteRecursively()
        }
    }
}
