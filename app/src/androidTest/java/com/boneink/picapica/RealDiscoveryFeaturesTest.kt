package com.boneink.picapica

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.network.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real API metadata and a reversible bookmark round trip; no images or account identity output. */
@RunWith(AndroidJUnit4::class)
class RealDiscoveryFeaturesTest {
    @Test fun realCategoriesRankingsAndCloudFavourites() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("realFeatures") == "true")
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        assertTrue(app.container.repo.signedIn.value)
        val api = app.container.repo.api
        val categories = api.categories()
        assertTrue(categories.isNotEmpty())
        val category = api.category("WEBTOON", ComicSort.UPDATED)
        assertTrue(category.items.isNotEmpty())
        val rankings = RankingPeriod.entries.associate { it.code to api.ranking(it).size }
        assertTrue(rankings.values.all { it > 0 })
        val id = requireNotNull(args.getString("comicId"))
        val originallyFavourite = api.comic(id).isFavourite
        var attemptedToggle = false
        var verifiedListed = false
        try {
            if (!originallyFavourite) {
                attemptedToggle = true
                assertTrue(api.toggleFavourite(id))
                assertTrue(api.comic(id).isFavourite)
            }
            val favourites = api.favourites(sort = ComicSort.NEWEST)
            assertTrue(favourites.page >= 1)
            verifiedListed = favourites.items.any { it.id == id }
        } finally {
            if (!originallyFavourite && attemptedToggle) {
                if (api.comic(id).isFavourite) assertFalse(api.toggleFavourite(id))
                assertFalse("Restore the user's original cloud state", api.comic(id).isFavourite)
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putInt("real_categories", categories.size)
            putInt("real_category_results", category.items.size)
            putString("real_rankings", rankings.toString())
            putString("cloud_favourite", if (originallyFavourite) "existing_state_preserved" else "add_verify_remove_round_trip_passed")
            putString("listed_on_first_page", verifiedListed.toString())
            putString("original_cloud_state_restored", "true")
            putString("images_downloaded", "false")
        })
    }
}
