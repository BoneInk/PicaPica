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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockingAndInlineTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitTag(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun container(app: PicaPicaApp, fixture: FixtureServer): AppContainer {
        val name = "blocking-inline-${System.nanoTime()}"
        SessionStore(app, "$name.session").saveToken(FixtureServer.TOKEN)
        return AppContainer(app, SourceConfig(fixture.server.url("/")), name).also(app::installTestContainer)
    }
    @Test fun blockingCanUndoKeepsPaginationAndStoredRecordsAndShowsMetadata() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            fixture.metadataFixture.set(true); fixture.homePages.set(2)
            val c = container(app, fixture)
            var vm: PicaViewModel? = null
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { vm = ViewModelProvider(it)[PicaViewModel::class.java] }
                val model = requireNotNull(vm)
                waitTag("comic-comic1")
                compose.onNodeWithText("未汉化  AI 生成").assertExists()
                compose.onNodeWithTag("category-label-comic1-生肉", useUnmergedTree = true).assertExists()
                compose.onNodeWithTag("tag-comic1-幻想", useUnmergedTree = true).assertExists()
                capture(app, "ui-metadata-0.10.0")
                scenario.onActivity { model.openComic("comic1") }; waitTag("open-comments")
                compose.waitUntil(10_000) { model.state.value.knownComics.containsKey("comic1") }
                val key = c.repo.config.comicKey("comic1")
                c.repo.dao.putProgress(ReadingProgress(key, 1, 2))
                scenario.onActivity { model.tab(Screen.HOME); model.updateBlocking { it.copy(hideAi = true) } }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("comic-comic1").fetchSemanticsNodes().isEmpty() }
                waitTag("home-more"); compose.onNodeWithTag("home-more").performScrollTo().performClick()
                compose.waitUntil(10_000) { model.state.value.homePage == 2 && !model.state.value.loading }
                assertEquals(2, model.state.value.home.size)
                assertTrue(model.state.value.visibleComics(model.state.value.home).isEmpty())
                assertNotNull(c.repo.dao.comic(key)); assertEquals(2, c.repo.dao.progress(key)!!.pageIndex)
                val detailCalls = fixture.paths.count { it == "/comics/comic1" }
                assertEquals(1, detailCalls)
                scenario.onActivity { model.updateBlocking { it.copy(enabled = false) } }
                waitTag("comic-comic1")
                scenario.onActivity { model.navigate(Screen.SETTINGS) }; waitTag("setting-blocking")
                compose.onNodeWithTag("setting-blocking").performScrollTo().performClick(); waitTag("block-keywords")
                capture(app, "ui-blocking-0.10.0")
                compose.onNodeWithTag("block-keywords").performScrollTo().performClick(); waitTag("block-rule-input")
                compose.onNodeWithTag("block-rule-input").performTextReplacement("  ABC  \nＡＢＣ\n雨后")
                compose.onNodeWithTag("block-rule-save").performClick()
                compose.waitUntil(10_000) { model.state.value.blockRules.keywords.size == 2 }
                assertEquals(setOf("ABC", "雨后"), model.state.value.blockRules.keywords)
                scenario.onActivity { model.updateBlocking { it.copy(enabled = true) }; model.openComic("comic1") }
                compose.waitUntil(10_000) { model.state.value.comic != null && !model.state.value.loading }
                compose.onNodeWithText("该作品已被屏蔽").assertExists()
                compose.onNodeWithText("仍要查看").performClick(); waitTag("open-comments")
                assertEquals(2, c.db.openHelper.readableDatabase.version)
                assertTrue(fixture.commentsPosted.isEmpty()); assertTrue(fixture.repliesPosted.isEmpty())
            }
            c.db.close()
        }
    }
    @Test fun rulesPersistWithIndependentToggles() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val name = "blocking-persistence-${System.nanoTime()}"
        val rules = ContentBlocking(app, name)
        rules.update { it.copy(enabled = false, hideAi = true, keywords = setOf("keyword"), tags = setOf("tag"), categories = setOf("category")) }
        assertEquals(rules.rules.value, ContentBlocking(app, name).rules.value)
        assertFalse(rules.rules.value.blocks(Comic("a", "keyword")))
        rules.update { it.copy(enabled = true) }
        assertTrue(rules.rules.value.blocks(Comic("a", "keyword")))
        assertEquals(setOf("tag"), rules.rules.value.tags)
        app.getSharedPreferences(name, 0).edit().clear().commit()
    }
    @Test fun inlineRepliesRetryExpandNestedPaginateCollapseAndRetainSourceNavigation() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            fixture.inlinePages.set(true); fixture.failInlineOnce.set(true)
            val c = container(app, fixture)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitTag("nav-profile"); compose.onNodeWithTag("nav-profile").performClick(); waitTag("recent-comment-mine1")
                compose.onNodeWithTag("inline-toggle-mine1").performScrollTo().performClick()
                var debugVm: PicaViewModel? = null
                scenario.onActivity { debugVm = ViewModelProvider(it)[PicaViewModel::class.java] }
                compose.waitUntil(10_000) { debugVm!!.state.value.inlineReplies["mine1"]?.loading == false }
                assertEquals("Fixture paths: ${fixture.paths}; state=${debugVm!!.state.value.inlineReplies}", Screen.PROFILE, debugVm!!.state.value.screen)
                assertNotNull("Fixture paths: ${fixture.paths}; state=${debugVm!!.state.value.inlineReplies}", debugVm!!.state.value.inlineReplies["mine1"]?.error)
                waitTag("error-message")
                compose.onNodeWithTag("error-message").performScrollTo(); compose.onNodeWithText("重试").performClick()
                waitTag("inline-reply-historyReply")
                scenario.onActivity { assertEquals(Screen.PROFILE, ViewModelProvider(it)[PicaViewModel::class.java].state.value.screen) }
                compose.onNodeWithTag("inline-toggle-historyReply").performScrollTo().performClick(); waitTag("inline-reply-deepReply")
                capture(app, "ui-inline-replies-0.10.0")
                compose.onNodeWithTag("inline-avatar-deepReply").performScrollTo().performClick(); waitTag("user-details")
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].closeUserDetails() }
                compose.onNodeWithTag("inline-more-mine1").performScrollTo().performClick(); waitTag("inline-reply-historyReply2")
                compose.onNodeWithTag("inline-toggle-mine1").performScrollTo().performClick()
                compose.onNodeWithTag("inline-replies-mine1").assertDoesNotExist()
                compose.onNodeWithTag("inline-toggle-mine1").performScrollTo().performClick(); waitTag("inline-reply-historyReply2")
                compose.onNodeWithTag("recent-source-mine1").performScrollTo().performClick(); waitTag("history-comment-anchor")
                compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick(); waitTag("inline-replies-mine1")
                scenario.onActivity { ViewModelProvider(it)[PicaViewModel::class.java].openMyComments() }; waitTag("my-comments-list")
                compose.onNodeWithTag("my-comments-list").performScrollToNode(hasTestTag("inline-toggle-mine2"))
                compose.onNodeWithTag("inline-toggle-mine2").performClick(); waitTag("inline-replies-mine2")
                compose.waitUntil(10_000) { fixture.paths.count { it == "/comments/mine2/childrens" } > 0 }
                assertTrue(fixture.commentsPosted.isEmpty()); assertTrue(fixture.repliesPosted.isEmpty()); assertTrue(fixture.violations.isEmpty())
            }
            c.db.close()
        }
    }
}
