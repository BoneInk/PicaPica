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
import com.boneink.picapica.ui.Screen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommentThreadsTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun waitTag(tag:String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun phase(value:String) { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply { putString("phase",value) }) }
    private fun setup(app:PicaPicaApp, fixture:FixtureServer):AppContainer {
        val storage="comment-threads-${System.nanoTime()}"
        SessionStore(app,"$storage.session").saveToken(FixtureServer.TOKEN)
        return AppContainer(app,SourceConfig(fixture.server.url("/")),storage).also(app::installTestContainer)
    }
    @Test fun nestedReplyUsesSelectedChildAndBackRestoresEachDraft() {
        val app=ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val container=setup(app,fixture)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                phase("wait_home");waitTag("comic-comic1");compose.onNodeWithTag("comic-comic1").performClick();waitTag("open-comments")
                compose.onNodeWithTag("open-comments").performScrollTo().performClick();waitTag("comment-c1")
                phase("main_comment_loaded");compose.onNodeWithTag("comment-draft").performTextInput("作品评论草稿")
                compose.onNodeWithTag("comment-replies-c1").performScrollTo().performClick();waitTag("comment-reply1")
                phase("first_reply_level");compose.onNodeWithTag("comment-draft").performTextInput("上一层回复草稿")
                compose.onNodeWithTag("comment-replies-reply1").performScrollTo().performClick()
                compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("comment-send").assertIsNotEnabled() }.isSuccess }
                phase("nested_reply_level");fixture.failNestedReplyOnce.set(true)
                compose.onNodeWithTag("comment-draft").performTextInput("回复这条楼中楼")
                compose.onNodeWithTag("comment-send").performClick();waitTag("error-message")
                compose.onNodeWithTag("comment-draft").assertTextContains("回复这条楼中楼")
                compose.onNodeWithTag("comment-send").performClick();waitTag("comment-nested0")
                assertEquals(listOf("回复这条楼中楼"),fixture.nestedRepliesPosted.toList())
                assertTrue(fixture.repliesPosted.isEmpty())
                assertTrue(fixture.paths.contains("/comments/reply1"))
                phase("nested_post_passed");capture(app,"nested-reply-0.5.0")
                compose.onNodeWithTag("comment-replies-nested0").performScrollTo().performClick()
                scenario.onActivity { assertEquals("nested0",ViewModelProvider(it)[PicaViewModel::class.java].state.value.replyParent?.id) }
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].goBack() };waitTag("comment-nested0")
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].goBack() };waitTag("comment-reply1")
                compose.onNodeWithTag("comment-draft").assertTextContains("上一层回复草稿")
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].goBack() };waitTag("comment-c1")
                compose.onNodeWithTag("comment-draft").assertTextContains("作品评论草稿")
                assertTrue(fixture.violations.toString(),fixture.violations.isEmpty())
            }
            container.db.close()
        }
    }
    @Test fun historyOpensExactCommentThreadAndAllCommentsWithoutDetailHop() {
        val app=ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val container=setup(app,fixture)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitTag("nav-profile");compose.onNodeWithTag("nav-profile").performClick();waitTag("recent-comment-mine1")
                compose.onNodeWithTag("recent-comment-mine1").performScrollTo().performClick();waitTag("history-comment-anchor")
                waitTag("comment-historyReply")
                scenario.onActivity { assertEquals("mine1",ViewModelProvider(it)[PicaViewModel::class.java].state.value.replyParent?.id) }
                assertFalse(fixture.paths.contains("/comics/comic1/eps"))
                capture(app,"history-comment-thread-0.5.0")
                compose.onNodeWithTag("all-comic-comments").performClick();waitTag("comment-c1")
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].goBack() }
                compose.onNodeWithTag("profile-my-comments").performScrollTo().performClick();waitTag("my-comments-list")
                compose.onNodeWithTag("my-comments-list").performScrollToNode(hasTestTag("recent-comment-mine2"))
                compose.onNodeWithTag("recent-comment-mine2").performClick();waitTag("history-comment-anchor");waitTag("comment-historyReply")
                scenario.onActivity { val state=ViewModelProvider(it)[PicaViewModel::class.java].state.value
                    assertEquals("mine2",state.replyParent?.id);assertEquals("c1",state.replyParent?.parentId) }
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].goBack() };waitTag("my-comments-list")
                assertTrue(fixture.violations.toString(),fixture.violations.isEmpty())
            }
            container.db.close()
        }
    }
    @Test fun unavailableHistoryHasReadableFallbackAndCanOpenAllComments() {
        val app=ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            fixture.unavailableHistory.set(true)
            val container=setup(app,fixture)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitTag("nav-profile");compose.onNodeWithTag("nav-profile").performClick();waitTag("recent-comment-mine1")
                compose.onNodeWithTag("recent-comment-mine1").performScrollTo().performClick();waitTag("error-message")
                compose.onNodeWithText("这条评论暂时无法回复").assertIsDisplayed()
                compose.onNodeWithTag("all-comic-comments").performClick();waitTag("comment-c1")
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].goBack() }
                scenario.onActivity { assertEquals(Screen.PROFILE,ViewModelProvider(it)[PicaViewModel::class.java].state.value.screen) }
                assertTrue(fixture.commentsPosted.isEmpty());assertTrue(fixture.repliesPosted.isEmpty());assertTrue(fixture.nestedRepliesPosted.isEmpty())
            }
            container.db.close()
        }
    }
}
