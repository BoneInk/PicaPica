package com.boneink.picapica

import com.boneink.picapica.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PicaProtocolTest {
    @Test fun signatureMatchesIndependentPythonHmacVector() {
        assertEquals("bc18acd2fabded8c4448462136bb3fe01d55997fd4a1294564e50c143fbc9239", PicaProtocol.signature(
            "comics/advanced-search?page=2", "1700000000", "0123456789abcdef0123456789abcdef", "POST"))
    }

    @Test fun loginOmitsExistingTokenAndRejectsBadCredentials() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":400,"message":"bad credentials"}"""))
            val api = PicaClient(SourceConfig(server.url("/")), { "previous-account-token" })
            try { api.signIn("reader@example.invalid", "wrong"); fail("Expected login rejection") }
            catch (e: PicaException) { assertEquals(PicaException.Kind.CREDENTIALS, e.kind) }
            val request = server.takeRequest()
            assertEquals("/auth/sign-in", request.path)
            assertNull(request.getHeader("authorization"))
            assertEquals("wrong", JSONObject(request.body.readUtf8()).getString("password"))
        }
    }

    @Test fun searchSignsExactQueryAndSendsUtf8Json() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":200,"data":{"comics":{"docs":[],"page":2,"pages":3}}}"""))
            val api = PicaClient(SourceConfig(server.url("/")), { "test-token" })
            assertEquals(2, api.search("雨后 & tomorrow", 2).page)
            val req = server.takeRequest()
            assertEquals("/comics/advanced-search?page=2", req.path)
            assertEquals("test-token", req.getHeader("authorization"))
            assertEquals("雨后 & tomorrow", JSONObject(req.body.readUtf8()).getString("keyword"))
            assertEquals(PicaProtocol.signature(req.path!!, req.getHeader("time")!!, req.getHeader("nonce")!!, "POST"), req.getHeader("signature"))
        }
    }

    @Test fun chapterPaginationIsCompleteAndInReadingOrder() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":200,"data":{"eps":{"docs":[{"_id":"two","title":"二","order":2}],"page":1,"pages":2}}}"""))
            server.enqueue(MockResponse().setBody("""{"code":200,"data":{"eps":{"docs":[{"_id":"one","title":"一","order":1}],"page":2,"pages":2}}}"""))
            val chapters = PicaClient(SourceConfig(server.url("/")), { "token" }).chapters("comic1")
            assertEquals(listOf(1, 2), chapters.map { it.order })
            assertEquals("/comics/comic1/eps?page=1", server.takeRequest().path)
            assertEquals("/comics/comic1/eps?page=2", server.takeRequest().path)
        }
    }

    @Test fun pagesResolveStaticMediaAndKeepAllPagination() = runTest {
        MockWebServer().use { server ->
            val media = """{"fileServer":"${server.url("/")}","path":"folder/page 1.png"}"""
            server.enqueue(MockResponse().setBody("""{"code":200,"data":{"pages":{"docs":[{"_id":"p1","media":$media}],"pages":2}}}"""))
            server.enqueue(MockResponse().setBody("""{"code":200,"data":{"pages":{"docs":[{"_id":"p2","media":$media}],"pages":2}}}"""))
            val pages = PicaClient(SourceConfig(server.url("/")), { "token" }).pages("comic1", 1)
            assertEquals(listOf(0, 1), pages.map { it.index })
            assertTrue(pages[0].url.endsWith("/static/folder/page%201.png"))
        }
    }

    @Test fun expiredSessionAndHtmlFailuresAreActionable() = runTest {
        MockWebServer().use { server ->
            val api = PicaClient(SourceConfig(server.url("/")), { "token" })
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            try { api.comics(); fail() } catch (e: PicaException) { assertEquals(PicaException.Kind.AUTH, e.kind) }
            server.enqueue(MockResponse().setResponseCode(502).setBody("<html>gateway unavailable</html>"))
            try { api.comics(); fail() } catch (e: PicaException) { assertEquals(PicaException.Kind.SERVICE, e.kind); assertTrue(e.message.contains("502")) }
        }
    }

    @Test fun rejectsInsecureExternalSourcesAndUnsafeIdentifiers() = runTest {
        try { SourceConfig("http://example.com/".toHttpUrl()); fail() } catch (_: IllegalArgumentException) { }
        MockWebServer().use { server ->
            val api = PicaClient(SourceConfig(server.url("/")), { "token" })
            try { api.comic("../users"); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(0, server.requestCount)
        }
    }
}
