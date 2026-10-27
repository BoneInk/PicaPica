package com.boneink.picapica

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boneink.picapica.data.ImageQuality
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Existing non-explicit reading fixture, two temporary in-memory GETs, no artwork files or screenshots. */
@RunWith(AndroidJUnit4::class)
class RealImageQualityTest {
    @Test fun measureOriginalAndLowResponsesWithoutTouchingExistingDownloads() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realImageQuality") == "true")
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val container = AppContainer(app, storageName = "real-quality-${System.currentTimeMillis()}", sessionName = "picapica.session")
        assertTrue(container.repo.signedIn.value)
        try {
            container.settings.update { it.copy(imageQuality = ImageQuality.ORIGINAL) }
            val original = container.repo.api.pages("6a1c5e2e200d604e2a4fed0e", 1).first()
            container.settings.update { it.copy(imageQuality = ImageQuality.LOW) }
            val low = container.repo.api.pages("6a1c5e2e200d604e2a4fed0e", 1).first()
            fun bytes(url: String): ByteArray = container.network.imageHttp.newCall(Request.Builder().url(url).build()).execute().use {
                assertTrue(it.isSuccessful)
                val source = requireNotNull(it.body).source()
                source.request(20L * 1024 * 1024 + 1)
                assertTrue(source.buffer.size in 1..20L * 1024 * 1024)
                source.readByteArray()
            }
            val originalBytes = bytes(original.url)
            val lowBytes = if (original.url == low.url) originalBytes else bytes(low.url)
            fun dimensions(data: ByteArray) = BitmapFactory.Options().apply { inJustDecodeBounds = true;BitmapFactory.decodeByteArray(data, 0, data.size, this);assertTrue(outWidth > 0 && outHeight > 0) }
            val a = dimensions(originalBytes);val b = dimensions(lowBytes)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("quality_api", "passed")
                putString("url_changed", (original.url != low.url).toString())
                putString("original_image", "${a.outWidth}x${a.outHeight} ${originalBytes.size} bytes")
                putString("low_image", "${b.outWidth}x${b.outHeight} ${lowBytes.size} bytes")
                putString("payload_changed", (!originalBytes.contentEquals(lowBytes)).toString())
                putString("real_mutations", "0")
            })
        } finally { container.db.close() }
    }
}
