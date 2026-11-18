package com.boneink.picapica

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.StrictMode
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.data.SessionStore
import com.boneink.picapica.network.SourceConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Local cached artwork only. Emulator frame timings are diagnostics, not phone FPS claims. */
class ScrollingPerformanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun composeView(view: android.view.View): android.view.View? {
        if (view.javaClass.simpleName == "AndroidComposeView") return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
            composeView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    @Test fun scrollingCachedCoversDoesNotReadFilesOnTheUiThread() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val baseline = InstrumentationRegistry.getArguments().getString("perfBaseline") == "true"
        FixtureServer().use { fixture ->
            val original = fixture.server.dispatcher
            val docs = JSONArray()
            repeat(40) { index ->
                docs.put(fixture.comic().put("_id", if (index == 0) "comic1" else "scroll$index")
                    .put("thumb", JSONObject().put("fileServer", fixture.server.url("/").toString()).put("path", "scroll$index.png")))
            }
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl!!.encodedPath == "/comics") return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(JSONObject().put("code", 200).put("data", JSONObject().put("comics", JSONObject()
                            .put("docs", docs).put("page", 1).put("pages", 1))).toString())
                    return original.dispatch(request)
                }
            }
            val storage = "scroll-check-${System.nanoTime()}"
            SessionStore(app, "$storage.session").saveToken(FixtureServer.TOKEN)
            val container = AppContainer(app, SourceConfig(fixture.server.url("/")), storage)
            repeat(40) { container.repo.files.cacheCover(fixture.server.url("/static/scroll$it.png").toString()) }
            app.installTestContainer(container)
            val diskReads = AtomicInteger()
            val frames = ConcurrentLinkedQueue<Long>()
            val recording = AtomicBoolean(false)
            val thread = HandlerThread("scroll-frame-metrics").apply { start() }
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                if (recording.get()) frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
            }
            var refreshRate = 0f
            var requestedRate = 0f
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("offline-cover-loaded-comic1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                lateinit var oldPolicy: StrictMode.ThreadPolicy
                scenario.onActivity { activity ->
                    oldPolicy = StrictMode.getThreadPolicy()
                    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(oldPolicy).detectDiskReads()
                        .penaltyListener(Executor { it.run() }) { violation ->
                            if (violation.stackTrace.any { it.className.contains("picapica.download.LocalFiles") }) diskReads.incrementAndGet()
                        }.build())
                    activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                    @Suppress("DEPRECATION")
                    refreshRate = activity.windowManager.defaultDisplay.refreshRate
                    if (Build.VERSION.SDK_INT >= 35) requestedRate = composeView(activity.window.decorView)?.requestedFrameRate ?: Float.NaN
                }
                try {
                    recording.set(true)
                    repeat(8) {
                        compose.onNodeWithTag("home-list").performTouchInput { swipeUp(durationMillis = 300) }
                        compose.waitForIdle()
                    }
                } finally {
                    recording.set(false)
                    scenario.onActivity { activity ->
                        StrictMode.setThreadPolicy(oldPolicy)
                        activity.window.removeOnFrameMetricsAvailableListener(listener)
                    }
                }
            }
            thread.quitSafely(); thread.join(5_000)
            val timings = frames.toList().filter { it > 0 }.sorted()
            val report = JSONObject().put("variant", if (baseline) "baseline-debug" else "updated-debug")
                .put("emulator", true).put("cover_disk_reads_on_ui_thread", diskReads.get())
                .put("observed_refresh_hz", refreshRate).put("requested_frame_rate_category", if (requestedRate.isFinite()) requestedRate else JSONObject.NULL)
                .put("frame_count", timings.size)
            if (timings.isNotEmpty()) report.put("frame_ms_p50", timings[timings.size / 2] / 1_000_000.0)
                .put("frame_ms_p95", timings[((timings.size - 1) * .95).toInt()] / 1_000_000.0)
            val directory = File(app.getExternalFilesDir(null), "performance").apply { mkdirs() }
            File(directory, if (baseline) "scroll-baseline.json" else "scroll-updated.json").writeText(report.toString(2))
            if (!baseline) {
                assertEquals("Cover lookup must stay off the UI thread", 0, diskReads.get())
                if (Build.VERSION.SDK_INT >= 35) assertEquals(android.view.View.REQUESTED_FRAME_RATE_CATEGORY_HIGH, requestedRate, 0f)
            }
            assertTrue("Capture actual rendered frames", timings.isNotEmpty())
            assertTrue(fixture.violations.toString(), fixture.violations.isEmpty())
            container.db.close()
        }
    }
}
