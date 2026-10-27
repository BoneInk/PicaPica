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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchFiltersTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitTag(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun setup(app: PicaPicaApp, fixture: FixtureServer): AppContainer {
        val storage = "search-filters-${System.nanoTime()}"
        SessionStore(app, "$storage.session").saveToken(FixtureServer.TOKEN)
        fixture.filteredSearch.set(true)
        return AppContainer(app, SourceConfig(fixture.server.url("/")), storage).also(app::installTestContainer)
    }
    private fun assertCriteria(body: JSONObject, keyword: String, sort: String, categories: Set<String>, page: Int) {
        assertEquals(keyword, body.getString("keyword"));assertEquals(sort, body.getString("sort"));assertEquals(page, body.getInt("page"))
        val array = body.optJSONArray("categories")
        assertEquals(categories, if (array == null) emptySet<String>() else (0 until array.length()).map { array.getString(it) }.toSet())
    }
    @Test fun combinedFiltersPaginationReturnAndResetUseAppliedCriteria() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val container = setup(app, fixture)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: PicaViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[PicaViewModel::class.java] }
                waitTag("open-search");compose.onNodeWithTag("open-search").performClick();waitTag("search-categories")
                compose.onNodeWithTag("nav-library").assertDoesNotExist()
                compose.onNodeWithTag("search-categories").performClick();waitTag("search-category-daily")
                compose.onNodeWithTag("search-category-official-discussion").assertDoesNotExist()
                compose.onNodeWithTag("search-category-daily").performClick();compose.onNodeWithText("全年龄").performClick()
                compose.onNodeWithTag("search-category-apply").performClick();waitTag("comic-comic1")
                assertCriteria(fixture.searchRequests.last(), "", "dd", setOf("日常", "全年龄"), 1)
                compose.onNodeWithTag("search-input").performTextInput("雨后")
                compose.onNodeWithTag("search-sort-ld").performClick()
                compose.waitUntil(15_000) { fixture.searchRequests.size >= 2 && !vm.state.value.loading }
                assertCriteria(fixture.searchRequests.last(), "雨后", "ld", setOf("日常", "全年龄"), 1)
                compose.waitForIdle();compose.mainClock.advanceTimeBy(500)
                capture(app, "search-combined-0.8.0")
                compose.onNodeWithTag("search-more").performScrollTo().performClick();waitTag("comic-comic2")
                assertCriteria(fixture.searchRequests.last(), "雨后", "ld", setOf("日常", "全年龄"), 2)
                compose.onNodeWithTag("comic-comic1").performScrollTo().performClick();waitTag("chapter-1")
                compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick();waitTag("search-input")
                scenario.onActivity { assertEquals(2, vm.state.value.currentPage);assertEquals(setOf("日常", "全年龄"), vm.state.value.searchCategories) }
                compose.onNodeWithTag("search-sort-ld").assertIsSelected()
                compose.onNodeWithTag("search-input").performScrollTo().assertTextContains("雨后")
                compose.onNodeWithTag("search-clear-categories").performClick()
                compose.waitUntil(15_000) { fixture.searchRequests.size >= 4 && !vm.state.value.loading }
                assertCriteria(fixture.searchRequests.last(), "雨后", "ld", emptySet(), 1)
                assertEquals(1, vm.state.value.currentPage)
                assertTrue(fixture.violations.toString(), fixture.violations.isEmpty())
            }
            container.db.close()
        }
    }
    @Test fun scopedCategorySearchCanCancelDraftRetryAndSwitchSorts() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val container = setup(app, fixture)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: PicaViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[PicaViewModel::class.java] }
                waitTag("nav-categories");compose.onNodeWithTag("nav-categories").performClick();waitTag("category-daily")
                compose.onNodeWithTag("category-daily").performClick();waitTag("comic-comic1")
                compose.onNodeWithContentDescription("在此分类中搜索").performClick();waitTag("search-categories")
                assertEquals(setOf("日常"), vm.state.value.searchCategories)
                compose.onNodeWithTag("search-categories").performClick();waitTag("search-category-daily")
                compose.onNodeWithText("全年龄").performClick()
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("search-category-apply").fetchSemanticsNodes().isEmpty() }
                assertEquals(setOf("日常"), vm.state.value.searchCategories);assertTrue(fixture.searchRequests.isEmpty())
                fixture.failSearchOnce.set(true)
                compose.onNodeWithTag("search-sort-vd").performClick();waitTag("error-message")
                compose.onNodeWithText("重试").performClick();waitTag("comic-comic1")
                assertCriteria(fixture.searchRequests.last(), "", "vd", setOf("日常"), 1)
                compose.onNodeWithTag("search-sort-da").performClick()
                compose.waitUntil(15_000) { fixture.searchRequests.size >= 3 && !vm.state.value.loading }
                assertCriteria(fixture.searchRequests.last(), "", "da", setOf("日常"), 1)
                compose.onNodeWithTag("search-input").performTextInput("无结果");compose.onNodeWithTag("search-submit").performClick();compose.waitUntil(15_000) { !vm.state.value.loading && vm.state.value.results.isEmpty() }
                compose.onNodeWithText("没有找到相关作品").assertIsDisplayed()
                assertCriteria(fixture.searchRequests.last(), "无结果", "da", setOf("日常"), 1)
                compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
                scenario.onActivity { assertEquals(Screen.CATEGORY, vm.state.value.screen) }
            }
            container.db.close()
        }
    }
}
