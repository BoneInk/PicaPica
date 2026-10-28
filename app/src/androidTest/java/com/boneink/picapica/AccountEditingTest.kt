package com.boneink.picapica

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.data.SessionStore
import com.boneink.picapica.network.SourceConfig
import com.boneink.picapica.ui.PicaViewModel
import com.boneink.picapica.ui.prepareAvatar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AccountEditingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitTag(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (!tag.startsWith("nav-") && tag != "profile-discard") node.performScrollTo()
        node.performClick()
    }
    private fun fill(tag: String, value: String) { compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value) }
    private fun hideKeyboard() { InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); compose.waitForIdle() }

    @Test fun registrationValidatesRetainsDataOnFailureThenReturnsToPrefilledLogin() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            fixture.failRegisterOnce.set(true)
            val container = AppContainer(app, SourceConfig(fixture.server.url("/")), "registration-${System.nanoTime()}").also(app::installTestContainer)
            ActivityScenario.launch(MainActivity::class.java).use {
                waitTag("empty-action"); click("empty-action"); waitTag("login-form")
                capture(app, "account-login-light")
                click("open-register"); waitTag("register-account")
                fill("register-account", "new-reader"); fill("register-name", "新读者")
                fill("register-password", "new-reader-password"); fill("register-confirm", "different-password")
                fill("register-birthday", "2000-01-02"); hideKeyboard()
                compose.onNodeWithContentDescription("选择生日").performClick(); waitTag("birthday-confirm")
                compose.onNodeWithTag("birthday-confirm").performClick()
                compose.onNodeWithTag("register-birthday").assertTextContains("2000-01-02")
                click("register-gender-bot")
                click("register-submit"); waitTag("register-validation")
                assertTrue(fixture.registrations.isEmpty())
                fill("register-confirm", "new-reader-password"); hideKeyboard(); click("register-submit")
                waitTag("register-question-1")
                capture(app, "account-register-questions-light")
                repeat(3) { index ->
                    fill("register-question-${index + 1}", "第${index + 1}个安全问题")
                    fill("register-answer-${index + 1}", "答案${index + 1}")
                }
                hideKeyboard(); click("register-submit"); waitTag("register-error")
                compose.onNodeWithTag("register-answer-3").assertTextContains("答案3")
                capture(app, "account-register-retry")
                click("register-submit"); waitTag("login-account")
                assertEquals(1, fixture.registrations.size)
                compose.onNodeWithTag("login-account").assertTextContains("new-reader")
                assertEquals("", compose.onNodeWithTag("login-password").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
                assertNull(container.repo.sessions.readToken())
                fill("login-password", "new-reader-password"); hideKeyboard(); click("login-submit")
                waitTag("comic-comic1"); assertTrue(container.repo.signedIn.value)
                assertTrue(fixture.violations.toString(), fixture.violations.isEmpty())
            }
            container.db.close()
        }
    }

    @Test fun editProfileRetriesPersistsAndHonorsDiscardFromToolbar() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val storage = "edit-profile-${System.nanoTime()}"
            SessionStore(app, "$storage.session").saveToken(FixtureServer.TOKEN)
            val container = AppContainer(app, SourceConfig(fixture.server.url("/")), storage).also(app::installTestContainer)
            ActivityScenario.launch(MainActivity::class.java).use {
                waitTag("nav-profile"); click("nav-profile"); waitTag("profile-edit")
                compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("profile-edit") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                click("profile-edit"); waitTag("profile-slogan")
                capture(app, "account-profile-editor-light")
                fill("profile-slogan", "尚未保存"); hideKeyboard()
                compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
                waitTag("profile-discard"); click("profile-discard"); waitTag("profile-edit")
                assertFalse(fixture.profileSlogan.get().contains("尚未保存"))
                click("profile-edit"); waitTag("profile-slogan")
                fill("profile-slogan", "每一页都有新的风景"); hideKeyboard()
                fixture.failProfileSaveOnce.set(true)
                click("profile-save"); waitTag("profile-save-error")
                compose.onNodeWithTag("profile-slogan").assertTextContains("每一页都有新的风景")
                click("profile-save"); waitTag("profile-edit")
                compose.onNodeWithText("每一页都有新的风景").assertIsDisplayed()
                click("nav-home"); click("nav-profile"); waitTag("profile-edit")
                compose.onNodeWithText("每一页都有新的风景").assertIsDisplayed()
                assertEquals("每一页都有新的风景", runBlocking { container.repo.api.profile() }.slogan)
                capture(app, "account-profile-saved-light")
                click("profile-edit"); waitTag("profile-slogan")
                fill("profile-slogan", "需要重新登录"); hideKeyboard()
                fixture.expireProfileSaveOnce.set(true)
                click("profile-save"); waitTag("login-form"); waitTag("login-error")
                compose.onNodeWithTag("login-error").assertTextContains("登录已失效", substring = true)
                assertNull(container.repo.sessions.readToken())
                assertTrue(fixture.violations.toString(), fixture.violations.isEmpty())
            }
            container.db.close()
        }
    }

    @Test fun avatarIsSquareJpegAndSavedThroughTheProfileFlow() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val storage = "avatar-edit-${System.nanoTime()}"
            SessionStore(app, "$storage.session").saveToken(FixtureServer.TOKEN)
            val container = AppContainer(app, SourceConfig(fixture.server.url("/")), storage).also(app::installTestContainer)
            val file = File(app.cacheDir, "synthetic-avatar.png").apply { writeBytes(fixture.image) }
            val jpeg = prepareAvatar(app, Uri.fromFile(file))
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            assertEquals(200, bitmap.width); assertEquals(200, bitmap.height); bitmap.recycle()
            assertTrue(jpeg.size < 500_000)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitTag("nav-profile"); click("nav-profile"); waitTag("profile-edit")
                compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("profile-edit") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                click("profile-edit"); waitTag("profile-slogan")
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].saveProfile("头像也更新了", jpeg) }
                waitTag("profile-edit")
                assertEquals(1, fixture.avatarUploads.size)
                assertTrue(fixture.avatarUploads.single().startsWith("data:image/jpeg;base64,"))
                assertTrue(container.repo.selfProfile.value!!.avatarUrl.endsWith("avatar-updated.png"))
                assertEquals("头像也更新了", container.repo.selfProfile.value!!.slogan)
            }
            file.delete(); container.db.close()
        }
    }
}
