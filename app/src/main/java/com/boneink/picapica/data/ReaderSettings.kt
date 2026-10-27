package com.boneink.picapica.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReadingMode(val title: String) { VERTICAL("竖向连续"), HORIZONTAL("横向翻页") }
class ReaderSettings(context: Context, name: String) {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(runCatching {
        ReadingMode.valueOf(preferences.getString("mode", null) ?: "VERTICAL")
    }.getOrDefault(ReadingMode.VERTICAL))
    val mode = mutable.asStateFlow()
    fun setMode(value: ReadingMode) {
        preferences.edit().putString("mode", value.name).apply()
        mutable.value = value
    }
}
