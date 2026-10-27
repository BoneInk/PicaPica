package com.boneink.picapica

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only real-service validation. Never posts, replies or likes on a user's account. */
@RunWith(AndroidJUnit4::class)
class RealCommentsTest {
    @Test fun realCommentAndReplyResponsesAreReadable() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realComments") == "true")
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        assertTrue(app.container.repo.signedIn.value)
        val api = app.container.repo.api
        val id = "6a1c5e2e200d604e2a4fed0e"
        val page = api.comments(id)
        assertTrue(page.page >= 1)
        assertTrue((page.items + page.pinned).all { it.id.isNotBlank() && it.author.isNotBlank() })
        val parent = (page.pinned + page.items).firstOrNull { it.replies > 0 }
        val replies = parent?.let { api.replies(it.id) }
        assertTrue(replies?.items?.all { it.id.isNotBlank() && it.author.isNotBlank() } != false)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("comments_on_first_page", page.items.size.toString())
            putString("pinned_comments", page.pinned.size.toString())
            putString("replies_on_first_page", replies?.items?.size?.toString() ?: "no_parent_with_replies")
            putString("real_comment_mutations", "0")
        })
    }
}
