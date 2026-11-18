package com.boneink.picapica

import com.boneink.picapica.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Proxy
import java.net.InetSocketAddress
import java.net.URL
import java.io.File
import java.util.concurrent.TimeUnit

/** Credentials stay outside Gradle. A temporary local bridge supplies an existing, in-memory session. */
class XiangxiangAccountLiveTest {
    @Test fun actualProfileCloudFavouritesAndCommentHistory() = runBlocking {
        val bridge = System.getenv("PICAPICA_LIVE_AUTH_URL")
        assumeTrue(bridge != null)
        val connection = URL(bridge).openConnection(Proxy.NO_PROXY)
        val session = connection.getInputStream().bufferedReader().use { it.readText() }
        val root = JSONObject(session)
        val base = root.getString("baseUrl").toHttpUrl()
        val cookies = root.getJSONArray("cookies").let { a -> (0 until a.length()).mapNotNull { Cookie.parse(base, a.getString(it)) } }
        val proxy = java.net.URI(System.getenv("PICAPICA_LIVE_PROXY") ?: "http://127.0.0.1:7890")
        val http = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port))).build()
        val rawRequest = Request.Builder().url(base.resolve("home/user/getComment")!!)
            .header("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")
            .header("Referer", base.toString()).post(FormBody.Builder().add("page", "0").add("loadMod", "new").build()).build()
        http.newCall(rawRequest).execute().use { response ->
            val history = JSONObject(response.body!!.string())
            val result = history.optJSONObject("result")
            val first = result?.optJSONArray("list")?.optJSONObject(0)
            println(JSONObject().put("historyHttp", response.code).put("rootKeys", JSONArray(history.keys().asSequence().toList()))
                .put("resultKeys", JSONArray(result?.keys()?.asSequence()?.toList().orEmpty()))
                .put("firstKeys", JSONArray(first?.keys()?.asSequence()?.toList().orEmpty())).toString())
        }
        http.newCall(rawRequest.newBuilder().url(base.resolve("home/user/index.html")!!).get().build()).execute().use { response ->
            val doc = org.jsoup.Jsoup.parse(response.body!!.string())
            println(JSONObject().put("avatarFormField", doc.selectFirst("form[action*=cgavatar] input[name=is_avatar]")?.attr("value"))
                .put("avatarUploadField", doc.selectFirst("form[action*=cgavatar] input[type=file]")?.attr("name")).toString())
        }
        val api = XiangxiangClient(SourceConfig(base, SourceKind.XIANGXIANG), { session }, http)
        val profile = api.profile()
        assertNotEquals("Live reader", profile.name); assertTrue(profile.id.all(Char::isDigit))
        assertTrue(profile.avatarUrl.isNotBlank()); assertNotNull(profile.exp); assertTrue(profile.stats.isNotEmpty())
        val page = api.favourites()
        assertTrue(page.items.isNotEmpty()); assertTrue(page.items.all { it.isFavourite })
        val detail = api.comic(page.items.first().id)
        assertEquals(page.items.first().id, detail.id); assertTrue(detail.isFavourite)
        val history = api.myComments()
        println(JSONObject().put("profile", true).put("avatar", true).put("profileStats", profile.stats.size)
            .put("favouritesOnFirstPage", page.items.size).put("favouriteDetailsAgree", true)
            .put("historyItems", history.items.size).put("historyPages", history.pages).toString())
        if (System.getenv("PICAPICA_LIVE_NATIVE") != "0") {
            val sdk = System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/.local/share/bone-drop-toolchain/sdk"
            val adb = "$sdk/platform-tools/adb"
            fun command(vararg args: String): String = ProcessBuilder(listOf(adb, "-s", "emulator-5554") + args)
                .redirectErrorStream(true).start().let { process ->
                    val result = process.inputStream.bufferedReader().use { it.readText() }
                    check(process.waitFor() == 0) { "Emulator command failed" }; result.trim()
                }
            val oldProxy = command("shell", "settings", "get", "global", "http_proxy")
            val nativeBridge = bridge!!.toHttpUrl().newBuilder().host("10.0.2.2").build().toString()
            val log = File("/tmp/picapica-parity-native-live.log")
            try {
                command("shell", "settings", "put", "global", "http_proxy", "10.0.2.2:7890")
                val process = ProcessBuilder(adb, "-s", "emulator-5554", "shell", "am", "instrument", "-w", "-r",
                    "-e", "sessionBridge", nativeBridge, "-e", "class", "com.boneink.picapica.XiangxiangParityLiveTest",
                    "com.boneink.picapica.debug.test/androidx.test.runner.AndroidJUnitRunner")
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectErrorStream(true).redirectOutput(log).start()
                assertTrue("Native live verification timed out", process.waitFor(5, TimeUnit.MINUTES))
                assertTrue("Native live verification failed; inspect its local log", log.readText().contains("OK (1 test)"))
                println("{\"nativeProfileAndCloudRoundTrip\":true,\"cloudRestored\":true}")
            } finally {
                command("shell", "settings", "put", "global", "http_proxy", ":0")
                if (oldProxy == "null" || oldProxy.isBlank()) command("shell", "settings", "delete", "global", "http_proxy")
                else command("shell", "settings", "put", "global", "http_proxy", oldProxy)
            }
        }
    }
}
