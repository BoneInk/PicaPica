package com.boneink.picapica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiscoveryFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitTag(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitFavourite(selected: Boolean) { compose.waitUntil(10_000) { runCatching {
        val node = compose.onNodeWithTag("comic-favourite").assertIsEnabled()
        if (selected) node.assertIsSelected() else node.assertIsNotSelected()
    }.isSuccess } }
    @Test fun categoryPaginationRankingAndCloudFavouriteRoundTrip() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        FixtureServer().use { fixture ->
            val storage = "discover-flow-${System.currentTimeMillis()}"
            com.boneink.picapica.data.SessionStore(app, "$storage.session").saveToken(FixtureServer.TOKEN)
            val test = AppContainer(app, com.boneink.picapica.network.SourceConfig(fixture.server.url("/")), storage)
            app.installTestContainer(test)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitTag("comic-comic1")
                compose.onNodeWithTag("nav-categories").performClick()
                waitTag("category-daily"); capture(app, "categories")
                compose.onNodeWithTag("category-daily").performClick(); waitTag("comic-comic1")
                compose.onNodeWithTag("collection-more").performScrollTo().performClick(); waitTag("comic-comic2")
                compose.onNodeWithTag("comic-comic1").performScrollTo().performClick(); waitTag("comic-favourite")
                compose.onNodeWithTag("comic-favourite").performScrollTo().performClick()
                waitFavourite(true)
                compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
                compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
                compose.onNodeWithTag("nav-library").performClick(); compose.onNodeWithTag("library-cloud").performClick()
                waitTag("comic-comic1")
                scenario.onActivity { androidx.lifecycle.ViewModelProvider(it)[com.boneink.picapica.ui.PicaViewModel::class.java].clearNotice() }
                capture(app, "cloud-favourites")
                compose.onNodeWithTag("comic-comic1").performClick(); waitTag("comic-favourite")
                compose.onNodeWithTag("comic-favourite").performScrollTo().assertIsSelected().performClick()
                waitFavourite(false)
                compose.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
                compose.onNodeWithText("还没有云收藏").assertIsDisplayed()
                compose.onNodeWithTag("nav-home").performClick(); compose.onNodeWithTag("nav-categories").performClick();compose.onNodeWithTag("open-rankings").performClick()
                waitTag("ranked-comic1")
                compose.onNodeWithTag("ranking-D7").performClick(); compose.waitForIdle()
                compose.onNodeWithTag("ranking-D30").performClick(); waitTag("ranked-comic1")
                scenario.onActivity { androidx.lifecycle.ViewModelProvider(it)[com.boneink.picapica.ui.PicaViewModel::class.java].clearNotice() }
                capture(app, "rankings")
                assertTrue(fixture.paths.contains("/categories")); assertTrue(fixture.paths.contains("/users/favourite"))
                assertTrue(fixture.paths.contains("/comics/leaderboard")); assertTrue(fixture.violations.toString(), fixture.violations.isEmpty())
            }
            test.db.close()
        }
    }
}
