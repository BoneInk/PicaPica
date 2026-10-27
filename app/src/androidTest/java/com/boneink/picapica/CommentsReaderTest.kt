package com.boneink.picapica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boneink.picapica.data.SessionStore
import com.boneink.picapica.data.ReadingMode
import com.boneink.picapica.network.SourceConfig
import com.boneink.picapica.ui.PicaViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommentsReaderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitTag(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitPosition(text: String) { compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("reader-position").assertTextEquals(text) }.isSuccess } }
    @Test fun commentsPaginationReplyLikeAndFailedSendKeepDraft() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val storage = "comments-ui-${System.currentTimeMillis()}"
            SessionStore(app, "$storage.session").saveToken(FixtureServer.TOKEN)
            val container = AppContainer(app, SourceConfig(fixture.server.url("/")), storage)
            app.installTestContainer(container)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitTag("comic-comic1")
                compose.onNodeWithTag("comic-comic1").performClick(); waitTag("open-comments")
                compose.onNodeWithTag("open-comments").performScrollTo().performClick(); waitTag("comment-c1")
                capture(app, "comments-0.3.0")
                compose.onNodeWithTag("comments-more").performScrollTo().performClick(); waitTag("comment-c2")
                compose.onNodeWithTag("comment-like-c1").performScrollTo().performClick()
                compose.waitUntil(10_000) { fixture.commentLiked.get() }
                compose.onNodeWithTag("comment-replies-c1").performScrollTo().performClick(); waitTag("comment-reply1")
                compose.onNodeWithTag("comment-draft").performTextInput("谢谢分享这段感受。")
                compose.onNodeWithTag("comment-send").performClick()
                compose.waitUntil(10_000) { fixture.repliesPosted.size == 1 }
                compose.onNodeWithTag("comments-list").performScrollToNode(hasTestTag("comment-replyPosted0"))
                waitTag("comment-replyPosted0")
                assertEquals(listOf("谢谢分享这段感受。"), fixture.repliesPosted.toList())
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].goBack() }
                compose.onNodeWithTag("comments-list").performScrollToNode(hasTestTag("comment-c1"))
                waitTag("comment-c1")
                fixture.failNextComment.set(true)
                compose.onNodeWithTag("comment-draft").performTextInput("画面很有氛围。")
                compose.onNodeWithTag("comment-send").performClick(); waitTag("error-message")
                compose.onNodeWithTag("comment-draft").assertTextContains("画面很有氛围。")
                compose.onNodeWithTag("comment-send").performClick()
                compose.waitUntil(10_000) { fixture.commentsPosted.size == 1 }
                compose.onNodeWithTag("comments-list").performScrollToNode(hasTestTag("comment-posted0"))
                waitTag("comment-posted0")
                assertEquals(listOf("画面很有氛围。"), fixture.commentsPosted.toList())
                compose.onNodeWithTag("comment-draft").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
                assertTrue(fixture.violations.toString(), fixture.violations.isEmpty())
            }
            container.db.close()
        }
    }
    @Test fun horizontalSwipeModeSwitchAndActivityRecreationRetainPosition() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val storage = "horizontal-ui-${System.currentTimeMillis()}"
            SessionStore(app, "$storage.session").saveToken(FixtureServer.TOKEN)
            val container = AppContainer(app, SourceConfig(fixture.server.url("/")), storage)
            app.installTestContainer(container)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitTag("comic-comic1")
                compose.onNodeWithTag("comic-comic1").performClick(); waitTag("read-continue")
                compose.onNodeWithTag("read-continue").performClick(); waitTag("reader-pages")
                compose.onNodeWithTag("reader-mode-horizontal").performClick(); waitTag("reader-pager")
                compose.onNodeWithTag("reader-pager").performTouchInput { swipeLeft() }; waitPosition("2 / 3")
                compose.waitUntil(15_000) { runBlocking { container.repo.progress("comic1")?.pageIndex == 1 } }
                compose.onNodeWithTag("reader-mode-vertical").performClick(); waitTag("reader-pages"); waitPosition("2 / 3")
                compose.onNodeWithTag("reader-mode-horizontal").performClick(); waitTag("reader-pager"); waitPosition("2 / 3")
                compose.onNodeWithTag("reader-page-1").performTouchInput { doubleClick(center) }
                compose.waitForIdle()
                compose.onNodeWithTag("reader-pager").performTouchInput { swipeLeft() }; waitPosition("2 / 3")
                compose.onNodeWithTag("reader-page-1").performTouchInput { doubleClick(center) }
                compose.waitForIdle()
                compose.onNodeWithTag("reader-pager").performTouchInput { swipeLeft() }; waitPosition("3 / 3")
                compose.onNodeWithTag("reader-pager").performTouchInput { swipeRight() }; waitPosition("2 / 3")
                scenario.recreate(); waitTag("reader-pager"); waitPosition("2 / 3")
                assertEquals(ReadingMode.HORIZONTAL, container.readerSettings.mode.value)
                assertEquals(ReadingMode.HORIZONTAL, com.boneink.picapica.data.ReaderSettings(app, "$storage.reader").mode.value)
                capture(app, "horizontal-reader-0.3.0")
                // Leave the reader while its ViewModel is alive and await the final checkpoint
                // before the fixture database is closed by test teardown.
                val exitAt = System.currentTimeMillis()
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].goBack() }
                waitTag("read-continue")
                compose.waitUntil(10_000) { runBlocking { container.repo.progress("comic1")?.let { it.pageIndex == 1 && it.updatedAt >= exitAt } == true } }
            }
            container.db.close()
        }
    }
}
