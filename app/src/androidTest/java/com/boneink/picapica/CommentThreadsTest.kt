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
                waitTag("comic-comic1");compose.onNodeWithTag("comic-comic1").performClick();waitTag("open-comments")
                compose.onNodeWithTag("open-comments").performScrollTo().performClick();waitTag("comment-c1")
                compose.onNodeWithTag("comment-draft").performTextInput("作品评论草稿")
                compose.onNodeWithTag("comment-replies-c1").performScrollTo().performClick();waitTag("inline-reply-reply1")
                compose.onNodeWithTag("inline-reply-action-reply1").performScrollTo().performClick();waitTag("inline-reply-composer")
                compose.onNodeWithTag("inline-reply-draft").performTextInput("回复这条楼中楼")
                compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("inline-reply-send").assertIsEnabled() }.isSuccess }
                fixture.failNestedReplyOnce.set(true)
                compose.onNodeWithTag("inline-reply-send").performClick();waitTag("inline-send-error")
                compose.onNodeWithTag("inline-reply-draft").assertTextContains("回复这条楼中楼")
                compose.onNodeWithTag("inline-reply-cancel").performScrollTo().performClick()
                compose.onNodeWithTag("inline-reply-action-reply1").performScrollTo().performClick();waitTag("inline-reply-composer")
                compose.onNodeWithTag("inline-reply-draft").assertTextContains("回复这条楼中楼")
                compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("inline-reply-send").assertIsEnabled() }.isSuccess }
                compose.onNodeWithTag("inline-reply-send").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { action -> action(); action() }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("inline-reply-composer").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("comments-list").performScrollToNode(hasTestTag("inline-reply-nested0"));waitTag("inline-reply-nested0")
                assertEquals(listOf("回复这条楼中楼"), fixture.nestedRepliesPosted.toList()); assertTrue(fixture.repliesPosted.isEmpty())
                compose.onNodeWithTag("comment-draft").assertTextContains("作品评论草稿")
                compose.onNodeWithTag("comment-replies-c1").performScrollTo().performClick();compose.onNodeWithTag("inline-reply-reply1").assertDoesNotExist()
                compose.onNodeWithTag("comment-replies-c1").performScrollTo().performClick();waitTag("inline-reply-nested0")
                scenario.onActivity { assertEquals(Screen.COMMENTS, ViewModelProvider(it)[PicaViewModel::class.java].state.value.screen) }
                capture(app,"nested-inline-reply-0.11.0")
                assertTrue(fixture.violations.isEmpty())
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
