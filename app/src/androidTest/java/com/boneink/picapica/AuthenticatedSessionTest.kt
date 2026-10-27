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

/** Opt-in check of a session entered by the user. Never accepts or emits credentials. */
@RunWith(AndroidJUnit4::class)
class AuthenticatedSessionTest {
    @Test fun persistedSessionCanReadRealProfileHomeAndSearch() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAccount") == "true")
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val container = app.container
        assertEquals("picaapi.picacomic.com", container.config.apiBase.host)
        assertTrue(container.config.apiBase.isHttps)
        assertTrue("A user-entered encrypted session must survive process restart", container.repo.signedIn.value)
        val profile = container.repo.api.profile()
        assertTrue("Authenticated profile must contain an ID", profile.id.isNotBlank())
        val home = container.repo.home()
        assertTrue("Real home must contain results", home.items.isNotEmpty())
        val search = container.repo.search("测试", 1)
        assertTrue("Search must return valid pagination", search.pages >= 0)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("real_account_session", "restored_and_authenticated")
            putString("profile", "passed_without_emitting_identity")
            putInt("home_count", home.items.size)
            putInt("search_count", search.items.size)
            putString("connection_route", container.network.route.value.name)
            putString("credentials_emitted", "false")
            putString("content_images_downloaded", "false")
        })
    }
}
