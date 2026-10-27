package com.boneink.picapica

import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly opted-in live transport probe, never a real account or content test. */
@RunWith(AndroidJUnit4::class)
class LiveEndpointTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun documentedRouteReachesRealAuthenticationEndpointOverVerifiedTls() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveProbe") == "true")
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val container = AppContainer(app, storageName = "live-probe")
        try {
            container.network.select(com.boneink.picapica.network.ConnectionRoute.valueOf(
                InstrumentationRegistry.getArguments().getString("connectionRoute") ?: "AUTO"))
            assumeTrue("Respect a previous server cooldown", container.repo.api.backoff.remainingSeconds() == 0L)
            app.installTestContainer(container)
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("empty-action").performClick()
                compose.onNodeWithTag("login-account").performTextInput("picapica-connectivity-probe@example.invalid")
                compose.onNodeWithTag("login-password").performTextInput("not-a-real-account")
                compose.onNodeWithTag("login-submit").performClick()
                compose.waitUntil(60_000) { compose.onAllNodesWithTag("login-error").fetchSemanticsNodes().isNotEmpty() }
                val details = compose.onNodeWithTag("login-error-details").fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { text -> text.text }
                assertTrue("Expected a server response with HTTP status: $details", "HTTP：" in details)
                val rateLimited = "RATE_LIMIT" in details
                assertTrue("Expected a classified rejection: $details", rateLimited || "CREDENTIALS" in details)
                if (rateLimited) {
                    compose.onNodeWithTag("login-error").assertTextContains("请求过多", substring = true)
                    compose.onNodeWithTag("login-submit").assertIsNotEnabled()
                }
                assertNull(container.repo.sessions.readToken())
                capture(app, "login-live-response")
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("live_endpoint", if (rateLimited) "reachable_but_rate_limited_not_a_credential_check" else "reachable_explicit_credentials_rejected")
                    putString("safe_details", details)
                    putString("tls", "default_certificate_and_hostname_validation")
                    putString("real_account_tested", "false")
                    putString("connection_route", container.network.route.value.name)
                })
            }
        } finally { container.db.close() }
    }
}
