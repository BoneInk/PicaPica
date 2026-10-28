package com.boneink.picapica

import com.boneink.picapica.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountProtocolTest {
    private fun form(questions: List<String> = listOf("问题一", "问题二", "问题三")) = Registration(" reader123 ", "secret-password", "读者", "2000-01-02", "bot", questions, listOf("答案一", "答案二", "答案三"))
    private fun ok() = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"code":200,"data":{}}""")
    @Test fun registrationUsesUnsignedAccountSessionAndAllRecoveryFields() = runTest {
        MockWebServer().use { server ->
            server.enqueue(ok())
            PicaClient(SourceConfig(server.url("/")), { "existing-token" }).register(form())
            val request = server.takeRequest()
            assertEquals("/auth/register", request.path); assertEquals("POST", request.method)
            assertNull(request.getHeader("authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("reader123", body.getString("email")); assertEquals("2000/01/02", body.getString("birthday"))
            assertEquals("secret-password", body.getString("password")); assertEquals("答案三", body.getString("answer3"))
            assertEquals(11, body.length())
            assertEquals(PicaProtocol.signature(request.path!!, request.getHeader("time")!!, request.getHeader("nonce")!!, "POST"), request.getHeader("signature"))
            assertFalse(form().toString().contains("secret-password")); assertFalse(form().toString().contains("答案"))
        }
    }
    @Test fun mutationsUseAuthenticatedPutAndSignTheActualMethod() = runTest {
        MockWebServer().use { server ->
            val api = PicaClient(SourceConfig(server.url("/")), { "fixture" })
            server.enqueue(ok()); api.updateSlogan(" 新签名 ")
            server.enqueue(ok()); api.updateAvatar(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
            for (path in listOf("/users/profile", "/users/avatar")) {
                val request = server.takeRequest()
                assertEquals("PUT", request.method); assertEquals(path, request.path); assertEquals("fixture", request.getHeader("authorization"))
                assertEquals(PicaProtocol.signature(path, request.getHeader("time")!!, request.getHeader("nonce")!!, "PUT"), request.getHeader("signature"))
                val body = JSONObject(request.body.readUtf8())
                if (path.endsWith("profile")) assertEquals("新签名", body.getString("slogan"))
                else assertTrue(body.getString("avatar").startsWith("data:image/jpeg;base64,/9j/2Q=="))
            }
        }
    }
    @Test fun invalidInputsNeverReachTheService() = runTest {
        MockWebServer().use { server ->
            val api = PicaClient(SourceConfig(server.url("/")), { "fixture" })
            for (action in listOf<suspend () -> Unit>(
                { api.register(form(listOf("重复", "重复", "问题三"))) },
                { api.register(Registration("reader", "short", "读者", "2000-01-01", "bot", emptyList(), emptyList())) },
                { api.register(Registration("reader", "secret-password", "读者", "2000-02-30", "bot", emptyList(), emptyList())) },
                { api.updateSlogan("字".repeat(101)) }, { api.updateAvatar(byteArrayOf(1, 2)) },
            )) {
                try { action(); fail("invalid data was accepted") } catch (_: IllegalArgumentException) { }
            }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun duplicateRegistrationAndRateLimitHaveActionableErrorsWithoutInvalidatingLogin() {
        val duplicate = ApiFailure.parse(400, JSONObject("""{"message":"email already exists","error":"1001"}"""), false, registering = true)
        assertEquals(PicaException.Kind.DATA, duplicate.kind); assertTrue(duplicate.message.contains("已被注册"))
        val rate = ApiFailure.parse(429, JSONObject("{}"), false, "30", registering = true)
        assertEquals(PicaException.Kind.RATE_LIMIT, rate.kind); assertEquals(30L, rate.retryAfterSeconds)
    }
}
