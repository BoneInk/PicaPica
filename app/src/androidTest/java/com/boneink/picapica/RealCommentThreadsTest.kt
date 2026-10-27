package com.boneink.picapica

import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.network.*
import com.boneink.picapica.ui.PicaViewModel
import com.boneink.picapica.ui.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Live GETs only; reply writes are exercised exclusively against the local fixture. */
@RunWith(AndroidJUnit4::class)
class RealCommentThreadsTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun waitTag(tag:String) { compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun nestedReplyReadAndDirectHistoryThreadAreSupportedByRealService()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realCommentThreads")=="true")
        val app=ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val container=AppContainer(app,storageName="real-threads-${System.currentTimeMillis()}",sessionName="picapica.session")
        assertTrue(container.repo.signedIn.value)
        try {
            val api=container.repo.api
            val book="6a1c5e2e200d604e2a4fed0e"
            val rootPage=api.comments(book)
            val root=(rootPage.pinned+rootPage.items).first { it.replies>0 && !it.hidden }
            val children=api.replies(root.id)
            val child=children.items.first { !it.hidden }
            val grandchildren=api.replies(child.id)
            val history=api.myComments()
            val selected=history.items.first { it.comicId!=null && !it.hidden }
            app.installTestContainer(container)
            // No comic art or user avatars are required for this text-only acceptance test.
            coil.Coil.setImageLoader(coil.ImageLoader.Builder(app).memoryCache(null).diskCache(null).okHttpClient(
                container.network.imageHttp.newBuilder().addInterceptor { throw java.io.IOException("Text-only verification") }.build()).build())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var model:PicaViewModel?=null
                scenario.onActivity { model=ViewModelProvider(it)[PicaViewModel::class.java];model!!.openComic(book) }
                val vm=requireNotNull(model)
                waitTag("open-comments");compose.onNodeWithTag("open-comments").performScrollTo().performClick();waitTag("comment-${root.id}")
                compose.onNodeWithTag("comment-replies-${root.id}").performScrollTo().performClick();waitTag("comment-${child.id}")
                compose.onNodeWithTag("comment-replies-${child.id}").performScrollTo().performClick()
                compose.waitUntil(60_000) { !vm.state.value.commentLoading }
                assertTrue(vm.state.value.replyParent?.id==child.id);assertNull(vm.state.value.commentError)
                compose.onNodeWithTag("comment-draft").assertIsEnabled()
                scenario.onActivity { vm.goBack() }
                assertTrue(vm.state.value.replyParent?.id==root.id)
                scenario.onActivity { vm.tab(Screen.PROFILE) }
                compose.waitUntil(60_000) { !vm.state.value.profileLoading && !vm.state.value.myCommentsLoading }
                scenario.onActivity { vm.openCommentHistory(selected) };waitTag("history-comment-anchor")
                compose.waitUntil(60_000) { !vm.state.value.commentLoading }
                assertTrue(vm.state.value.replyParent?.id==selected.id)
                assertNull(vm.state.value.commentError)
                assertTrue(vm.state.value.commentContextReady)
                compose.onNodeWithTag("all-comic-comments").performClick()
                compose.waitUntil(60_000) { !vm.state.value.commentLoading }
                assertNull(vm.state.value.replyParent);assertNull(vm.state.value.commentError)
                scenario.onActivity { vm.goBack() }
                assertEquals(Screen.PROFILE,vm.state.value.screen)
                InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
                    putString("nested_child_read_api","passed")
                    putString("nested_child_records",grandchildren.items.size.toString())
                    putString("child_reply_composer","enabled")
                    putString("nested_back_context","restored")
                    putString("history_direct_thread","passed")
                    putString("all_comic_comments","passed")
                    putString("return_to_history_origin","passed")
                    putString("real_mutations","0")
                })
            }
        } finally { container.db.close() }
    }
}
