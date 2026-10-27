package com.boneink.picapica

import com.boneink.picapica.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommentsProtocolTest {
    private fun response(data: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"code":200,"data":$data}""")
    @Test fun parsesPinnedCommentsDeletedUsersAndReplyPagination() = runTest {
        MockWebServer().use { server ->
            val api = PicaClient(SourceConfig(server.url("/")), { "fixture" })
            server.enqueue(response("""{"comments":{"docs":[{"_id":"a","content":"你好","_user":{"name":"读者"},"likesCount":4,"commentsCount":2,"isLiked":true},{"_id":"gone","hide":true,"content":"hidden"}],"page":"2","pages":"3","total":30},"topComments":[{"_id":"pinned","content":"置顶"}]}"""))
            val result = api.comments("book", 2)
            assertEquals(2, result.page); assertEquals(3, result.pages); assertEquals(30, result.total)
            assertEquals("读者", result.items[0].author); assertTrue(result.items[0].isLiked)
            assertEquals(4, result.items[0].likes); assertEquals("这条评论已隐藏", result.items[1].content)
            assertEquals("已注销读者", result.items[1].author); assertTrue(result.pinned.single().pinned)
            assertEquals("/comics/book/comments?page=2", server.takeRequest().path)
            server.enqueue(response("""{"comments":{"docs":[],"page":3,"pages":3,"total":22}}"""))
            assertEquals(3, api.replies("parent", 3).page)
            assertEquals("/comments/parent/childrens?page=3", server.takeRequest().path)
        }
    }
    @Test fun postsTrimmedUnicodeContentWithSignatureForCommentAndReply() = runTest {
        MockWebServer().use { server ->
            val api = PicaClient(SourceConfig(server.url("/")), { "fixture" })
            for (reply in listOf(false, true)) {
                server.enqueue(response("{}"))
                if (reply) api.postReply("parent", "  阅读 & 感受\n第二行  ") else api.postComment("book", "  阅读 & 感受\n第二行  ")
                val req = server.takeRequest()
                assertEquals(if (reply) "/comments/parent" else "/comics/book/comments", req.path)
                assertEquals("阅读 & 感受\n第二行", JSONObject(req.body.readUtf8()).getString("content"))
                assertEquals("fixture", req.getHeader("authorization"))
                assertEquals(PicaProtocol.signature(req.path!!, req.getHeader("time")!!, req.getHeader("nonce")!!, "POST"), req.getHeader("signature"))
            }
        }
    }
    @Test fun rejectsInvalidInputBeforeAnyNetworkRequest() = runTest {
        MockWebServer().use { server ->
            val api = PicaClient(SourceConfig(server.url("/")), { "fixture" })
            for (text in listOf("  ", "a".repeat(1001))) {
                try { api.postComment("book", text); fail() } catch (_: IllegalArgumentException) { }
            }
            try { api.replies("../other"); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun likeActionsAreConfirmedAndAmbiguousResponsesNeverRetried() = runTest {
        MockWebServer().use { server ->
            val api = PicaClient(SourceConfig(server.url("/")), { "fixture" })
            server.enqueue(response("""{"action":"like"}""")); assertTrue(api.toggleCommentLike("comment"))
            server.enqueue(response("""{"action":"unlike"}""")); assertFalse(api.toggleCommentLike("comment"))
            server.enqueue(response("{}"))
            try { api.toggleCommentLike("comment"); fail() } catch (e: PicaException) { assertEquals(PicaException.Kind.DATA, e.kind) }
            assertEquals(3, server.requestCount)
        }
    }
}
