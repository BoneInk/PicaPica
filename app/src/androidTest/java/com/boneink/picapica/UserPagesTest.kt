package com.boneink.picapica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boneink.picapica.data.SessionStore
import com.boneink.picapica.network.SourceConfig
import com.boneink.picapica.ui.PicaViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserPagesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitTag(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitText(text: String) { compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun fixtureContainer(app: PicaPicaApp, fixture: FixtureServer): AppContainer {
        val storage="user-pages-${System.nanoTime()}"
        SessionStore(app,"$storage.session").saveToken(FixtureServer.TOKEN)
        return AppContainer(app,SourceConfig(fixture.server.url("/")),storage).also(app::installTestContainer)
    }
    @Test fun profileRecentCommentsPaginateAndOpenSourceWithoutLosingList() {
        val app=ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val container=fixtureContainer(app,fixture)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitTag("nav-profile");compose.onNodeWithTag("nav-profile").performClick()
                waitTag("recent-comment-mine1")
                compose.onNodeWithText("故事收藏家").assertExists()
                capture(app,"profile-recent-comments-0.4.0")
                compose.onNodeWithTag("profile-my-comments").performScrollTo().performClick()
                waitTag("my-comments-list")
                compose.onNodeWithTag("my-comments-list").performScrollToNode(hasTestTag("my-comments-more"))
                compose.onNodeWithTag("my-comments-more").performClick()
                compose.onNodeWithTag("my-comments-list").performScrollToNode(hasTestTag("recent-comment-mine5"))
                waitTag("recent-comment-mine5")
                scenario.onActivity { assertEquals(5,ViewModelProvider(it)[PicaViewModel::class.java].state.value.myComments.size) }
                capture(app,"my-comments-0.4.0")
                compose.onNodeWithTag("recent-source-mine5").performScrollTo().performClick();waitTag("history-comment-anchor")
                waitTag("comment-mine5")
                waitTag("comment-historyReply")
                compose.onNodeWithContentDescription("返回",useUnmergedTree=true).performClick()
                waitTag("my-comments-list")
                compose.onNodeWithTag("my-comments-list").performScrollToNode(hasTestTag("recent-comment-mine5"))
                waitTag("recent-comment-mine5")
                assertTrue(fixture.violations.toString(),fixture.violations.isEmpty())
            }
            container.db.close()
        }
    }
    @Test fun commentAvatarOpensPublicDetailsAndZoomableImageThenReturnsToDraft() {
        val app=ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val container=fixtureContainer(app,fixture)
            ActivityScenario.launch(MainActivity::class.java).use {
                waitTag("comic-comic1");compose.onNodeWithTag("comic-comic1").performClick();waitTag("open-comments")
                compose.onNodeWithTag("open-comments").performScrollTo().performClick();waitTag("comment-c1")
                compose.onNodeWithTag("comment-draft").performTextInput("尚未发送的阅读感受")
                compose.onNodeWithTag("comment-avatar-c1").performScrollTo().performClick();waitTag("user-details")
                compose.onNodeWithText("把每一次阅读，留在记忆里。").assertIsDisplayed()
                capture(app,"comment-user-details-0.4.0")
                compose.onNodeWithTag("user-details-avatar").performClick();waitTag("avatar-preview")
                compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("头像大图已加载").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("头像大图已加载").performTouchInput { doubleClick(center) }
                capture(app,"avatar-large-0.4.0")
                compose.onNodeWithTag("avatar-close").performClick();waitTag("user-details")
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("user-details").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("comment-draft").assertTextContains("尚未发送的阅读感受")
                compose.onNodeWithTag("comment-replies-c1").performScrollTo().performClick();waitTag("comment-reply1")
                compose.onNodeWithTag("comment-avatar-reply1").performScrollTo().performClick();waitTag("user-details")
                assertTrue(fixture.commentsPosted.isEmpty());assertTrue(fixture.repliesPosted.isEmpty())
            }
            container.db.close()
        }
    }
    @Test fun recentCommentErrorsCanRetryEmptyStateAndAvatarFailureCanReload() {
        val app=ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            fixture.failMyCommentsOnce.set(true);fixture.myCommentsEmpty.set(true);fixture.failAvatar.set(true)
            val container=fixtureContainer(app,fixture)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitTag("nav-profile");compose.onNodeWithTag("nav-profile").performClick()
                waitTag("error-message")
                compose.onNodeWithTag("error-message").performScrollTo()
                compose.onNodeWithText("重试").performClick();waitText("还没有评论记录")
                compose.onNodeWithTag("profile-avatar").performScrollTo().performClick();waitTag("user-details")
                compose.onNodeWithTag("user-details-avatar").performClick();waitTag("avatar-preview")
                waitText("头像暂时无法加载")
                fixture.failAvatar.set(false)
                compose.onNodeWithText("重新加载").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("头像大图已加载").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("avatar-close").performClick()
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].closeUserDetails(); ViewModelProvider(it)[PicaViewModel::class.java].openMyComments() }
                waitText("还没有评论记录")
                scenario.onActivity { container.repo.invalidateSession() }
                waitText("登录后查看最近评论")
                scenario.onActivity { assertTrue(ViewModelProvider(it)[PicaViewModel::class.java].state.value.myComments.isEmpty()) }
            }
            container.db.close()
        }
    }
}
