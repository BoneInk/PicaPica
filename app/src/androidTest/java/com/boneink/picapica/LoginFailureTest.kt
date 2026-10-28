package com.boneink.picapica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boneink.picapica.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginFailureTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun http400RateLimitShowsActualReasonAndBlocksRetryAcrossReopen() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setHeader("Retry-After", "90")
                .setBody("""{"code":400,"error":"1023","message":"too many requests"}"""))
            val storage = "login-rate-${System.currentTimeMillis()}"
            val container = AppContainer(app, SourceConfig(server.url("/")), storage)
            app.installTestContainer(container)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithTag("empty-action").performClick()
                compose.onNodeWithTag("login-account").performTextInput("reader@example.invalid")
                compose.onNodeWithTag("login-password").performTextInput("fake-password")
                compose.onNodeWithTag("login-submit").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("login-error").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("login-error").assertTextContains("请求过多", substring = true)
                compose.onNodeWithTag("login-error-details").assertDoesNotExist()
                compose.onNodeWithTag("login-error-toggle").performScrollTo().performClick()
                compose.onNodeWithTag("login-error-details").assertTextContains("1023", substring = true)
                compose.onNodeWithTag("login-submit").assertIsNotEnabled()
                compose.onNodeWithTag("login-password").performImeAction()
                assertEquals(1, server.requestCount)
                assertNull(container.repo.sessions.readToken())
                runBlocking {
                    try { container.repo.api.signIn("reader@example.invalid", "fake-password"); fail() }
                    catch (e: PicaException) { assertEquals(PicaException.Kind.RATE_LIMIT, e.kind) }
                }
                assertEquals(1, server.requestCount)
                val reopened = AppContainer(app, SourceConfig(server.url("/")), storage)
                assertTrue(reopened.repo.api.backoff.remainingSeconds() > 0)
                reopened.db.close()
                capture(app, "login-rate-limit-fixed")
            }
            container.db.close()
        }
    }

    @Test fun signatureFailureIsNotReportedAsBadPassword() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":400,"error":"1009","message":"invalid signature"}"""))
            val container = AppContainer(app, SourceConfig(server.url("/")), "login-signature-${System.currentTimeMillis()}")
            app.installTestContainer(container)
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithTag("empty-action").performClick()
                compose.onNodeWithTag("login-account").performTextInput("reader@example.invalid")
                compose.onNodeWithTag("login-password").performTextInput("fake-password")
                compose.onNodeWithTag("login-submit").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("login-error").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("login-error").assertTextContains("校验失败", substring = true)
                assertFalse(compose.onNodeWithTag("login-error").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString().contains("密码"))
                assertNull(container.repo.sessions.readToken())
                capture(app, "login-protocol-error-fixed")
            }
            container.db.close()
        }
    }
}
