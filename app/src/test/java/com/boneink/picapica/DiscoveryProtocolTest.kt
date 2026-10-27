package com.boneink.picapica

import com.boneink.picapica.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DiscoveryProtocolTest {
    private fun response(data: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"code":200,"data":$data}""")
    @Test fun categoriesHandleMissingIdsAndExcludeExternalOrInactiveItems() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response("""{"categories":[{"title":"全年龄"},{"_id":"daily","title":"日常"},{"title":"external","isWeb":true},{"title":"disabled","active":false}]}"""))
            val items = PicaClient(SourceConfig(server.url("/")), { "test-token" }).categories()
            assertEquals(listOf("全年龄", "日常"), items.map { it.title })
            assertTrue(items.first().id.isNotBlank())
        }
    }
    @Test fun categoryTitleSortAndPaginationAreEncodedAndSigned() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response("""{"comics":{"docs":[],"page":"2","pages":"3"}}"""))
            val values = PicaClient(SourceConfig(server.url("/")), { "test-token" }).category("日常 & 短篇", ComicSort.POPULAR, 2)
            assertEquals(2, values.page); assertEquals(3, values.pages)
            val req = server.takeRequest()
            assertEquals("日常 & 短篇", req.requestUrl!!.queryParameter("c")); assertEquals("ld", req.requestUrl!!.queryParameter("s"))
            assertEquals("2", req.requestUrl!!.queryParameter("page"))
            assertEquals(PicaProtocol.signature(req.path!!, req.getHeader("time")!!, req.getHeader("nonce")!!, "GET"), req.getHeader("signature"))
        }
    }
    @Test fun rankingPeriodsUseDocumentedViewsChannel() = runTest {
        MockWebServer().use { server ->
            val api = PicaClient(SourceConfig(server.url("/")), { "test-token" })
            RankingPeriod.entries.forEach { period ->
                server.enqueue(response("""{"comics":[{"_id":"book","title":"A story"}]}"""))
                assertEquals("book", api.ranking(period).single().id)
                val req = server.takeRequest(); assertEquals("/comics/leaderboard", req.requestUrl!!.encodedPath)
                assertEquals(period.code, req.requestUrl!!.queryParameter("tt")); assertEquals("VC", req.requestUrl!!.queryParameter("ct"))
            }
        }
    }
    @Test fun favouriteStateComesFromDetailEnvelopeAndToggleAction() = runTest {
        MockWebServer().use { server ->
            val api = PicaClient(SourceConfig(server.url("/")), { "test-token" })
            server.enqueue(response("""{"comic":{"_id":"book","title":"A story","isFavourite":false},"isFavourite":true}"""))
            assertTrue(api.comic("book").isFavourite); server.takeRequest()
            for ((action, expected) in listOf("favourite" to true, "un_favourite" to false)) {
                server.enqueue(response("""{"action":"$action"}""")); assertEquals(expected, api.toggleFavourite("book"))
                val req = server.takeRequest(); assertEquals("POST", req.method); assertEquals("{}", req.body.readUtf8()); assertEquals("test-token", req.getHeader("authorization"))
            }
        }
    }
    @Test fun ambiguousToggleRefreshesDetailInsteadOfTogglingAgain() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response("{}")); server.enqueue(response("""{"comic":{"_id":"book","title":"A story"},"isFavourite":true}"""))
            assertTrue(PicaClient(SourceConfig(server.url("/")), { "test-token" }).toggleFavourite("book"))
            assertEquals("POST", server.takeRequest().method); assertEquals("GET", server.takeRequest().method); assertEquals(2, server.requestCount)
        }
    }
    @Test fun favouritesRetainServerPaginationAndSort() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response("""{"comics":{"docs":[],"page":2,"pages":4}}"""))
            assertEquals(4, PicaClient(SourceConfig(server.url("/")), { "test-token" }).favourites(2, ComicSort.NEWEST).pages)
            val req = server.takeRequest(); assertEquals("/users/favourite", req.requestUrl!!.encodedPath)
            assertEquals("dd", req.requestUrl!!.queryParameter("s")); assertEquals("2", req.requestUrl!!.queryParameter("page"))
        }
    }
}
