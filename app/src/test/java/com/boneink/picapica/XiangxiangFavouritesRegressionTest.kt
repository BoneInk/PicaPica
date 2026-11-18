package com.boneink.picapica

import com.boneink.picapica.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XiangxiangFavouritesRegressionTest {
    private fun response(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")
    private fun detail(selected: Boolean): String = "<script>let data = JSON.parse(" +
        JSONObject.quote(JSONArray(listOf("state", if (selected) "200" else "1", "")).toString()) +
        ");</script><div class='stui-content__detail'><h1>收藏测试故事</h1></div>"
    @Test fun actualOkEnvelopeWithFalseSuccKeepsPopulatedAndEmptyFavouritePages() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(SourceConfig(server.url("/"), SourceKind.XIANGXIANG), { null })
            server.enqueue(response("""{"code":0,"msg":"ok","succ":false,"result":{"data":[{"id":9000,"bid":12,"title":"收藏测试故事"}],"history":{},"can_del":true,"lastPage":false}}"""))
            val populated = api.favourites(); assertEquals("12", populated.items.single().id); assertTrue(populated.items.single().isFavourite); assertEquals(2, populated.pages)
            server.enqueue(response("""{"code":0,"msg":"ok","succ":false,"result":{"data":[],"lastPage":true}}"""))
            val empty = api.favourites(2); assertTrue(empty.items.isEmpty()); assertEquals(2, empty.pages)
        }
    }
    @Test fun malformedPayloadAndPermissionErrorsRemainErrorsInsteadOfEmptySuccess() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(SourceConfig(server.url("/"), SourceKind.XIANGXIANG), { null })
            server.enqueue(response("""{"code":0,"msg":"ok","succ":false,"result":{"list":[],"lastPage":true}}"""))
            try { api.favourites(); fail() } catch (e: PicaException) { assertEquals(PicaException.Kind.DATA, e.kind); assertNotEquals("ok", e.message) }
            server.enqueue(response("""{"code":403,"msg":"无权读取收藏","succ":false,"result":{"data":[],"lastPage":true}}"""))
            try { api.favourites(); fail() } catch (e: PicaException) { assertEquals(PicaException.Kind.SERVICE, e.kind) }
            server.enqueue(response("""{"code":0,"msg":"请先登录","succ":false,"result":{"data":[],"lastPage":true}}"""))
            try { api.favourites(); fail() } catch (e: PicaException) { assertEquals(PicaException.Kind.AUTH, e.kind) }
        }
    }
    @Test fun addAndRemoveConfirmTheActualDetailStateEvenWhenSuccIsFalse() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(SourceConfig(server.url("/"), SourceKind.XIANGXIANG), { null })
            for (selected in listOf(true, false)) {
                server.enqueue(response(detail(!selected))); server.enqueue(response("""{"code":0,"msg":"ok","succ":false}""")); server.enqueue(response(detail(selected)))
                assertEquals(selected, api.toggleFavourite("12")); server.takeRequest()
                val mutation = server.takeRequest(); assertEquals(if (selected) "add" else "del", mutation.requestUrl!!.queryParameter("action"))
                assertEquals("GET", server.takeRequest().method)
            }
            assertEquals(6, server.requestCount)
        }
    }
    @Test fun successFlagWithoutChangedStateDoesNotReportAFakeFavouriteChange() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(SourceConfig(server.url("/"), SourceKind.XIANGXIANG), { null })
            server.enqueue(response(detail(false))); server.enqueue(response("""{"succ":true}""")); server.enqueue(response(detail(false)))
            try { api.toggleFavourite("12"); fail() } catch (e: PicaException) { assertEquals(PicaException.Kind.DATA, e.kind) }
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun lostMutationResponseIsResolvedByReadWithoutRepeatingTheMutation() = runTest {
        MockWebServer().use { server ->
            val api = XiangxiangClient(SourceConfig(server.url("/"), SourceKind.XIANGXIANG), { null })
            server.enqueue(response(detail(false)))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(response(detail(true)))
            assertTrue(api.toggleFavourite("12"))
            assertEquals(3, server.requestCount); server.takeRequest()
            assertEquals("/home/api/dosubscribe", server.takeRequest().requestUrl!!.encodedPath)
            assertTrue(server.takeRequest().requestUrl!!.encodedPath.startsWith("/home/book/index/"))
        }
    }
}
