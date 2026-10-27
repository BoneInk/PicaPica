package com.boneink.picapica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boneink.picapica.data.*
import com.boneink.picapica.network.*
import com.boneink.picapica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShortcutsAndDeepRepliesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitTag(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun readySend() { compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("inline-reply-send").assertIsEnabled() }.isSuccess } }
    private fun container(app: PicaPicaApp, fixture: FixtureServer, name: String): AppContainer {
        SessionStore(app, "$name.session").saveToken(FixtureServer.TOKEN)
        return AppContainer(app, SourceConfig(fixture.server.url("/")), name).also(app::installTestContainer)
    }
    @Test fun coverContainsTitleAndAuthorAndLongPressRulesAreExactAndUndoable() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            fixture.metadataFixture.set(true)
            val name = "shortcuts-${System.nanoTime()}"; val c = container(app, fixture, name)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: PicaViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[PicaViewModel::class.java] }
                waitTag("comic-comic1")
                val media = compose.onNodeWithTag("card-media-comic1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val title = compose.onNodeWithTag("card-title-comic1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val author = compose.onNodeWithTag("card-author-comic1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue(media.height > media.width); assertTrue(title.top >= media.top && title.bottom <= media.bottom)
                assertTrue(author.top >= media.top && author.bottom <= media.bottom)
                capture(app, "ui-cover-cards-0.11.0")
                compose.onNodeWithTag("tag-comic1-幻想", useUnmergedTree = true).performTouchInput { longClick(center) }
                compose.waitUntil(10_000) { "幻想" in vm.state.value.blockRules.tags };assertEquals(Screen.HOME, vm.state.value.screen)
                compose.onNodeWithTag("comic-comic1").assertDoesNotExist()
                compose.onNodeWithText("撤销").performClick();waitTag("comic-comic1")
                compose.onNodeWithTag("category-label-comic1-日常", useUnmergedTree = true).performTouchInput { longClick(center) }
                compose.waitUntil(10_000) { "日常" in vm.state.value.blockRules.categories }
                compose.onNodeWithText("撤销").performClick();waitTag("comic-comic1")
                compose.onNodeWithTag("comic-comic1").performTouchInput { longClick(center) }
                compose.waitUntil(10_000) { "comic1" in vm.state.value.blockRules.comics }
                assertTrue(vm.state.value.blockRules.keywords.isEmpty());assertEquals(Screen.HOME, vm.state.value.screen)
                assertTrue(fixture.paths.none { it == "/comics/comic1" })
                val stored = ContentBlocking(app, "$name.blocking").rules.value
                assertTrue(stored.blocks(Comic("comic1", "Renamed title")))
                assertFalse(stored.blocks(Comic("other", fixture.comic().getString("title"))))
                scenario.onActivity { vm.navigate(Screen.BLOCKING) };waitTag("block-comics")
                compose.onNodeWithTag("block-comics").performScrollTo().performClick();waitTag("unblock-comic-comic1")
                compose.onNodeWithTag("unblock-comic-comic1").performClick();compose.onNodeWithText("完成").performClick()
                scenario.onActivity { vm.tab(Screen.HOME) };waitTag("comic-comic1")
                assertTrue(vm.state.value.blockRules.comics.isEmpty());assertTrue(fixture.violations.isEmpty())
            }
            c.db.close()
        }
    }
    @Test fun categoryBottomTabHasRankingIconAndCategoryLongPressDoesNotNavigate() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val c = container(app, fixture, "category-nav-${System.nanoTime()}")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: PicaViewModel;scenario.onActivity { vm = ViewModelProvider(it)[PicaViewModel::class.java] }
                waitTag("nav-categories");compose.onNodeWithTag("home-categories").assertDoesNotExist();compose.onNodeWithTag("home-rankings").assertDoesNotExist()
                compose.onNodeWithTag("nav-categories").performClick();waitTag("category-daily");compose.onNodeWithTag("nav-categories").assertIsSelected()
                compose.onNodeWithTag("category-daily").performTouchInput { longClick(center) }
                compose.waitUntil(10_000) { "日常" in vm.state.value.blockRules.categories };assertEquals(Screen.CATEGORIES, vm.state.value.screen)
                compose.onNodeWithText("撤销").performClick()
                capture(app, "ui-categories-navigation-0.11.0")
                compose.onNodeWithTag("open-rankings").performClick();waitTag("ranked-comic1")
                compose.onNodeWithTag("nav-categories").assertDoesNotExist()
                compose.onNodeWithTag("ranking-D7").performClick();waitTag("ranked-comic1")
                compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick();waitTag("category-daily")
                compose.onNodeWithTag("nav-categories").assertIsSelected()
                compose.onNodeWithTag("nav-home").performClick();waitTag("comic-comic1")
                assertTrue(fixture.violations.isEmpty())
            };c.db.close()
        }
    }
    @Test fun deeperThanEightLevelsExpandAndReplyTargetsTheSelectedChildWithSeparateDrafts() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            fixture.deepThreads.set(true)
            val c = container(app, fixture, "deep-inline-${System.nanoTime()}")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitTag("comic-comic1");compose.onNodeWithTag("comic-comic1").performClick();waitTag("open-comments")
                compose.onNodeWithTag("open-comments").performScrollTo().performClick();waitTag("comment-c1")
                compose.onNodeWithTag("comment-draft").performTextInput("主评论独立草稿")
                compose.onNodeWithTag("comment-replies-c1").performScrollTo().performClick();waitTag("inline-reply-reply1")
                compose.onNodeWithTag("inline-toggle-reply1").performScrollTo().performClick();waitTag("inline-reply-deep0")
                for (index in 0..10) {
                    compose.onNodeWithTag("inline-toggle-deep$index").performScrollTo().performClick();waitTag("inline-reply-deep${index+1}")
                }
                compose.onNodeWithTag("inline-reply-action-deep9").performScrollTo().performClick();waitTag("inline-reply-composer")
                compose.onNodeWithTag("inline-reply-draft").performTextInput("只回复第九层")
                compose.onNodeWithTag("inline-reply-cancel").performScrollTo().performClick()
                compose.onNodeWithTag("inline-reply-action-deep10").performScrollTo().performClick();waitTag("inline-reply-composer")
                compose.onNodeWithTag("inline-reply-draft").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")));compose.onNodeWithTag("inline-reply-draft").performTextInput("第十层独立草稿")
                compose.onNodeWithTag("inline-reply-cancel").performScrollTo().performClick()
                compose.onNodeWithTag("inline-reply-action-deep9").performScrollTo().performClick();waitTag("inline-reply-composer")
                compose.onNodeWithTag("inline-reply-draft").assertTextContains("只回复第九层");readySend()
                compose.onNodeWithTag("inline-reply-send").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("inline-reply-composer").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("comments-list").performScrollToNode(hasTestTag("inline-reply-deepPosted9_0"));waitTag("inline-reply-deepPosted9_0")
                assertEquals(listOf("deep9" to "只回复第九层"), fixture.deepReplyPosts.toList())
                compose.onNodeWithTag("comment-draft").assertTextContains("主评论独立草稿")
                scenario.onActivity { assertNull(ViewModelProvider(it)[PicaViewModel::class.java].state.value.replyParent) }
                assertTrue(fixture.commentsPosted.isEmpty());assertTrue(fixture.repliesPosted.isEmpty());assertTrue(fixture.violations.isEmpty())
                capture(app, "ui-deep-inline-reply-0.11.0")
            };c.db.close()
        }
    }
    @Test fun closedComicCannotReceiveRepliesFromRecentComments() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            fixture.allowComments.set(false)
            val c = container(app, fixture, "closed-reply-${System.nanoTime()}")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitTag("nav-profile");compose.onNodeWithTag("nav-profile").performClick();waitTag("recent-comment-mine1")
                compose.onNodeWithTag("inline-toggle-mine1").performScrollTo().performClick();waitTag("inline-reply-historyReply")
                compose.onNodeWithTag("inline-reply-action-historyReply").performScrollTo().performClick();waitTag("inline-reply-composer")
                compose.onNodeWithTag("inline-reply-draft").performTextInput("未发送草稿")
                compose.waitUntil(10_000) { compose.onAllNodesWithText("这部作品已关闭评论").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("inline-reply-send").assertIsNotEnabled()
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].sendInlineReply() }
                assertTrue(fixture.historyReplyPosts.isEmpty())
                compose.onNodeWithTag("inline-reply-cancel").performScrollTo().performClick()
            };c.db.close()
        }
    }
    @Test fun recentCommentChildCanReplyWithoutLeavingProfile() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            fixture.inlinePages.set(true)
            val c = container(app, fixture, "recent-direct-reply-${System.nanoTime()}")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitTag("nav-profile");compose.onNodeWithTag("nav-profile").performClick();waitTag("recent-comment-mine1")
                compose.onNodeWithTag("inline-toggle-mine1").performScrollTo().performClick();waitTag("inline-reply-historyReply")
                compose.onNodeWithTag("inline-reply-action-historyReply").performScrollTo().performClick();waitTag("inline-reply-composer")
                compose.onNodeWithTag("inline-reply-draft").performTextInput("最近评论中直接回复")
                readySend();compose.onNodeWithTag("inline-reply-send").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("inline-reply-composer").fetchSemanticsNodes().isEmpty() }
                waitTag("inline-reply-historyPosted0")
                scenario.onActivity { assertEquals(Screen.PROFILE, ViewModelProvider(it)[PicaViewModel::class.java].state.value.screen) }
                assertEquals(listOf("最近评论中直接回复"), fixture.historyReplyPosts.toList())
                assertTrue(fixture.violations.isEmpty())
            };c.db.close()
        }
    }
}
