package com.boneink.picapica.data

import android.content.Context
import com.boneink.picapica.network.Comic
import com.boneink.picapica.network.isAiGenerated
import com.boneink.picapica.network.normalizeLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ContentBlockRules(
    val enabled: Boolean = true,
    val keywords: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val categories: Set<String> = emptySet(),
    val hideAi: Boolean = false,
) {
    private val words = keywords.map(::normalizeLabel).filter(String::isNotBlank)
    private val tagKeys = tags.map(::normalizeLabel).filter(String::isNotBlank).toSet()
    private val categoryKeys = categories.map(::normalizeLabel).filter(String::isNotBlank).toSet()
    val count: Int get() = keywords.size + tags.size + categories.size + if (hideAi) 1 else 0
    fun blocks(comic: Comic): Boolean {
        if (!enabled || count == 0) return false
        if (hideAi && comic.isAiGenerated) return true
        if (comic.tags.any { normalizeLabel(it) in tagKeys } || comic.categories.any { normalizeLabel(it) in categoryKeys }) return true
        val text = listOf(comic.title, comic.author, comic.description).map(::normalizeLabel)
        return words.any { word -> text.any { word in it } }
    }
}

class ContentBlocking(context: Context, name: String) {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(ContentBlockRules(prefs.getBoolean("enabled", true), read("keywords"), read("tags"), read("categories"), prefs.getBoolean("hideAi", false)))
    val rules = mutable.asStateFlow()
    private fun read(key: String): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty().toSet()
    fun update(transform: (ContentBlockRules) -> ContentBlockRules) {
        fun clean(values: Set<String>) = values.map(String::trim).filter(String::isNotBlank).distinctBy(::normalizeLabel).take(200).map { it.take(120) }.toSet()
        val next = transform(mutable.value).let { it.copy(keywords = clean(it.keywords), tags = clean(it.tags), categories = clean(it.categories)) }
        prefs.edit().putBoolean("enabled", next.enabled).putBoolean("hideAi", next.hideAi).putStringSet("keywords", next.keywords)
            .putStringSet("tags", next.tags).putStringSet("categories", next.categories).apply()
        mutable.value = next
    }
}
