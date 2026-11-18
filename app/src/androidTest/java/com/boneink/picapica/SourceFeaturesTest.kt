package com.boneink.picapica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.network.*
import com.boneink.picapica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceFeaturesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitTag(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun fill(tag: String, text: String) { compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(text) }
    private fun hideKeyboard() { InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); compose.waitForIdle() }
    @Test fun cloudFavouritesAcceptTheActualFalseFlagAndReturnToEmptyAfterRemovingTheLastComic() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>(); val original = app.container
        XiangxiangFixture().use { fixture ->
            val storage = "xx-favourite-flag-${System.nanoTime()}"
            val configs = mapOf(SourceKind.XIANGXIANG to SourceConfig(fixture.server.url("/"), SourceKind.XIANGXIANG))
            val container = AppContainer(app, storageName = storage, sourceConfigs = configs); container.accounts.select("xiangxiang")
            runBlocking { container.repo.api.captcha(); container.repo.login("xx-reader", "xx-password", "1234") }
            app.installTestContainer(container)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var model: PicaViewModel
                    scenario.onActivity { model = ViewModelProvider(it)[PicaViewModel::class.java]; model.openFavourites() }
                    compose.waitUntil(15_000) { !model.state.value.loading && model.state.value.screen == Screen.LIBRARY }
                    assertNull(model.state.value.error); compose.onNodeWithText("还没有云收藏").assertIsDisplayed()
                    scenario.onActivity { model.openComic("101") }
                    compose.waitUntil(15_000) { !model.state.value.loading && model.state.value.comic != null }
                    compose.onNodeWithTag("comic-favourite").performScrollTo().performClick()
                    compose.waitUntil(15_000) { !model.state.value.loading && model.state.value.comic?.isFavourite == true }
                    assertNull(model.state.value.error); scenario.onActivity { model.clearNotice(); model.goBack() }
                    compose.waitUntil(15_000) { !model.state.value.loading && model.state.value.favourites.size == 1 }
                    assertNull(model.state.value.error); compose.onNodeWithTag("comic-101").performClick()
                    compose.waitUntil(15_000) { !model.state.value.loading && model.state.value.comic?.isFavourite == true }
                    compose.onNodeWithTag("comic-favourite").performScrollTo().performClick()
                    compose.waitUntil(15_000) { !model.state.value.loading && model.state.value.comic?.isFavourite == false }
                    scenario.onActivity { model.clearNotice(); model.goBack() }
                    compose.waitUntil(15_000) { !model.state.value.loading && model.state.value.screen == Screen.LIBRARY }
                    assertNull(model.state.value.error); assertTrue(model.state.value.favourites.isEmpty())
                    compose.onNodeWithText("还没有云收藏").assertIsDisplayed(); assertTrue(container.repo.signedIn.value)
                }
            } finally { container.db.close(); app.installTestContainer(original); app.deleteDatabase("$storage.db") }
        }
    }
    @Test fun picaDailyCheckInShowsConfirmedStatusAfterReopeningAndDoesNotRepeat() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>(); val original = app.container
        FixtureServer().use { fixture ->
            val storage = "punch-ui-${System.nanoTime()}"; val container = AppContainer(app, SourceConfig(fixture.server.url("/")), storage)
            runBlocking { container.repo.login(FixtureServer.ACCOUNT, FixtureServer.PASSWORD) }; app.installTestContainer(container)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var model: PicaViewModel
                    scenario.onActivity { model = ViewModelProvider(it)[PicaViewModel::class.java]; model.tab(Screen.PROFILE) }
                    compose.waitUntil(15_000) { !model.state.value.checkInLoading && model.state.value.checkIn != null }
                    compose.onNodeWithTag("profile-check-in").performScrollTo().assertIsEnabled().performClick()
                    compose.waitUntil(15_000) { !model.state.value.checkInBusy && model.state.value.checkIn?.signed == true }
                    compose.onNodeWithTag("profile-check-in").assertTextContains("今日已签到", substring = true).assertIsNotEnabled()
                    scenario.onActivity { model.tab(Screen.HOME); model.tab(Screen.PROFILE) }
                    compose.waitUntil(15_000) { !model.state.value.checkInLoading }
                    compose.onNodeWithTag("profile-check-in").performScrollTo().assertIsNotEnabled()
                    assertEquals(1, fixture.punchCalls.get())
                }
            } finally { container.db.close(); app.installTestContainer(original); app.deleteDatabase("$storage.db") }
        }
    }
    @Test fun xiangxiangRegistrationRetainsFieldsOnFailureThenHomepageSectionsAndCheckInWork() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>(); val original = app.container
        XiangxiangFixture().use { fixture ->
            fixture.failRegistrationOnce = true
            val storage = "xx-features-${System.nanoTime()}"
            val configs = mapOf(SourceKind.XIANGXIANG to SourceConfig(fixture.server.url("/"), SourceKind.XIANGXIANG))
            val container = AppContainer(app, storageName = storage, sourceConfigs = configs); container.accounts.select("xiangxiang"); app.installTestContainer(container)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var model: PicaViewModel
                    scenario.onActivity { model = ViewModelProvider(it)[PicaViewModel::class.java]; model.navigate(Screen.LOGIN) }
                    waitTag("open-register"); compose.onNodeWithTag("open-register").performScrollTo().performClick()
                    waitTag("register-account"); compose.waitUntil(15_000) { !model.state.value.captchaLoading && model.state.value.captchaImage != null }
                    fill("register-account", "newreader"); fill("register-password", "fixture-password"); fill("register-confirm", "fixture-password")
                    fill("register-email", "reader@example.invalid"); hideKeyboard()
                    compose.onNodeWithTag("register-send-code").performScrollTo().performClick()
                    compose.waitUntil(10_000) { fixture.mailRequests.size == 1 && !model.state.value.registrationCodeSending }
                    compose.onNodeWithTag("register-send-code").assertIsNotEnabled()
                    fill("register-email-code", "123456"); fill("register-captcha", "1234"); hideKeyboard()
                    compose.onNodeWithTag("register-submit").performScrollTo().performClick()
                    waitTag("register-error"); compose.waitUntil(10_000) { !model.state.value.loading && !model.state.value.captchaLoading }
                    compose.onNodeWithTag("register-account").assertTextContains("newreader")
                    compose.onNodeWithTag("register-email-code").assertTextContains("123456")
                    fill("register-captcha", "1234"); hideKeyboard(); compose.onNodeWithTag("register-submit").performScrollTo().performClick()
                    waitTag("login-account"); compose.onNodeWithTag("login-account").assertTextContains("newreader")
                    assertEquals(2, fixture.registrations.size); assertNull(container.repo.sessions.readToken())
                    fill("login-password", "fixture-password")
                    compose.waitUntil(10_000) { !model.state.value.captchaLoading && model.state.value.captchaImage != null }
                    fill("login-captcha", "1234"); hideKeyboard(); compose.onNodeWithTag("login-submit").performScrollTo().performClick()
                    waitTag("home-tab-newest"); compose.onNodeWithTag("home-tab-siangsiang").performClick(); waitTag("home-section-siangsiang")
                    capture(app, "xiangxiang-home-sections")
                    compose.onNode(hasText("更多") and hasAnyAncestor(hasTestTag("home-section-siangsiang"))).performClick()
                    compose.waitUntil(15_000) { !model.state.value.loading && model.state.value.categoryComics.isNotEmpty() }
                    assertTrue(fixture.requests.any { it.startsWith("/home/api/getpage/tp/1-siangsiang-0") })
                    scenario.onActivity { model.tab(Screen.PROFILE) }
                    compose.waitUntil(15_000) { !model.state.value.checkInLoading && model.state.value.checkIn != null }
                    compose.onNodeWithTag("profile-check-in").performScrollTo().performClick()
                    compose.waitUntil(15_000) { !model.state.value.checkInBusy && model.state.value.checkIn?.signed == true }
                    compose.onNodeWithTag("profile-check-in").assertIsNotEnabled(); assertTrue(fixture.signedToday)
                    scenario.onActivity { model.clearNotice(); model.navigate(Screen.SETTINGS) }
                    compose.onNodeWithTag("setting-xiangxiang-site").performScrollTo().performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithText("香香腐宅访问站点").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("备用站  fuzhaiboylove7.work").performScrollTo().assertIsDisplayed()
                    capture(app, "xiangxiang-site-selector")
                    compose.onNodeWithText("取消").performClick()
                    container.network.selectXiangxiang(XiangxiangSite.MIRROR_7)
                    val restored = com.boneink.picapica.network.NetworkStack(app, storage)
                    assertEquals(XiangxiangSite.MIRROR_7, restored.xiangxiangSite.value)
                    assertTrue(fixture.violations.toString(), fixture.violations.isEmpty())
                }
            } finally { container.db.close(); app.installTestContainer(original); app.deleteDatabase("$storage.db") }
        }
    }
}
