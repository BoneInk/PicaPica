package com.boneink.picapica

import com.boneink.picapica.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearchFiltersProtocolTest {
    private fun response(page: Int = 1) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"code":200,"data":{"comics":{"docs":[],"page":$page,"pages":3}}}""")
    @Test fun combinedCategoriesSortAndPageAreSentAndSignedTogether() = runTest {
        MockWebServer().use { server ->
            val api = PicaClient(SourceConfig(server.url("/")), { "fixture-token" })
            SearchSort.entries.forEach { sort ->
                server.enqueue(response(2))
                assertEquals(2, api.search("雨后 & letters", 2, sort, setOf("日常", "全年龄")).page)
                val req = server.takeRequest();val body = JSONObject(req.body.readUtf8())
                assertEquals("雨后 & letters", body.getString("keyword"));assertEquals(sort.code, body.getString("sort"))
                assertEquals(setOf("日常", "全年龄"), (0 until body.getJSONArray("categories").length()).map { body.getJSONArray("categories").getString(it) }.toSet())
                assertEquals("/comics/advanced-search?page=2", req.path)
                assertEquals(PicaProtocol.signature(req.path!!, req.getHeader("time")!!, req.getHeader("nonce")!!, "POST"), req.getHeader("signature"))
            }
        }
    }
    @Test fun allCategoriesOmitsTheCategoryRestriction() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response());PicaClient(SourceConfig(server.url("/")), { "token" }).search("雨后", sort = SearchSort.OLDEST)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(body.has("categories"));assertEquals("da", body.getString("sort"))
        }
    }
    @Test fun categoryOnlySearchKeepsAnEmptyKeyword() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response());PicaClient(SourceConfig(server.url("/")), { "token" }).search("", sort = SearchSort.VIEWED, categories = setOf("日常"))
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("", body.getString("keyword"));assertEquals("vd", body.getString("sort"));assertEquals("日常", body.getJSONArray("categories").getString(0))
        }
    }
    @Test fun clearingFiltersCannotSendAnEmptyCategoryName() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response());PicaClient(SourceConfig(server.url("/")), { "token" }).search("", categories = setOf("", " "))
            assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("categories"))
        }
    }
}
