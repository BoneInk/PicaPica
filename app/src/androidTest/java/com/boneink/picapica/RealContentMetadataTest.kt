package com.boneink.picapica

import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.data.ContentBlockRules
import com.boneink.picapica.ui.*
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Metadata and reply GETs only, no screenshots, artwork or account mutations. */
@RunWith(AndroidJUnit4::class)
class RealContentMetadataTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun realMetadataFiltersAndRecentInlineReplies() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realContentMetadata") == "true")
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val c = AppContainer(app, storageName = "real-content-${System.nanoTime()}", sessionName = "picapica.session")
        assertTrue(c.repo.signedIn.value)
        app.installTestContainer(c)
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(app).memoryCache(null).diskCache(null).okHttpClient(c.network.imageHttp.newBuilder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(404).message("Metadata only").body(ByteArray(0).toResponseBody()).build()
        }.build()).build())
        try {
            val comic = c.repo.comic("6a1c5e2e200d604e2a4fed0e")
            assertTrue(comic.categoriesAvailable); assertTrue(comic.tagsAvailable); assertTrue(comic.completionKnown)
            assertTrue(comic.categories.isNotEmpty())
            assertTrue(ContentBlockRules(categories = setOf(comic.categories.first())).blocks(comic))
            if (comic.tags.isNotEmpty()) assertTrue(ContentBlockRules(tags = setOf(comic.tags.first())).blocks(comic))
            var ownReplies = 0
            var replyItems = 0
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var model: PicaViewModel? = null
                scenario.onActivity { model = ViewModelProvider(it)[PicaViewModel::class.java]; model!!.tab(Screen.PROFILE) }
                val vm = requireNotNull(model)
                compose.waitUntil(60_000) { !vm.state.value.myCommentsLoading && !vm.state.value.profileLoading }
                assertNull(vm.state.value.myCommentsError)
                val recent = vm.state.value.myComments.firstOrNull { it.replies > 0 && !it.hidden }
                if (recent != null) {
                    ownReplies = 1
                    scenario.onActivity { vm.openMyComments() }
                    compose.waitUntil(60_000) { !vm.state.value.myCommentsLoading }
                    compose.onNodeWithTag("my-comments-list").performScrollToNode(hasTestTag("inline-toggle-${recent.id}"))
                    compose.onNodeWithTag("inline-toggle-${recent.id}").performClick()
                    compose.waitUntil(60_000) { vm.state.value.inlineReplies[recent.id]?.let { !it.loading } == true }
                    assertNull(vm.state.value.inlineReplies[recent.id]!!.error)
                    assertEquals(Screen.MY_COMMENTS, vm.state.value.screen)
                    replyItems = vm.state.value.inlineReplies[recent.id]!!.items.size
                    assertTrue(vm.state.value.inlineReplies[recent.id]!!.loaded)
                }
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("categories_present", comic.categories.size.toString()); putString("tags_present", comic.tags.size.toString())
                putString("status_known", comic.completionKnown.toString()); putString("real_block_match", "passed")
                putString("own_recent_comment_with_replies", ownReplies.toString()); putString("inline_reply_items", replyItems.toString())
                putString("real_mutations", "0")
            })
        } finally { c.db.close() }
    }
}
