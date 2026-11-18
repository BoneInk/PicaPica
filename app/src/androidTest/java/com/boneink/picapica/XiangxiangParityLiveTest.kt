package com.boneink.picapica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.data.SessionStore
import com.boneink.picapica.network.*
import com.boneink.picapica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Proxy
import java.net.URL
import java.io.File

/** Opt-in: uses an ephemeral session bridge and restores the single temporary favourite in finally. */
@RunWith(AndroidJUnit4::class)
class XiangxiangParityLiveTest {
    @get:Rule val compose = createEmptyComposeRule()
    private suspend fun allFavourites(api: ComicSource): Set<String> {
        val ids = linkedSetOf<String>(); var page = 1
        while (page <= 100) {
            val rows = api.favourites(page); ids += rows.items.map { it.id }
            if (rows.page >= rows.pages) return ids
            page++
        }
        error("Favourite verification exceeded its page limit")
    }
    @Test fun profileHistoryCloudPaginationAndFavouriteRoundTrip() {
        val bridge = InstrumentationRegistry.getArguments().getString("sessionBridge")
        assumeTrue(bridge != null)
        val session = URL(bridge).openConnection(Proxy.NO_PROXY).getInputStream().bufferedReader().use { it.readText() }
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>(); val original = app.container
        val storage = "xx-parity-live-${System.currentTimeMillis()}"
        SessionStore(app, "$storage.session.xiangxiang").saveToken(session)
        val container = AppContainer(app, storageName = storage); container.accounts.select("xiangxiang")
        app.installTestContainer(container)
        val api = container.repo.api
        var temporary: String? = null
        try {
            val before = runBlocking { allFavourites(api) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: PicaViewModel
                compose.onNodeWithTag("nav-profile").performClick()
                scenario.onActivity { model = ViewModelProvider(it)[PicaViewModel::class.java] }
                compose.waitUntil(45_000) { !model.state.value.profileLoading && model.state.value.selfProfile != null }
                assertNull(model.state.value.profileError)
                assertTrue(model.state.value.selfProfile!!.avatarUrl.isNotBlank())
                compose.onNodeWithTag("profile-stat-FU金").assertIsDisplayed()
                compose.onNodeWithTag("profile-stat-经验").assertIsDisplayed()
                compose.waitUntil(30_000) { !model.state.value.myCommentsLoading }
                assertNull(model.state.value.myCommentsError)
                scenario.onActivity { model.openFavourites() }
                compose.waitUntil(45_000) { !model.state.value.loading && model.state.value.favourites.isNotEmpty() }
                assertNull(model.state.value.error)
                assertTrue(model.state.value.favourites.all { it.id in before })
                val candidate = runBlocking { api.comics().items.first { it.id !in before && !api.comic(it.id).isFavourite } }
                temporary = candidate.id
                scenario.onActivity { model.openComic(candidate.id) }
                compose.waitUntil(45_000) { !model.state.value.loading && model.state.value.comic?.id == candidate.id }
                assertNull(model.state.value.error)
                compose.onNodeWithTag("comic-favourite").performScrollTo().performClick()
                compose.waitUntil(45_000) { !model.state.value.loading && model.state.value.comic?.isFavourite == true }
                assertNull(model.state.value.error)
                assertTrue(runBlocking { api.comic(candidate.id).isFavourite })
                scenario.onActivity { model.goBack() }
                compose.waitUntil(45_000) { !model.state.value.loading && model.state.value.screen == Screen.LIBRARY }
                assertTrue(runBlocking { candidate.id in allFavourites(api) })
                scenario.onActivity { model.openComic(candidate.id) }
                compose.waitUntil(45_000) { !model.state.value.loading && model.state.value.comic?.isFavourite == true }
                compose.onNodeWithTag("comic-favourite").performScrollTo().performClick()
                compose.waitUntil(45_000) { !model.state.value.loading && model.state.value.comic?.isFavourite == false }
                assertNull(model.state.value.error)
                scenario.onActivity { model.goBack() }
                compose.waitUntil(45_000) { !model.state.value.loading && model.state.value.screen == Screen.LIBRARY }
                assertTrue(model.state.value.favourites.none { it.id == candidate.id })
                assertTrue("Cloud favourites were restored", runBlocking { allFavourites(api) } == before)
                temporary = null
            }
        } finally {
            try { temporary?.let { id -> runBlocking { if (api.comic(id).isFavourite) api.toggleFavourite(id) } } }
            finally {
                container.repo.logout(); container.db.close(); app.installTestContainer(original)
                app.deleteDatabase("$storage.db")
                listOf("accounts", "session", "session.xiangxiang", "settings", "network", "blocking", "blocking.XIANGXIANG", "reader").forEach { app.deleteSharedPreferences("$storage.$it") }
                File(app.filesDir, "$storage/downloads").deleteRecursively()
            }
        }
    }
}
