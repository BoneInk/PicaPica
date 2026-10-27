package com.boneink.picapica.network

import java.text.Normalizer
import java.util.Locale

fun normalizeLabel(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

/** Labels and explicit API flags only; never infer generated artwork from image pixels or title substrings. */
val Comic.isAiGenerated: Boolean get() = aiGenerated || (categories + tags).any {
    normalizeLabel(it).replace(Regex("[\\s_-]+"), "") in setOf("ai", "ai生成", "ai生成物", "ai產物", "ai产物", "ai繪圖", "ai绘图", "aigenerated", "aigeneratedart", "ai作品")
}
val Comic.languageMark: String? get() {
    val labels = (categories + tags).map(::normalizeLabel).toSet()
    return when {
        labels.any { it in setOf("生肉", "未汉化", "未漢化", "未翻译", "未翻譯") } -> "未汉化"
        labels.any { it in setOf("英語 eng", "英语 eng", "英語", "英语", "english") } -> "英语内容"
        else -> null
    }
}
val Comic.completionLabel: String? get() = if (!completionKnown) null else if (finished) "已完结" else "连载中"

/** Fill only absent list metadata from an already-read detail; this never fetches N extra requests. */
fun Comic.withKnownMetadata(known: Comic?): Comic {
    if (known == null || known.id != id) return this
    return copy(author = author.ifBlank { known.author }, description = description.ifBlank { known.description },
        tags = if (tagsAvailable) tags else known.tags, tagsAvailable = tagsAvailable || known.tagsAvailable,
        categories = if (categoriesAvailable) categories else known.categories, categoriesAvailable = categoriesAvailable || known.categoriesAvailable,
        chineseTeam = chineseTeam.ifBlank { known.chineseTeam }, aiGenerated = aiGenerated || known.aiGenerated,
        finished = if (completionKnown) finished else known.finished, completionKnown = completionKnown || known.completionKnown,
        pagesCount = pagesCount.takeIf { it > 0 } ?: known.pagesCount, likes = likes ?: known.likes, views = views ?: known.views)
}
