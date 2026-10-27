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
class BoardNavigationTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun waitTag(tag:String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun setup(app:PicaPicaApp,fixture:FixtureServer):AppContainer {
        val storage="board-nav-${System.nanoTime()}"
        SessionStore(app,"$storage.session").saveToken(FixtureServer.TOKEN)
        return AppContainer(app,SourceConfig(fixture.server.url("/")),storage).also(app::installTestContainer)
    }
    @Test fun historyAndNestedCommentsCanOpenWorkAndReturnToTheirDrafts() {
        val app=ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val container=setup(app,fixture)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitTag("nav-profile");compose.onNodeWithTag("nav-profile").performClick();waitTag("recent-comment-mine1")
                compose.onNodeWithTag("recent-comment-mine1").performScrollTo().performClick();waitTag("comment-historyReply")
                compose.onNodeWithTag("comment-draft").performTextInput("从历史进入的草稿")
                compose.onNodeWithTag("comment-open-work").performClick();waitTag("chapter-1")
                capture(app,"comment-work-details-0.6.0")
                compose.onNodeWithContentDescription("返回",useUnmergedTree=true).performClick();waitTag("history-comment-anchor")
                compose.onNodeWithTag("comment-draft").assertTextContains("从历史进入的草稿")
                compose.onNodeWithTag("all-comic-comments").performClick();waitTag("comment-c1")
                compose.onNodeWithTag("comment-replies-c1").performScrollTo().performClick();waitTag("inline-reply-reply1")
                compose.onNodeWithTag("inline-reply-action-reply1").performScrollTo().performClick();waitTag("inline-reply-composer")
                compose.onNodeWithTag("inline-reply-draft").performTextInput("楼中楼草稿")
                compose.onNodeWithTag("inline-reply-cancel").performScrollTo().performClick()
                compose.onNodeWithTag("comment-open-work").performClick();waitTag("chapter-1")
                compose.onNodeWithContentDescription("返回",useUnmergedTree=true).performClick();waitTag("inline-reply-reply1")
                compose.onNodeWithTag("inline-reply-action-reply1").performScrollTo().performClick();waitTag("inline-reply-composer")
                compose.onNodeWithTag("inline-reply-draft").assertTextContains("楼中楼草稿")
                compose.onNodeWithTag("inline-reply-cancel").performScrollTo().performClick()
                scenario.onActivity { val vm=ViewModelProvider(it)[PicaViewModel::class.java];vm.openCommentWork();vm.goBack()
                    assertEquals(Screen.COMMENTS,vm.state.value.screen);assertEquals("comic1",vm.state.value.comic?.id) }
                capture(app,"comment-work-entry-0.6.0")
            }
            container.db.close()
        }
    }
    @Test fun officialDiscussionCategoryUsesBoardCommentsAndCanReplyAndReturn() {
        val app=ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val container=setup(app,fixture)
            ActivityScenario.launch(MainActivity::class.java).use {
                waitTag("nav-categories");compose.onNodeWithTag("nav-categories").performClick();waitTag("category-official-discussion")
                capture(app,"categories-0.6.0")
                compose.onNodeWithTag("category-official-discussion").performClick();waitTag("comment-boardRoot")
                compose.onNodeWithText("官方评论区").assertExists()
                compose.onNodeWithTag("comment-open-work").assertDoesNotExist()
                compose.onNodeWithTag("comment-replies-boardRoot").performScrollTo().performClick();waitTag("inline-reply-boardReply")
                compose.onNodeWithTag("comment-reply-boardRoot").performScrollTo().performClick();waitTag("inline-reply-composer")
                compose.onNodeWithTag("inline-reply-draft").performTextInput("仅本地讨论板回复测试")
                compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("inline-reply-send").assertIsEnabled() }.isSuccess }
                compose.onNodeWithTag("inline-reply-send").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("inline-reply-composer").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("comments-list").performScrollToNode(hasTestTag("inline-reply-boardPosted0"));waitTag("inline-reply-boardPosted0")
                assertEquals(listOf("仅本地讨论板回复测试"),fixture.boardRepliesPosted.toList())
                compose.onNodeWithContentDescription("返回",useUnmergedTree=true).performClick();waitTag("category-official-discussion")
                assertTrue(fixture.paths.contains("/comics/5822a6e3ad7ede654696e482/comments"))
                assertFalse(fixture.paths.contains("/comics/5822a6e3ad7ede654696e482/eps"))
                assertTrue(fixture.violations.toString(),fixture.violations.isEmpty())
            }
            container.db.close()
        }
    }
}
