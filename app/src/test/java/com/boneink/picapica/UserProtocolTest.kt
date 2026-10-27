package com.boneink.picapica

import com.boneink.picapica.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class UserProtocolTest {
    private fun response(data: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"code":200,"data":$data}""")
    @Test fun profileMapsPublicFieldsAndMediaWithoutKeepingEmailOrBirthday() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response("""{"user":{"_id":"user1","name":"阅读同伴","email":"private@example.invalid","birthday":"2000-01-01","avatar":{"fileServer":"https://storage1.picacomic.com","path":"tobs/avatar.png"},"slogan":"继续阅读","title":"故事收藏家","level":"12","exp":"12000","gender":"bot","created_at":"2020-01-01T00:00:00Z","verified":true}}"""))
            val user = PicaClient(SourceConfig(server.url("/")), { "fixture" }).profile()
            assertEquals("https://storage1.picacomic.com/static/tobs/avatar.png", user.avatarUrl)
            assertEquals(12, user.level); assertEquals(12000L, user.exp); assertEquals("继续阅读", user.slogan); assertTrue(user.verified)
            assertFalse(user.toString().contains("private@example.invalid")); assertFalse(user.toString().contains("2000-01-01"))
            assertEquals("/users/profile", server.takeRequest().path)
        }
    }
    @Test fun commentsAndRepliesRetainPublicAuthorDetailsAndAvatar() = runTest {
        MockWebServer().use { server ->
            val api = PicaClient(SourceConfig(server.url("/")), { "fixture" })
            for (reply in listOf(false,true)) {
                server.enqueue(response("""{"comments":{"docs":[{"_id":"comment","_user":{"_id":"user1","name":"读者","avatar":{"fileServer":"https://storage-b.picacomic.com","path":"avatar.png"},"level":5,"slogan":"签名"},"content":"你好"}],"page":1,"pages":1}}"""))
                val row = (if (reply) api.replies("parent") else api.comments("book")).items.single()
                assertEquals("user1", row.user!!.id); assertEquals("签名", row.user.slogan)
                assertEquals("https://storage-b.picacomic.com/static/avatar.png", row.user.avatarUrl)
                assertEquals(5, row.user.level)
            }
        }
    }
    @Test fun missingOrInvalidAvatarDoesNotBreakCommentsOrInventProfileStats() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response("""{"comments":{"docs":[{"_id":"missing","_user":{"name":"读者","avatar":{"fileServer":"http://untrusted.example","path":"avatar.png"}}},{"_id":"deleted","_user":null}],"page":1,"pages":1}}"""))
            val rows=PicaClient(SourceConfig(server.url("/")), { "fixture" }).comments("book").items
            assertEquals("", rows[0].user!!.avatarUrl); assertNull(rows[0].user!!.level); assertNull(rows[0].user!!.exp)
            assertNull(rows[1].user); assertEquals("已注销读者", rows[1].author)
        }
    }
    @Test fun myCommentsPreservePaginationComicLinkAndReplyParentWithSignedGet() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response("""{"comments":{"docs":[{"_id":"mine","content":"读后感","created_at":"2026-10-27","likesCount":2,"commentsCount":3,"_comic":{"_id":"book","title":"雨后"},"_parent":{"_id":"parent"}}],"page":"2","pages":"4","total":34}}"""))
            val result=PicaClient(SourceConfig(server.url("/")), { "fixture" }).myComments(2)
            assertEquals(2,result.page);assertEquals(4,result.pages);assertEquals(34,result.total)
            val row=result.items.single();assertEquals("book",row.comicId);assertEquals("parent",row.parentId);assertEquals("雨后",row.comicTitle)
            val req=server.takeRequest();assertEquals("GET",req.method);assertEquals("/users/my-comments?page=2",req.path)
            assertEquals("fixture",req.getHeader("authorization"))
            assertEquals(PicaProtocol.signature(req.path!!,req.getHeader("time")!!,req.getHeader("nonce")!!,"GET"),req.getHeader("signature"))
        }
    }
    @Test fun hiddenCommentsAndUnavailableTargetsHaveSafeFallbacks() = runTest {
        MockWebServer().use { server ->
            server.enqueue(response("""{"comments":{"docs":[{"_id":"hidden","hide":true,"content":"hidden text","_comic":null},{"_id":"unsafe","_comic":{"_id":"../other","title":"Unavailable"},"_parent":null}],"page":1,"pages":1,"total":2}}"""))
            val rows=PicaClient(SourceConfig(server.url("/")), { "fixture" }).myComments().items
            assertEquals("这条评论已隐藏",rows[0].content);assertTrue(rows[0].hidden);assertNull(rows[0].comicId)
            assertNull(rows[1].comicId);assertNull(rows[1].parentId)
        }
    }
}
