package com.boneink.picapica

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Proxy
import java.net.URL
import java.util.concurrent.TimeUnit

/** Opt-in existing session bridge. Native test restores its single temporary favourite in finally. */
class XiangxiangFavouritesLiveTest {
    @Test fun nativeCloudPagesAddRemoveAndSessionRemainConsistent() {
        val bridge = System.getenv("PICAPICA_LIVE_AUTH_URL"); assumeTrue(bridge != null)
        URL(bridge).openConnection(Proxy.NO_PROXY).getInputStream().use { assertTrue(it.read() >= 0) }
        val sdk = System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/.local/share/bone-drop-toolchain/sdk"
        val adb = "$sdk/platform-tools/adb"
        fun command(vararg args: String): String = ProcessBuilder(listOf(adb, "-s", "emulator-5554") + args)
            .redirectErrorStream(true).start().let { process ->
                val output = process.inputStream.bufferedReader().use { it.readText() }
                check(process.waitFor() == 0) { "Emulator command failed" }; output.trim()
            }
        val previous = command("shell", "settings", "get", "global", "http_proxy")
        val nativeBridge = bridge!!.toHttpUrl().newBuilder().host("10.0.2.2").build().toString()
        val log = File("/tmp/picapica-favourite-native-live.log")
        try {
            command("shell", "settings", "put", "global", "http_proxy", "10.0.2.2:7890")
            val process = ProcessBuilder(adb, "-s", "emulator-5554", "shell", "am", "instrument", "-w", "-r",
                "-e", "sessionBridge", nativeBridge, "-e", "class", "com.boneink.picapica.XiangxiangParityLiveTest",
                "com.boneink.picapica.debug.test/androidx.test.runner.AndroidJUnitRunner")
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).redirectErrorStream(true).redirectOutput(log).start()
            assertTrue("Native favourite verification timed out", process.waitFor(5, TimeUnit.MINUTES))
            assertTrue("Native favourite verification failed; inspect the local log", log.readText().contains("OK (1 test)"))
            println("{\"realCloudList\":true,\"addAndRemoveConfirmed\":true,\"originalFavouriteSetRestored\":true,\"profileAndSessionPreserved\":true}")
        } finally {
            command("shell", "settings", "put", "global", "http_proxy", ":0")
            if (previous == "null" || previous.isBlank()) command("shell", "settings", "delete", "global", "http_proxy")
            else command("shell", "settings", "put", "global", "http_proxy", previous)
        }
    }
}
