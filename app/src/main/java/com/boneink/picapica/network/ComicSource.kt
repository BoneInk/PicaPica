package com.boneink.picapica.network

/** Source contracts keep protocol and capability differences out of shared reading screens. */
enum class SourceKind(val title: String, val baseUrl: String) {
    PICA("PicACG", "https://picaapi.picacomic.com/"),
    XIANGXIANG("香香腐宅", "https://fulovehouse.net/"),
}

data class SourceCapabilities(
    val registration: Boolean = true,
    val editProfile: Boolean = true,
    val editSlogan: Boolean = true,
    val recentCommentsTitle: String = "最近评论",
    val recentComments: Boolean = true,
    val officialDiscussion: Boolean = true,
    val searchCategories: Boolean = true,
    val searchSorts: List<SearchSort> = SearchSort.entries,
    val comicSorts: List<ComicSort> = ComicSort.entries,
    val favouriteSorts: List<ComicSort> = comicSorts,
    val rankingPeriods: List<RankingPeriod> = RankingPeriod.entries,
    val rankingTitles: Map<RankingPeriod, String> = emptyMap(),
    val homepage: Boolean = false,
)

interface ComicSource {
    val config: SourceConfig
    val backoff: ApiBackoff
    val capabilities: SourceCapabilities get() = SourceCapabilities()
    suspend fun captcha(): ByteArray? = null
    suspend fun signIn(account: String, password: String, captcha: String = ""): String
    suspend fun profile(): UserProfile
    suspend fun checkInStatus(): DailyCheckIn
    suspend fun checkIn(): DailyCheckIn
    suspend fun homepage(): HomeFeed = throw UnsupportedOperationException()
    suspend fun homeSection(key: String, page: Int = 1): ComicResults = throw UnsupportedOperationException()
    suspend fun register(form: Registration)
    suspend fun requestRegistrationCode(email: String): Unit = throw UnsupportedOperationException()
    suspend fun registerXiangxiang(form: XiangxiangRegistration): Unit = throw UnsupportedOperationException()
    suspend fun updateSlogan(slogan: String)
    suspend fun updateAvatar(jpeg: ByteArray)
    suspend fun myComments(page: Int = 1): RecentCommentResults
    suspend fun comics(page: Int = 1): ComicResults
    suspend fun categories(): List<ComicCategory>
    suspend fun category(title: String, sort: ComicSort = ComicSort.UPDATED, page: Int = 1): ComicResults
    suspend fun ranking(period: RankingPeriod): List<Comic>
    suspend fun favourites(page: Int = 1, sort: ComicSort = ComicSort.UPDATED): ComicResults
    suspend fun toggleFavourite(id: String): Boolean
    suspend fun comments(comicId: String, page: Int = 1): CommentResults
    suspend fun replies(commentId: String, page: Int = 1): CommentResults
    suspend fun postComment(comicId: String, content: String)
    suspend fun postReply(commentId: String, content: String)
    suspend fun toggleCommentLike(commentId: String): Boolean
    suspend fun search(keyword: String, page: Int = 1, sort: SearchSort = SearchSort.NEWEST, categories: Set<String> = emptySet()): ComicResults
    suspend fun comic(id: String): Comic
    suspend fun chapters(id: String): List<Chapter>
    suspend fun pages(id: String, order: Int): List<PageImage>
}
