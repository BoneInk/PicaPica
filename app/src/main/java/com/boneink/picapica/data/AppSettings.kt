package com.boneink.picapica.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppTheme(val title: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
enum class ReaderOrientation(val title: String) { SYSTEM("跟随系统"), PORTRAIT("竖屏"), LANDSCAPE("横屏") }
enum class ImageQuality(val code: String, val title: String) { ORIGINAL("original", "原图"), HIGH("high", "高"), MEDIUM("medium", "中"), LOW("low", "低") }
data class AppPreferences(
    val theme: AppTheme = AppTheme.SYSTEM,
    val imageQuality: ImageQuality = ImageQuality.ORIGINAL,
    val orientation: ReaderOrientation = ReaderOrientation.SYSTEM,
    val volumePaging: Boolean = false,
    val keepScreenOn: Boolean = true,
    val brightness: Float = -1f,
)

/** Local preferences only. Existing reader mode and all library/session data keep their names. */
class AppSettings(context: Context, name: String) {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private inline fun <reified T : Enum<T>> enum(key: String, fallback: T): T = runCatching { enumValueOf<T>(prefs.getString(key, null).orEmpty()) }.getOrDefault(fallback)
    private val mutable = MutableStateFlow(AppPreferences(
        theme = enum("theme", AppTheme.SYSTEM), imageQuality = enum("quality", ImageQuality.ORIGINAL), orientation = enum("orientation", ReaderOrientation.SYSTEM),
        volumePaging = prefs.getBoolean("volumePaging", false), keepScreenOn = prefs.getBoolean("keepScreenOn", true),
        brightness = prefs.getFloat("brightness", -1f).let { if (it.isFinite() && it in .05f..1f) it else -1f },
    ))
    val values = mutable.asStateFlow()
    fun update(transform: (AppPreferences) -> AppPreferences) {
        val value = transform(mutable.value).let { it.copy(brightness = if (it.brightness.isFinite() && it.brightness in .05f..1f) it.brightness else -1f) }
        prefs.edit().putString("theme", value.theme.name).putString("quality", value.imageQuality.name).putString("orientation", value.orientation.name)
            .putBoolean("volumePaging", value.volumePaging).putBoolean("keepScreenOn", value.keepScreenOn).putFloat("brightness", value.brightness).apply()
        mutable.value = value
    }
}
