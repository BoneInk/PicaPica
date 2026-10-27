package com.boneink.picapica

import com.boneink.picapica.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommentThreadProtocolTest {
    private fun response(data: String) = MockResponse().setHeader("Content-Type","application/json").setBody("""{"code":200,"data":$data}""")
    @Test fun replyCountsUseNewFieldAndKeepOldFieldFallbackIncludingZero() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response("""{"comments":{"docs":[{"_id":"new","totalComments":"3","commentsCount":0},{"_id":"zero","totalComments":0,"commentsCount":5},{"_id":"old","commentsCount":2},{"_id":"nullable","totalComments":null,"commentsCount":4}],"page":1,"pages":1}}"""))
            val rows=PicaClient(SourceConfig(server.url("/")),{"fixture"}).replies("root").items
            assertEquals(listOf(3,0,2,4),rows.map { it.replies })
        }
    }
    @Test fun childRepliesKeepParentIdsHiddenStateAndUseTheirOwnEndpoint() = runTest {
        MockWebServer().use { server ->
            val api=PicaClient(SourceConfig(server.url("/")),{"fixture"})
            server.enqueue(response("""{"comments":{"docs":[{"_id":"child","_parent":"root","totalComments":1,"hide":true}],"page":1,"pages":1}}"""))
            val child=api.replies("root").items.single()
            assertEquals("root",child.parentId);assertTrue(child.hidden)
            assertEquals("/comments/root/childrens?page=1",server.takeRequest().path)
            server.enqueue(response("{}"));api.postReply("child","回复楼中楼")
            val post=server.takeRequest();assertEquals("/comments/child",post.path);assertEquals("POST",post.method)
            assertEquals("回复楼中楼",JSONObject(post.body.readUtf8()).getString("content"))
            assertEquals(PicaProtocol.signature(post.path!!,post.getHeader("time")!!,post.getHeader("nonce")!!,"POST"),post.getHeader("signature"))
        }
    }
    @Test fun historyRetainsConfirmedLikeStateParentAndCurrentReplyCount() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response("""{"comments":{"docs":[{"_id":"mine","_comic":{"_id":"book","title":"故事"},"_parent":{"_id":"root"},"isLiked":true,"totalComments":6,"commentsCount":0}],"page":1,"pages":1,"total":1}}"""))
            val row=PicaClient(SourceConfig(server.url("/")),{"fixture"}).myComments().items.single()
            assertTrue(row.isLiked);assertEquals(6,row.replies);assertEquals("root",row.parentId)
        }
    }
}
