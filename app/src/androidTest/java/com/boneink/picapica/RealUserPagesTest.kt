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
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Only GETs and avatar images. No comments, likes, profile changes or external credential reads. */
@RunWith(AndroidJUnit4::class)
class RealUserPagesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun waitTag(tag: String) { compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun realRecentCommentsPublicUserDetailsAndAvatarPreview() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realUserPages") == "true")
        val app=ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val container=AppContainer(app,storageName="real-user-pages-${System.currentTimeMillis()}",sessionName="picapica.session")
        assertTrue(container.repo.signedIn.value)
        try {
            val api=container.repo.api
            val self=api.profile()
            val ownComments=api.myComments()
            val book="6a1c5e2e200d604e2a4fed0e"
            val comments=api.comments(book)
            val selected=(comments.pinned+comments.items).firstOrNull { it.user?.avatarUrl?.isNotBlank()==true }
                ?: error("Choose a known comment author with a public avatar")
            val allowed=setOf(self.avatarUrl, selected.user!!.avatarUrl).filter(String::isNotBlank).toSet()
            app.installTestContainer(container)
            val images=java.util.concurrent.atomic.AtomicInteger()
            coil.Coil.setImageLoader(coil.ImageLoader.Builder(app).memoryCache(null).diskCache(null).okHttpClient(
                container.network.imageHttp.newBuilder().addInterceptor { chain ->
                    if (chain.request().url.toString() !in allowed) Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(404).message("Outside this test").body(ByteArray(0).toResponseBody()).build()
                    else { images.incrementAndGet(); chain.proceed(chain.request()) }
                }.build()).build())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitTag("nav-profile");compose.onNodeWithTag("nav-profile").performClick()
                var model: PicaViewModel?=null
                scenario.onActivity { model=ViewModelProvider(it)[PicaViewModel::class.java] }
                val vm=requireNotNull(model)
                compose.waitUntil(60_000) { !vm.state.value.profileLoading && !vm.state.value.myCommentsLoading }
                assertTrue("Real own profile must load", vm.state.value.selfProfile?.id==self.id)
                assertNull(vm.state.value.myCommentsError)
                assertEquals(ownComments.total,vm.state.value.myCommentTotal)
                scenario.onActivity { vm.openMyComments() };waitTag("my-comments-list")
                compose.waitUntil(60_000) { !vm.state.value.myCommentsLoading }
                assertNull(vm.state.value.myCommentsError)
                assertEquals(ownComments.items.size,vm.state.value.myComments.size)
                scenario.onActivity { vm.openComic(book) };waitTag("open-comments")
                compose.onNodeWithTag("open-comments").performScrollTo().performClick();waitTag("comment-${selected.id}")
                compose.onNodeWithTag("comment-avatar-${selected.id}").performScrollTo().performClick();waitTag("user-details")
                assertTrue("Public details must use the selected author", vm.state.value.viewedUser?.id==selected.user!!.id)
                compose.onNodeWithTag("user-details-avatar").performClick();waitTag("avatar-preview")
                compose.waitUntil(60_000) { compose.onAllNodesWithContentDescription("头像大图已加载").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("avatar-close").performClick();waitTag("user-details")
                scenario.onActivity { vm.closeUserDetails() }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("user-details").fetchSemanticsNodes().isEmpty() }
                assertTrue(vm.state.value.comments.any { it.id==selected.id } || vm.state.value.pinnedComments.any { it.id==selected.id })
                InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
                    putString("self_profile","passed")
                    putString("recent_comments_first_page",ownComments.items.size.toString())
                    putString("recent_comments_total",ownComments.total.toString())
                    putString("recent_comments_ui","passed")
                    putString("public_user_details","passed")
                    putString("avatar_preview_real_https","loaded_without_image_cache")
                    putString("avatar_image_requests",images.get().toString())
                    putString("real_mutations","0")
                })
            }
        } finally { container.db.close() }
    }
}
