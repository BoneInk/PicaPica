package com.boneink.picapica

import com.boneink.picapica.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApiFailureTest {
    @Test fun actual1023ResponseIsRateLimitNotWrongPassword() {
        val failure = ApiFailure.parse(400, JSONObject("""{"code":400,"error":"1023","message":"too many requests"}"""), true)
        assertEquals(PicaException.Kind.RATE_LIMIT, failure.kind)
        assertEquals("1023", failure.errorCode)
        assertEquals(60L, failure.retryAfterSeconds)
        assertFalse(failure.message.contains("密码"))
        assertTrue(failure.safeDetails().contains("HTTP：400"))
    }
    @Test fun rateLimitTakesPrecedenceOverHttpUnauthorized() {
        assertEquals(PicaException.Kind.RATE_LIMIT, ApiFailure.parse(401, JSONObject("""{"code":401,"error":"1023"}"""), true).kind)
    }
    @Test fun honorsRetryAfterSecondsAndHttpDate() {
        assertEquals(125L, ApiFailure.parse(429, JSONObject(), true, "125").retryAfterSeconds)
        assertEquals(121L, ApiFailure.parse(429, JSONObject(), true, "Thu, 01 Jan 1970 00:02:00 GMT", 0).retryAfterSeconds)
    }
    @Test fun onlyExplicitCredentialErrorBlamesCredentials() {
        assertEquals(PicaException.Kind.CREDENTIALS, ApiFailure.parse(400, JSONObject("""{"code":400,"message":"invalid email or password"}"""), true).kind)
        val unknown = ApiFailure.parse(400, JSONObject("""{"code":400,"error":"1099","message":"unknown error"}"""), true)
        assertEquals(PicaException.Kind.SERVICE, unknown.kind)
        assertFalse(unknown.message.contains("密码"))
    }
    @Test fun signatureRejectionDoesNotInvalidateExistingSession() {
        assertEquals(PicaException.Kind.PROTOCOL, ApiFailure.parse(401, JSONObject("""{"code":401,"message":"invalid signature"}"""), true).kind)
        assertEquals(PicaException.Kind.AUTH, ApiFailure.parse(401, JSONObject(), false).kind)
    }
    @Test fun diagnosticsDoNotEchoAccountPasswordOrRawMessages() {
        val failure = ApiFailure.parse(400, JSONObject().put("code", 400).put("error", "unsafe value secret-password")
            .put("message", "user@example.invalid secret-password token-value"), true)
        assertNull(failure.errorCode)
        assertFalse((failure.message + failure.safeDetails()).contains("secret-password"))
        assertFalse(failure.safeDetails().contains("user@example.invalid"))
    }
    @Test fun cooldownPersistsAndIsNotShortenedByLaterResponse() {
        var instant = 1_000_000L
        var saved = 0L
        val first = ApiBackoff({ instant }, { saved }, { saved = it })
        first.blockFor(120)
        first.blockFor(5)
        assertEquals(120L, first.remainingSeconds())
        val reopened = ApiBackoff({ instant }, { saved }, { saved = it })
        assertEquals(120L, reopened.remainingSeconds())
        instant += 119_001
        assertEquals(1L, reopened.remainingSeconds())
        instant += 1_000
        reopened.checkAllowed()
    }
    @Test fun repeatedLoginDuringCooldownSendsNoFurtherHttpRequest() = runTest {
        MockWebServer().use { server ->
            var instant = 1_000_000L
            val gate = ApiBackoff(now = { instant })
            server.enqueue(MockResponse().setResponseCode(400).setHeader("Retry-After", "90")
                .setBody("""{"code":400,"error":"1023","message":"too many requests"}"""))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":400,"message":"invalid email or password"}"""))
            val api = PicaClient(SourceConfig(server.url("/")), { null }, backoff = gate)
            repeat(3) {
                try { api.signIn("reader@example.invalid", "fake-password"); fail() }
                catch (e: PicaException) { assertEquals(PicaException.Kind.RATE_LIMIT, e.kind) }
            }
            assertEquals(1, server.requestCount)
            instant += 91_000
            try { api.signIn("reader@example.invalid", "fake-password"); fail() }
            catch (e: PicaException) { assertEquals(PicaException.Kind.CREDENTIALS, e.kind) }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun htmlHttp429AlsoStartsCooldown() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("<html>Too many requests</html>"))
            val api = PicaClient(SourceConfig(server.url("/")), { null })
            try { api.signIn("reader@example.invalid", "fake-password"); fail() }
            catch (e: PicaException) { assertEquals(PicaException.Kind.RATE_LIMIT, e.kind) }
            assertTrue(api.backoff.remainingSeconds() > 0)
        }
    }
    @Test fun transportDetailsIdentifyLoginWithoutExposingCredentials() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":400,"message":"user@example.invalid secret-password token-value"}"""))
            val api = PicaClient(SourceConfig(server.url("/")), { null })
            try { api.signIn("user@example.invalid", "secret-password"); fail() }
            catch (e: PicaException) {
                val details = e.safeDetails()
                assertTrue(details.contains("接口：auth/sign-in"))
                assertTrue(details.contains("连接地址：127.0.0.1"))
                for (privateValue in listOf("user@example.invalid", "secret-password", "token-value")) {
                    assertFalse((details + e.message).contains(privateValue))
                }
            }
            val request = server.takeRequest()
            assertEquals("1", request.getHeader("app-channel"))
            assertEquals("application/json; charset=UTF-8", request.getHeader("Content-Type"))
        }
    }
}
