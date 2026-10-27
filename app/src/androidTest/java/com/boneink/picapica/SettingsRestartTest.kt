package com.boneink.picapica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boneink.picapica.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs after force-stop in a separate instrumentation process. */
@RunWith(AndroidJUnit4::class)
class SettingsRestartTest {
    @Test fun preferencesSurviveActualProcessRestart() {
        val app = ApplicationProvider.getApplicationContext<PicaPicaApp>()
        val storage = requireNotNull(app.getSharedPreferences("settings-e2e.bootstrap", Context.MODE_PRIVATE).getString("storage", null))
        val values = AppSettings(app, "$storage.settings").values.value
        assertEquals(AppTheme.DARK, values.theme);assertEquals(ImageQuality.HIGH, values.imageQuality)
        assertEquals(ReaderOrientation.PORTRAIT, values.orientation);assertTrue(values.volumePaging);assertTrue(values.keepScreenOn)
        assertEquals(.35f, values.brightness, .01f);assertEquals(ReadingMode.HORIZONTAL, ReaderSettings(app, "$storage.reader").mode.value)
        // Malformed local options fall back safely without touching sessions or library data.
        val name = "invalid-settings-${System.nanoTime()}"
        app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("theme", "unknown").putString("quality", "unknown").putFloat("brightness", Float.NaN).commit()
        assertEquals(AppPreferences(), AppSettings(app, name).values.value)
    }
}
