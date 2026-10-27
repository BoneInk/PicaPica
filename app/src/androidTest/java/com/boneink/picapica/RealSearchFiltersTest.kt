package com.boneink.picapica

import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.ui.PicaViewModel
import com.boneink.picapica.network.SearchSort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Search is a read-only POST; logs contain counts only, with no user or work text. */
@RunWith(AndroidJUnit4::class)
class RealSearchFiltersTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitTag(tag: String) { compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun categoryAndEachOriginalSortWorkThroughSearchUi() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realSearchFilters") == "true")
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val container = AppContainer(app, storageName = "real-search-${System.currentTimeMillis()}", sessionName = "picapica.session")
        assertTrue(container.repo.signedIn.value)
        try {
            app.installTestContainer(container)
            coil.Coil.setImageLoader(coil.ImageLoader.Builder(app).memoryCache(null).diskCache(null).okHttpClient(
                container.network.imageHttp.newBuilder().addInterceptor { throw java.io.IOException("Text-only validation") }.build()).build())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: PicaViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[PicaViewModel::class.java];vm.openSearch() }
                compose.waitUntil(60_000) { !vm.state.value.categoriesLoading && vm.state.value.categories.size > 1 }
                assertNull(vm.state.value.categoriesError)
                val available = vm.state.value.categories.filterNot { it.id == com.boneink.picapica.network.OfficialDiscussion.category.id }
                val category = available.firstOrNull { it.title.contains("webtoon", ignoreCase = true) } ?: available.first()
                compose.onNodeWithTag("search-categories").performClick();waitTag("search-category-${category.id}")
                compose.onNodeWithTag("search-category-${category.id}").performScrollTo().performClick()
                compose.onNodeWithTag("search-category-apply").performClick()
                compose.waitUntil(60_000) { vm.state.value.searchSubmitted && !vm.state.value.loading }
                assertNull(vm.state.value.error);assertTrue(vm.state.value.results.isNotEmpty())
                val counts = mutableMapOf("dd" to vm.state.value.results.size)
                for (sort in SearchSort.entries.filterNot { it == SearchSort.NEWEST }) {
                    compose.onNodeWithTag("search-sort-${sort.code}").performScrollTo().performClick()
                    compose.waitUntil(60_000) { !vm.state.value.loading }
                    assertNull(vm.state.value.error);assertEquals(sort, vm.state.value.searchSort)
                    assertEquals(setOf(category.title), vm.state.value.searchCategories)
                    assertTrue(vm.state.value.results.isNotEmpty());counts[sort.code] = vm.state.value.results.size
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("category_search", "passed")
                    putString("sort_result_counts", counts.toString())
                    putString("real_mutations", "0")
                    putString("real_screenshots", "0")
                })
            }
        } finally { container.db.close() }
    }
}
