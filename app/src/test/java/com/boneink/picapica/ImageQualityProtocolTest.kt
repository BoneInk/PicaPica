package com.boneink.picapica

import com.boneink.picapica.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ImageQualityProtocolTest {
    @Test fun changingQualityAffectsNextRequestWithoutReplacingTheClient() = runTest {
        MockWebServer().use { server ->
            var quality = "original"
            val api = PicaClient(SourceConfig(server.url("/")), { "token" }, imageQuality = { quality })
            for (value in listOf("original", "low", "medium", "high")) {
                quality = value
                server.enqueue(MockResponse().setBody("""{"code":200,"data":{"pages":{"docs":[{"_id":"p","media":{"fileServer":"${server.url("/")}","path":"page.png"}}],"page":1,"pages":1}}}"""))
                assertEquals(1, api.pages("comic1", 1).size)
                val request = server.takeRequest()
                assertEquals(value, request.getHeader("image-quality"));assertEquals("token", request.getHeader("authorization"))
                assertEquals(PicaProtocol.signature(request.path!!, request.getHeader("time")!!, request.getHeader("nonce")!!, "GET"), request.getHeader("signature"))
            }
        }
    }
}
