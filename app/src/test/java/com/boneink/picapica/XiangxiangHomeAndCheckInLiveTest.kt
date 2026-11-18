package com.boneink.picapica

import com.boneink.picapica.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** Opt-in account session bridge. Does not create accounts or send verification emails. */
class XiangxiangHomeAndCheckInLiveTest {
    @Test fun websiteHomepageSectionsPaginationCheckInAndKnownSiteSelection() = runBlocking {
        val bridge = System.getenv("PICAPICA_LIVE_AUTH_URL"); assumeTrue(bridge != null)
        val session = URL(bridge).openConnection(Proxy.NO_PROXY).getInputStream().bufferedReader().use { it.readText() }
        val base = JSONObject(session).getString("baseUrl").toHttpUrl()
        val proxy = java.net.URI(System.getenv("PICAPICA_LIVE_PROXY") ?: "http://127.0.0.1:7890")
        val http = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port)))
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).callTimeout(45, java.util.concurrent.TimeUnit.SECONDS).build()
        val paths = mutableListOf<String>()
        val api = XiangxiangClient(SourceConfig(base, SourceKind.XIANGXIANG), { session }, http.newBuilder().addInterceptor { chain ->
            synchronized(paths) { paths += chain.request().method + " " + chain.request().url.encodedPath }
            chain.proceed(chain.request())
        }.build())
        val feed = api.homepage()
        assertEquals(listOf("newest", "siangsiang", "recommend", "topestmh", "cnxh"), feed.sections.map { it.key })
        assertTrue(feed.sections.all { it.comics.isNotEmpty() }); assertTrue(feed.articles.isNotEmpty())
        val counts = linkedMapOf<String, Int>()
        feed.sections.forEach { section ->
            val page = api.homeSection(section.key); assertTrue(page.items.isNotEmpty()); counts[section.key] = page.items.size
        }
        val first = api.homeSection("newest", 1); val second = api.homeSection("newest", 2)
        assertTrue(first.items.map { it.id }.toSet() != second.items.map { it.id }.toSet())
        val status = api.checkInStatus()
        val postsBefore = paths.count { it.startsWith("POST /home/Api/signupNew") }
        val confirmed = api.checkIn(); assertTrue(confirmed.signed)
        if (status.signed) assertEquals(postsBefore, paths.count { it.startsWith("POST /home/Api/signupNew") })
        val origin = api.currentOrigin(); var selected = 0
        XiangxiangSite.entries.forEach { site -> api.selectSite(site); assertEquals(origin, api.currentOrigin()); selected++ }
        assertTrue(api.profile().stats.isNotEmpty())
        http.newCall(Request.Builder().url(base.resolve("home/auth/login/type/register.html")!!).build()).execute().use { response ->
            val doc = org.jsoup.Jsoup.parse(response.body!!.string())
            assertNotNull(doc.selectFirst("input[name=reset_token]")); assertNotNull(doc.selectFirst("input[name=email]")); assertNotNull(doc.selectFirst("input[name=vfycode]"))
        }
        println(JSONObject().put("homepageSections", feed.sections.size).put("nativeMorePageCounts", JSONObject(counts))
            .put("dailyPaginationDistinct", true).put("checkedIn", confirmed.signed).put("alreadySignedBefore", status.signed)
            .put("siteSelections", selected).put("sessionPreserved", true).put("registrationFieldsVerified", true).toString())
    }
}
