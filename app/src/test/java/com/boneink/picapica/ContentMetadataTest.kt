package com.boneink.picapica

import com.boneink.picapica.data.*
import com.boneink.picapica.network.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test

class ContentMetadataTest {
    @Test fun keywordIsLiteralNormalizedAndAnyRuleBlocks() {
        val rules = ContentBlockRules(keywords = setOf(" ＡＢＣ ", ".*"), categories = setOf("日常"))
        assertTrue(rules.blocks(Comic("a", "An abc story")))
        assertTrue(rules.blocks(Comic("a", "Book", author = "ABC")))
        assertTrue(rules.blocks(Comic("a", "Book", description = "literal .* value")))
        assertTrue(rules.blocks(Comic("a", "Book", categories = listOf("日常"))))
        assertFalse(rules.blocks(Comic("a", "Book")))
        assertFalse(rules.copy(enabled = false).blocks(Comic("a", "ABC")))
    }
    @Test fun tagsAndCategoriesAreIndependentWholeLabels() {
        val comic = Comic("a", "Story", tags = listOf("magic"), categories = listOf("冒险"))
        assertTrue(ContentBlockRules(tags = setOf("MAGIC")).blocks(comic))
        assertFalse(ContentBlockRules(tags = setOf("冒险")).blocks(comic))
        assertFalse(ContentBlockRules(categories = setOf("magic")).blocks(comic))
        assertFalse(ContentBlockRules(tags = setOf("mag")).blocks(comic))
    }
    @Test fun aiAndTranslationRequireExplicitMetadata() {
        val rules = ContentBlockRules(hideAi = true)
        for (label in listOf("AI生成", "AI_GENERATED", "AI 繪圖", "ai")) assertTrue(rules.blocks(Comic("a", "Story", tags = listOf(label))))
        assertTrue(rules.blocks(Comic("a", "Story", aiGenerated = true)))
        assertFalse(rules.blocks(Comic("a", "AI story", author = "rain", tags = listOf("railway", "paint"))))
        assertEquals("未汉化", Comic("a", "中文标题", categories = listOf("生肉")).languageMark)
        assertNull(Comic("a", "日本語の本").languageMark)
        assertNull(Comic("a", "Story", completionKnown = false).completionLabel)
    }
    @Test fun protocolKeepsTagsSeparateAndMissingStatusUnknown() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":200,"data":{"comics":{"docs":[{"_id":"a","title":"Story","categories":["生肉"],"pagesCount":15,"likesCount":23},{"_id":"b","title":"Other","tags":[],"finished":true}],"page":1,"pages":2}}}"""))
            val comics = PicaClient(SourceConfig(server.url("/")), { "fixture" }).comics().items
            assertEquals(listOf("生肉"), comics[0].categories); assertTrue(comics[0].tags.isEmpty())
            assertFalse(comics[0].tagsAvailable); assertTrue(comics[0].categoriesAvailable)
            assertFalse(comics[0].completionKnown); assertEquals(15, comics[0].pagesCount); assertEquals(23L, comics[0].likes)
            assertTrue(comics[1].tagsAvailable); assertTrue(comics[1].finished)
        }
    }
    @Test fun savedMetadataRoundTripsAndLegacyTagsStayReadable() {
        val config = SourceConfig()
        val comic = Comic("a", "Story", tags = listOf("magic"), categories = listOf("生肉"), chineseTeam = "Team", aiGenerated = true, pagesCount = 12, likes = 45, views = 90)
        val record = ComicRecord.from(config, comic)
        assertEquals(comic, record.comic())
        val legacy = record.copy(metadataJson = "{}")
        assertEquals(listOf("magic"), legacy.comic().tags); assertTrue(legacy.comic().categories.isEmpty())
        assertTrue(legacy.comic().completionKnown)
    }
    @Test fun enrichOnlyAbsentFieldsWithoutOverridingExplicitEmptyTags() {
        val detail = Comic("a", "Story", tags = listOf("AI生成"), categories = listOf("生肉"), aiGenerated = false)
        val list = Comic("a", "Story", completionKnown = false)
        assertTrue(ContentBlockRules(hideAi = true).blocks(list.withKnownMetadata(detail)))
        assertTrue(list.copy(tagsAvailable = true).withKnownMetadata(detail).tags.isEmpty())
        assertEquals(list, list.withKnownMetadata(detail.copy(id = "different")))
    }
}
