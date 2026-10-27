package com.boneink.picapica

import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.network.OfficialDiscussion
import com.boneink.picapica.ui.PicaViewModel
import com.boneink.picapica.ui.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Text-only real GET validation; never writes to the public official discussion board. */
@RunWith(AndroidJUnit4::class)
class RealBoardNavigationTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun waitTag(tag:String) { compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun officialDiscussionAndCommentWorkRoundTripUseRealService()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realBoardNavigation")=="true")
        val app=ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val container=AppContainer(app,storageName="real-board-${System.currentTimeMillis()}",sessionName="picapica.session")
        assertTrue(container.repo.signedIn.value)
        try {
            app.installTestContainer(container)
            coil.Coil.setImageLoader(coil.ImageLoader.Builder(app).memoryCache(null).diskCache(null).okHttpClient(
                container.network.imageHttp.newBuilder().addInterceptor { throw java.io.IOException("Text-only validation") }.build()).build())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var model:PicaViewModel?=null
                scenario.onActivity { model=ViewModelProvider(it)[PicaViewModel::class.java];model!!.tab(Screen.CATEGORIES) }
                val vm=requireNotNull(model)
                waitTag("category-official-discussion");compose.onNodeWithTag("category-official-discussion").performClick()
                compose.waitUntil(60_000) { !vm.state.value.commentLoading }
                assertTrue(vm.state.value.isOfficialDiscussion);assertNull(vm.state.value.commentError)
                assertTrue(vm.state.value.comic?.id==OfficialDiscussion.COMIC_ID)
                assertTrue(vm.state.value.comments.isNotEmpty() || vm.state.value.pinnedComments.isNotEmpty())
                val boardCount=vm.state.value.comments.size
                compose.onNodeWithTag("comment-open-work").assertDoesNotExist()
                scenario.onActivity { vm.goBack() };waitTag("category-official-discussion")
                scenario.onActivity { vm.openComic("6a1c5e2e200d604e2a4fed0e") };waitTag("open-comments")
                compose.onNodeWithTag("open-comments").performScrollTo().performClick()
                compose.waitUntil(60_000) { !vm.state.value.commentLoading }
                val root=(vm.state.value.pinnedComments+vm.state.value.comments).first { it.replies>0 && !it.hidden }
                compose.onNodeWithTag("comment-replies-${root.id}").performScrollTo().performClick()
                compose.waitUntil(60_000) { vm.state.value.inlineReplies[root.id]?.loading == false }
                assertNull(vm.state.value.inlineReplies[root.id]?.error)
                compose.onNodeWithTag("comment-open-work").performClick()
                compose.waitUntil(60_000) { !vm.state.value.loading && vm.state.value.chapters.isNotEmpty() }
                assertEquals(Screen.DETAIL,vm.state.value.screen)
                compose.onNodeWithContentDescription("返回",useUnmergedTree=true).performClick();waitTag("comment-open-work")
                assertEquals(Screen.COMMENTS,vm.state.value.screen)
                assertNull(vm.state.value.replyParent); assertTrue(vm.state.value.inlineReplies[root.id]?.expanded == true)
                InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
                    putString("official_discussion","passed")
                    putString("official_first_page_comments",boardCount.toString())
                    putString("comment_to_work","passed")
                    putString("work_to_original_thread","passed")
                    putString("real_mutations","0")
                })
            }
        } finally { container.db.close() }
    }
}
