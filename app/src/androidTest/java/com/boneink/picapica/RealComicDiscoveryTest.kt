package com.boneink.picapica

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.boneink.picapica.network.*

/** Metadata only: find a general-audience work before fetching any image. */
@RunWith(AndroidJUnit4::class)
class RealComicDiscoveryTest {
    @Test fun findGeneralAudienceComicMetadata() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realComic") == "true")
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val api = app.container.repo.api
        assertTrue(app.container.repo.signedIn.value)
        val categories = api.categories()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putInt("visible_categories", categories.size)
            putString("category_names", categories.joinToString { it.title })
        })
        val category = categories.firstOrNull { it.title in setOf("全年龄", "全年齡", "一般向", "全年齡向", "非H", "非Ｈ", "非h") }
        val results = if (category != null) api.category(category.title, ComicSort.NEWEST) else api.search("非H")
        assertTrue(results.items.isNotEmpty())
        val data = Bundle().apply {
            putString("general_audience_source", category?.title ?: "search: 非H")
            putInt("categories_count", categories.size)
            // Titles/author metadata only; never render or fetch arbitrary covers in this discovery phase.
            putStringArrayList("candidates", ArrayList(results.items.take(8).map { "${it.id} | ${it.title} | ${it.author}" }))
            putString("images_downloaded", "false")
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, data)
    }
}
