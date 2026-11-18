package com.boneink.picapica

import com.boneink.picapica.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Opt-in public, read-only integration probe. Never logs into or posts to a real account. */
class XiangxiangLiveTest {
    @Test fun currentPublicWebsiteContract() = runBlocking {
        assumeTrue(System.getenv("PICAPICA_LIVE_XIANGXIANG") == "1")
        val proxy = java.net.URI(System.getenv("PICAPICA_LIVE_PROXY") ?: System.getenv("HTTPS_PROXY") ?: "http://127.0.0.1:7890")
        val routes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        var captchaHost: String? = null
        val http = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            routes += chain.request().url.host to chain.request().url.encodedPath
            if (chain.request().url.encodedPath == "/captcha.html" && response.isSuccessful) captchaHost = chain.request().url.host
            println("${chain.request().url.host}${chain.request().url.encodedPath}: ${response.code} ${response.header("Location").orEmpty()}"); response
        }.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port)))
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
        val api = XiangxiangClient(SourceConfig("https://fulovehouse.net/".toHttpUrl(), SourceKind.XIANGXIANG), { null }, http)
        println("phase=captcha"); val captcha = api.captcha()
        assertTrue(captcha.size > 8); assertEquals(0x89.toByte(), captcha[0]); assertEquals(0x50.toByte(), captcha[1])
        println("phase=expired-profile"); try { api.profile(); fail("Logged-out profile must request login") }
        catch (e: PicaException) { assertEquals(PicaException.Kind.AUTH, e.kind) }
        assertNotNull(captchaHost)
        assertEquals(listOf(captchaHost), routes.filter { it.second == "/home/user/index" }.map { it.first }.distinct())
        println("phase=categories"); val categories = api.categories(); assertTrue(categories.size > 10)
        println("phase=home"); val home = api.comics(); assertTrue(home.items.isNotEmpty())
        println("phase=tag"); val tag = api.category("日常"); assertTrue(tag.items.isNotEmpty())
        println("phase=search"); val search = api.search("日常"); assertTrue(search.items.isNotEmpty())
        println("phase=empty"); val empty = api.search("picapica-nonexistent-178f75cfe38b"); assertTrue(empty.items.isEmpty())
        println("phase=ranking"); val ranking = api.ranking(RankingPeriod.DAY); assertTrue(ranking.isNotEmpty())
        println("phase=detail"); val detail = api.comic("20743"); assertTrue(detail.tags.isNotEmpty()); assertTrue(detail.coverUrl.startsWith("https://"))
        println("phase=chapters"); val chapters = api.chapters("20743"); assertTrue(chapters.size >= 60)
        println("phase=plainImages"); val oldImages = api.pages("20743", 1); assertTrue(oldImages.isNotEmpty()); assertNull(XiangxiangImages.slices(oldImages.first().url.toHttpUrl()))
        println("phase=newChapters"); val newChapters = api.chapters("30897"); val recentImages = api.pages("30897", newChapters.last().order)
        assertTrue(recentImages.isNotEmpty()); assertNotNull(XiangxiangImages.slices(recentImages.first().url.toHttpUrl()))
        println("phase=comments"); val comments = api.comments("20743"); assertTrue(comments.items.isNotEmpty())
        val parent = comments.items.first { it.replies > 0 }; val replies = api.replies(parent.id); assertTrue(replies.items.isNotEmpty())
        File("../artifacts/xiangxiang-live-probe.json").writeText(JSONObject().put("checkedAt", java.time.Instant.now().toString())
            .put("base", api.config.apiBase).put("captchaBytes", captcha.size).put("resolvedBase", "https://$captchaHost/").put("categories", categories.size).put("homeItems", home.items.size).put("tagItems", tag.items.size)
            .put("searchItems", search.items.size).put("rankingItems", ranking.size).put("chapters", chapters.size)
            .put("plainImages", oldImages.size).put("rearrangedImages", recentImages.size).put("comments", comments.items.size)
            .put("replies", replies.items.size).put("authenticatedActions", "not tested; no account supplied").toString(2))
    }
}
