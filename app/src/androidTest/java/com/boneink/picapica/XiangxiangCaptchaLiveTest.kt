package com.boneink.picapica

import android.graphics.BitmapFactory
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.ui.PicaViewModel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly enabled, read-only real CAPTCHA check. Never submits real credentials. */
@RunWith(AndroidJUnit4::class)
class XiangxiangCaptchaLiveTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun realLoginPageLoadsAndRefreshesCaptchaAcrossCanonicalRedirect() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("xiangxiangLive") == "1")
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val container = AppContainer(app, storageName = "xx-captcha-live-${System.currentTimeMillis()}")
        container.accounts.select("xiangxiang")
        app.installTestContainer(container)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("empty-action").performClick()
            compose.onNodeWithTag("login-captcha").assertExists()
            lateinit var model: PicaViewModel
            scenario.onActivity { model = ViewModelProvider(it)[PicaViewModel::class.java] }
            compose.waitUntil(45_000) { !model.state.value.captchaLoading && (model.state.value.captchaImage != null || model.state.value.captchaError != null) }
            assertNull(model.state.value.captchaError)
            val first = requireNotNull(model.state.value.captchaImage)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(first, 0, first.size))
            assertTrue(bitmap.width > 10 && bitmap.height > 10); bitmap.recycle()
            compose.onNodeWithContentDescription("刷新验证码").assertIsDisplayed()
            compose.waitForIdle()
            capture(app, "xiangxiang-captcha-canonical")
            compose.onNodeWithTag("refresh-captcha").performClick()
            compose.waitUntil(45_000) { !model.state.value.captchaLoading && (model.state.value.captchaImage !== first || model.state.value.captchaError != null) }
            assertNull(model.state.value.captchaError)
            assertNotNull(model.state.value.captchaImage)
            compose.onNodeWithContentDescription("刷新验证码").assertIsDisplayed()
            assertFalse(container.repo.signedIn.value)
            assertNull(container.repo.sessions.readToken())
        }
        container.db.close()
    }
}
