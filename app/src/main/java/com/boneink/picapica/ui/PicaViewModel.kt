package com.boneink.picapica.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boneink.picapica.AppContainer
import com.boneink.picapica.data.*
import com.boneink.picapica.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class Screen { SETTINGS, HOME, SEARCH, CATEGORIES, CATEGORY, RANKINGS, LIBRARY, PROFILE, MY_COMMENTS, LOGIN, DETAIL, COMMENTS, READER }
enum class LibraryTab { DOWNLOADS, HISTORY, CLOUD }
data class UiState(
    val preferences: AppPreferences = AppPreferences(),
    val clearingCache: Boolean = false,
    val selfProfile: UserProfile? = null,
    val profileLoading: Boolean = false, val profileError: String? = null,
    val myComments: List<RecentComment> = emptyList(),
    val myCommentPage: Int = 1, val myCommentPages: Int = 1, val myCommentTotal: Int = 0,
    val myCommentsLoading: Boolean = false, val myCommentsError: String? = null,
    val viewedUser: UserProfile? = null, val avatarPreview: UserProfile? = null,
    val readingMode: ReadingMode = ReadingMode.VERTICAL,
    val comments: List<ComicComment> = emptyList(),
    val pinnedComments: List<ComicComment> = emptyList(),
    val commentPage: Int = 1, val commentPages: Int = 1, val commentTotal: Int = 0,
    val commentLoading: Boolean = false, val commentSending: Boolean = false,
    val commentError: String? = null, val commentSent: Int = 0,
    val replyParent: ComicComment? = null,
    val replies: List<ComicComment> = emptyList(),
    val replyPage: Int = 1, val replyPages: Int = 1,
    val commentLikeBusy: Set<String> = emptySet(),
    val commentDrafts: Map<String, String> = emptyMap(),
    val historyCommentId: String? = null,
    val isOfficialDiscussion: Boolean = false,
    val commentContextReady: Boolean = true,
    val replyUnavailable: Boolean = false,
    val screen: Screen = Screen.HOME,
    val signedIn: Boolean = false,
    val name: String = "PicaPica 读者",
    val loading: Boolean = false,
    val error: String? = null,
    val errorDetails: String? = null,
    val notice: String? = null,
    val query: String = "",
    val searchSort: SearchSort = SearchSort.NEWEST,
    val searchCategories: Set<String> = emptySet(),
    val searchSubmitted: Boolean = false,
    val results: List<Comic> = emptyList(),
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val home: List<Comic> = emptyList(),
    val categories: List<ComicCategory> = emptyList(),
    val categoriesLoading: Boolean = false,
    val categoriesError: String? = null,
    val selectedCategory: ComicCategory? = null,
    val categorySort: ComicSort = ComicSort.UPDATED,
    val categoryComics: List<Comic> = emptyList(),
    val categoryPage: Int = 1,
    val categoryPages: Int = 1,
    val rankingPeriod: RankingPeriod = RankingPeriod.DAY,
    val ranking: List<Comic> = emptyList(),
    val libraryTab: LibraryTab = LibraryTab.DOWNLOADS,
    val favourites: List<Comic> = emptyList(),
    val favouriteSort: ComicSort = ComicSort.UPDATED,
    val favouritePage: Int = 1,
    val favouritePages: Int = 1,
    val comic: Comic? = null,
    val requestedComicId: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val chapter: Chapter? = null,
    val images: List<PageImage> = emptyList(),
    val initialPage: Int = 0,
    val offline: Boolean = false,
    val downloads: List<DownloadRecord> = emptyList(),
    val recent: List<Comic> = emptyList(),
)

class PicaViewModel(val container: AppContainer) : ViewModel() {
    val repo = container.repo
    private val mutable = MutableStateFlow(UiState(signedIn = repo.signedIn.value))
    val state = mutable.asStateFlow()
    private var profileJob: Job? = null
    private var myCommentsJob: Job? = null
    private data class ReplyFrame(val parent: ComicComment?, val replies: List<ComicComment>, val page: Int, val pages: Int, val unavailable: Boolean)
    private val replyBack = mutableListOf<ReplyFrame>()
    private var commentJob: Job? = null
    private var loadingJob: Job? = null
    private var categoriesJob: Job? = null
    private val back = mutableListOf<Screen>()

    init {
        viewModelScope.launch { container.settings.values.collect { value -> mutable.update { it.copy(preferences = value) } } }
        viewModelScope.launch { container.readerSettings.mode.collect { mode -> mutable.update { it.copy(readingMode = mode) } } }
        // Once per process, repair covers of existing completed downloads without polling or changing their state.
        viewModelScope.launch(Dispatchers.IO) {
            repo.downloads.first().filter { it.state == DownloadRecord.COMPLETE }.map { it.coverUrl }.distinct().forEach { url ->
                try { repo.files.cacheCover(url); repo.dao.clearCoverWarning(url) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Retry on next launch or explicit repair. */ }
            }
        }
        viewModelScope.launch { repo.signedIn.collect { value ->
            mutable.update { if (value) it.copy(signedIn = true) else it.copy(signedIn = false, selfProfile = null, myComments = emptyList(),
                myCommentPage = 1, myCommentPages = 1, myCommentTotal = 0, myCommentsLoading = false, myCommentsError = null, commentDrafts = emptyMap()) }
        } }
        viewModelScope.launch { repo.selfProfile.collect { user -> mutable.update { it.copy(selfProfile = user) } } }
        viewModelScope.launch { repo.displayName.collect { value -> mutable.update { it.copy(name = value) } } }
        viewModelScope.launch { repo.downloads.collect { rows -> mutable.update { it.copy(downloads = rows) } } }
        viewModelScope.launch { repo.recent.collect { rows -> mutable.update { it.copy(recent = rows.map(ComicRecord::comic)) } } }
        if (repo.signedIn.value) loadHome()
    }

    fun tab(screen: Screen) {
        cancelProfileLoads()
        commentJob?.cancel()
        loadingJob?.cancel()
        back.clear()
        mutable.update { it.copy(screen = screen, error = null, loading = false, viewedUser = null, avatarPreview = null) }
        if (screen == Screen.PROFILE && repo.signedIn.value) { loadSelfProfile(); loadMyComments() }
        if (screen == Screen.HOME && repo.signedIn.value && state.value.home.isEmpty()) loadHome()
        if (screen == Screen.CATEGORIES && state.value.categories.isEmpty()) loadCategories()
        if (screen == Screen.RANKINGS && state.value.ranking.isEmpty()) loadRanking()
        if (screen == Screen.LIBRARY && state.value.libraryTab == LibraryTab.CLOUD) loadFavourites()
    }
    fun navigate(screen: Screen) {
        cancelProfileLoads()
        if (state.value.screen != screen) back += state.value.screen
        loadingJob?.cancel()
        mutable.update { it.copy(screen = screen, error = null, loading = false, viewedUser = null, avatarPreview = null) }
    }
    fun goBack() {
        if (state.value.avatarPreview != null) { closeAvatar(); return }
        if (state.value.viewedUser != null) { closeUserDetails(); return }
        cancelProfileLoads()
        if (state.value.screen == Screen.COMMENTS && replyBack.isNotEmpty()) {
            commentJob?.cancel()
            val frame = replyBack.removeAt(replyBack.lastIndex)
            mutable.update { it.copy(replyParent = frame.parent, replies = frame.replies, replyPage = frame.page, replyPages = frame.pages,
                replyUnavailable = frame.unavailable, commentLoading = false, commentError = null) }
            return
        }
        val returningToComments = state.value.screen == Screen.DETAIL && back.lastOrNull() == Screen.COMMENTS
        if (!returningToComments) commentJob?.cancel()
        loadingJob?.cancel()
        val dest = back.removeLastOrNull() ?: Screen.HOME
        mutable.update { it.copy(screen = dest, error = null, loading = false) }
    }
    fun clearNotice() { mutable.update { it.copy(notice = null) } }

    private fun work(block: suspend () -> Unit) {
        loadingJob?.cancel()
        loadingJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null, errorDetails = null) }
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(e) }
            finally { if (isActive) mutable.update { it.copy(loading = false) } }
        }
    }
    private fun failed(e: Exception) {
        if (e is PicaException && e.kind == PicaException.Kind.AUTH) repo.invalidateSession()
        mutable.update { it.copy(errorDetails = (e as? PicaException)?.safeDetails(), error = when (e) {
            is PicaException -> e.message
            is java.io.IOException -> "网络连接中断，请重试"
            is IllegalStateException, is IllegalArgumentException -> e.message?.take(120) ?: "操作失败，请重试"
            else -> "数据暂时无法读取，请重试"
        }) }
    }
    fun login(account: String, password: String) = work {
        repo.login(account, password)
        back.clear()
        mutable.update { it.copy(screen = Screen.HOME, error = null, home = emptyList()) }
        val values = repo.home()
        mutable.update { it.copy(home = values.items) }
    }
    fun loadHome() = work {
        val values = repo.home()
        mutable.update { it.copy(home = values.items) }
    }
    private fun requireLogin(): Boolean {
        if (repo.signedIn.value) return true
        navigate(Screen.LOGIN)
        return false
    }
    fun loadCategories() {
        if (!requireLogin()) return
        categoriesJob?.cancel()
        if (state.value.categories.isEmpty()) mutable.update { it.copy(categories = listOf(OfficialDiscussion.category)) }
        mutable.update { it.copy(categoriesLoading = true, categoriesError = null) }
        categoriesJob = viewModelScope.launch {
            try {
                val values = withContext(Dispatchers.IO) { repo.api.categories() }
                mutable.update { it.copy(categories = listOf(OfficialDiscussion.category) + values.filterNot { c -> c.id == OfficialDiscussion.category.id || c.title == OfficialDiscussion.category.title }) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is PicaException && e.kind == PicaException.Kind.AUTH) repo.invalidateSession()
                mutable.update { it.copy(categoriesError = (e as? PicaException)?.message ?: "分类暂时无法加载，请重试") }
            } finally { if (isActive) mutable.update { it.copy(categoriesLoading = false) } }
        }
    }
    fun showCategory(category: ComicCategory) {
        if (!requireLogin()) return
        if (category.id == OfficialDiscussion.category.id) { openOfficialDiscussion(); return }
        navigate(Screen.CATEGORY)
        mutable.update { it.copy(selectedCategory = category, categoryComics = emptyList(), categoryPage = 1, categoryPages = 1) }
        loadCategory()
    }
    fun loadCategory(sort: ComicSort = state.value.categorySort, more: Boolean = false) {
        val category = state.value.selectedCategory ?: return
        if (!requireLogin() || more && state.value.loading) return
        val page = if (more) state.value.categoryPage + 1 else 1
        mutable.update { it.copy(categorySort = sort, categoryComics = if (more) it.categoryComics else emptyList()) }
        work { val values = repo.api.category(category.title, sort, page)
            mutable.update { it.copy(categoryComics = (if (more) it.categoryComics + values.items else values.items).distinctBy(Comic::id), categoryPage = values.page, categoryPages = values.pages) } }
    }
    fun loadRanking(period: RankingPeriod = state.value.rankingPeriod) {
        if (!requireLogin()) return
        mutable.update { it.copy(rankingPeriod = period, ranking = if (period == it.rankingPeriod) it.ranking else emptyList()) }
        work { val values = repo.api.ranking(period); mutable.update { it.copy(ranking = values) } }
    }
    fun selectLibraryTab(tab: LibraryTab) {
        loadingJob?.cancel()
        mutable.update { it.copy(libraryTab = tab, error = null, loading = false) }
        if (tab == LibraryTab.CLOUD) loadFavourites()
    }
    fun openFavourites() { mutable.update { it.copy(libraryTab = LibraryTab.CLOUD) }; tab(Screen.LIBRARY) }
    fun loadFavourites(sort: ComicSort = state.value.favouriteSort, more: Boolean = false) {
        if (!requireLogin() || more && state.value.loading) return
        val page = if (more) state.value.favouritePage + 1 else 1
        mutable.update { it.copy(favouriteSort = sort) }
        work { val values = repo.api.favourites(page, sort)
            mutable.update { it.copy(favourites = (if (more) it.favourites + values.items else values.items).distinctBy(Comic::id), favouritePage = values.page, favouritePages = values.pages) } }
    }
    fun toggleFavourite() {
        val comic = state.value.comic ?: return
        if (state.value.loading || state.value.offline || !requireLogin()) return
        work { val selected = repo.api.toggleFavourite(comic.id)
            mutable.update { it.copy(comic = it.comic?.takeIf { c -> c.id == comic.id }?.copy(isFavourite = selected) ?: it.comic,
                favourites = if (selected) it.favourites else it.favourites.filterNot { c -> c.id == comic.id }, notice = if (selected) "已加入云收藏" else "已取消云收藏") } }
    }
    private fun cancelProfileLoads() {
        profileJob?.cancel(); myCommentsJob?.cancel()
        mutable.update { it.copy(profileLoading = false, myCommentsLoading = false) }
    }
    fun loadSelfProfile() {
        if (!repo.signedIn.value) return
        profileJob?.cancel()
        mutable.update { it.copy(profileLoading = true, profileError = null) }
        profileJob = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { repo.refreshProfile() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is PicaException && e.kind == PicaException.Kind.AUTH) repo.invalidateSession()
                mutable.update { it.copy(profileError = (e as? PicaException)?.message ?: "个人资料暂时无法加载") }
            } finally { if (isActive) mutable.update { it.copy(profileLoading = false) } }
        }
    }
    fun openMyComments() {
        if (!requireLogin()) return
        navigate(Screen.MY_COMMENTS)
        loadMyComments()
    }
    fun loadMyComments(more: Boolean = false) {
        if (!repo.signedIn.value || more && state.value.myCommentsLoading) return
        val page = if (more) state.value.myCommentPage + 1 else 1
        myCommentsJob?.cancel()
        mutable.update { it.copy(myCommentsLoading = true, myCommentsError = null) }
        myCommentsJob = viewModelScope.launch {
            try {
                val values = withContext(Dispatchers.IO) { repo.api.myComments(page) }
                mutable.update { it.copy(myComments = (if (more) it.myComments + values.items else values.items).distinctBy(RecentComment::id),
                    myCommentPage = values.page, myCommentPages = values.pages, myCommentTotal = values.total) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is PicaException && e.kind == PicaException.Kind.AUTH) repo.invalidateSession()
                mutable.update { it.copy(myCommentsError = (e as? PicaException)?.message ?: "最近评论暂时无法加载，请重试") }
            } finally { if (isActive) mutable.update { it.copy(myCommentsLoading = false) } }
        }
    }
    fun showUser(user: UserProfile) { mutable.update { it.copy(viewedUser = user) } }
    fun closeUserDetails() { mutable.update { it.copy(viewedUser = null) } }
    fun showAvatar(user: UserProfile) { if (user.avatarUrl.isNotBlank()) mutable.update { it.copy(avatarPreview = user) } }
    fun closeAvatar() { mutable.update { it.copy(avatarPreview = null) } }
    fun updatePreferences(transform: (AppPreferences) -> AppPreferences) { container.settings.update(transform) }
    fun clearImageCache() {
        if (state.value.clearingCache) return
        mutable.update { it.copy(clearingCache = true) }
        viewModelScope.launch {
            try {
                val loader = coil.Coil.imageLoader(container.appContext)
                withContext(Dispatchers.IO) { loader.diskCache?.clear() }
                loader.memoryCache?.clear()
                mutable.update { it.copy(notice = "临时图片缓存已清除") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(notice = "缓存暂时无法清除，请重试") } }
            finally { mutable.update { it.copy(clearingCache = false) } }
        }
    }
    fun setReadingMode(mode: ReadingMode, page: Int) {
        val value = state.value
        mutable.update { it.copy(initialPage = page.coerceIn(0, value.images.lastIndex.coerceAtLeast(0)), readingMode = mode) }
        container.readerSettings.setMode(mode)
        value.comic?.let { comic -> value.chapter?.let { saveProgress(comic.id, it.order, page) } }
    }
    fun commentDraftKey(): String = "${state.value.comic?.id}:${state.value.replyParent?.id.orEmpty()}"
    fun setCommentDraft(content: String) {
        if (content.length <= 1000) mutable.update { it.copy(commentDrafts = it.commentDrafts + (commentDraftKey() to content)) }
    }
    fun openComments() {
        if (state.value.offline || !requireLogin()) return
        navigate(Screen.COMMENTS)
        replyBack.clear()
        mutable.update { it.copy(comments = emptyList(), pinnedComments = emptyList(), replyParent = null, replies = emptyList(),
            commentError = null, historyCommentId = null, commentContextReady = true, replyUnavailable = false, isOfficialDiscussion = false) }
        loadComments()
    }
    fun openOfficialDiscussion() {
        if (!requireLogin()) return
        navigate(Screen.COMMENTS)
        replyBack.clear()
        mutable.update { it.copy(comic = Comic(OfficialDiscussion.COMIC_ID, OfficialDiscussion.category.title, allowDownload = false),
            requestedComicId = OfficialDiscussion.COMIC_ID, chapters = emptyList(), offline = false,
            comments = emptyList(), pinnedComments = emptyList(), replyParent = null, replies = emptyList(),
            commentPage = 1, commentPages = 1, commentTotal = 0, replyPage = 1, replyPages = 1,
            historyCommentId = null, commentError = null, isOfficialDiscussion = true, commentContextReady = true, replyUnavailable = false) }
        loadComments()
    }
    fun openCommentWork() {
        val comic = state.value.comic ?: return
        if (state.value.screen != Screen.COMMENTS || state.value.isOfficialDiscussion) return
        // Keep the comic and current thread in memory, including while the detail request loads.
        navigate(Screen.DETAIL)
        work {
            val detail = repo.comic(comic.id)
            mutable.update { it.copy(comic = detail, requestedComicId = detail.id, commentContextReady = true) }
            val chapters = repo.chapters(comic.id)
            mutable.update { it.copy(chapters = chapters) }
        }
    }
    fun openCommentHistory(comment: RecentComment) {
        val comicId = comment.comicId ?: return
        if (!requireLogin()) return
        navigate(Screen.COMMENTS)
        replyBack.clear()
        val board = comicId == OfficialDiscussion.COMIC_ID
        val user = state.value.selfProfile ?: UserProfile("", "我")
        val target = ComicComment(comment.id, user.name, comment.content, comment.createdAt, comment.likes, comment.replies,
            comment.isLiked, user = user, parentId = comment.parentId, hidden = comment.hidden)
        mutable.update { it.copy(comic = Comic(comicId, if (board) OfficialDiscussion.category.title else comment.comicTitle, allowComment = board, allowDownload = !board), requestedComicId = comicId, chapters = emptyList(),
            offline = false, comments = emptyList(), pinnedComments = emptyList(), replyParent = target, replies = emptyList(), replyPage = 1, replyPages = 1,
            commentError = null, historyCommentId = comment.id, isOfficialDiscussion = board, commentContextReady = board, replyUnavailable = comment.hidden) }
        loadComments()
    }
    fun showAllComments() {
        commentJob?.cancel(); replyBack.clear()
        mutable.update { it.copy(replyParent = null, replies = emptyList(), comments = emptyList(), pinnedComments = emptyList(),
            replyUnavailable = false, commentError = null) }
        loadComments()
    }
    fun openReplies(parent: ComicComment) {
        val value = state.value
        if (parent.hidden || parent.id == value.replyParent?.id) return
        commentJob?.cancel()
        replyBack += ReplyFrame(value.replyParent, value.replies, value.replyPage, value.replyPages, value.replyUnavailable)
        mutable.update { it.copy(replyParent = parent, replies = emptyList(), replyPage = 1, replyPages = 1,
            commentError = null, replyUnavailable = false) }
        loadComments()
    }
    fun loadComments(more: Boolean = false) {
        val comic = state.value.comic ?: return
        if (more && state.value.commentLoading) return
        val parent = state.value.replyParent
        val page = if (!more) 1 else (if (parent != null) state.value.replyPage else state.value.commentPage) + 1
        commentJob?.cancel()
        mutable.update { it.copy(commentLoading = true, commentError = null) }
        commentJob = viewModelScope.launch {
            try {
                if (!state.value.commentContextReady) {
                    val detail = withContext(Dispatchers.IO) { repo.api.comic(comic.id) }
                    mutable.update { it.copy(comic = detail, commentContextReady = true) }
                }
                val values = withContext(Dispatchers.IO) { if (parent == null) repo.api.comments(comic.id, page) else repo.api.replies(parent.id, page) }
                mutable.update { if (parent == null) it.copy(
                    comments = (if (more) it.comments + values.items else values.items).distinctBy { c -> c.id },
                    pinnedComments = if (more) it.pinnedComments else values.pinned, commentPage = values.page, commentPages = values.pages, commentTotal = values.total,
                ) else it.copy(replies = (if (more) it.replies + values.items else values.items).distinctBy { c -> c.id }, replyPage = values.page, replyPages = values.pages,
                    replyParent = it.replyParent?.copy(replies = values.total), replyUnavailable = parent.hidden) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (e is PicaException && e.kind == PicaException.Kind.AUTH) repo.invalidateSession()
                val missing = (e as? PicaException)?.httpStatus == 404
                mutable.update { it.copy(replyUnavailable = missing || it.replyUnavailable, commentError = if (missing && it.historyCommentId != null)
                    "原评论或作品暂不可用，可查看作品评论区" else (e as? PicaException)?.message ?: "评论暂时无法加载，请重试") }
            } finally { if (isActive) mutable.update { it.copy(commentLoading = false) } }
        }
    }
    fun sendComment(content: String) {
        val value = state.value
        val comic = value.comic ?: return
        if (value.commentSending || !repo.signedIn.value || value.offline || !comic.allowComment || !value.commentContextReady || value.replyUnavailable) return
        if (content.isBlank() || content.trim().length > 1000) return
        val parent = value.replyParent
        val draftKey = commentDraftKey()
        mutable.update { it.copy(commentSending = true, commentError = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { if (parent == null) repo.api.postComment(comic.id, content) else repo.api.postReply(parent.id, content) }
                val increment: (ComicComment) -> ComicComment = { if (it.id == parent?.id) it.copy(replies = it.replies + 1) else it }
                for (i in replyBack.indices) replyBack[i] = replyBack[i].let { it.copy(parent = it.parent?.let(increment), replies = it.replies.map(increment)) }
                mutable.update { current ->
                    current.copy(commentSent = current.commentSent + 1, notice = "评论已发送", comments = current.comments.map(increment),
                        pinnedComments = current.pinnedComments.map(increment), replyParent = current.replyParent?.let(increment),
                        commentDrafts = if (current.commentDrafts[draftKey] == content) current.commentDrafts - draftKey else current.commentDrafts)
                }
                if (state.value.screen == Screen.COMMENTS && state.value.comic?.id == comic.id && state.value.replyParent?.id == parent?.id) loadComments()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (e is PicaException && e.kind == PicaException.Kind.AUTH) repo.invalidateSession()
                mutable.update { it.copy(commentError = if (e is PicaException && e.kind !in setOf(PicaException.Kind.NETWORK, PicaException.Kind.SERVICE)) e.message else "发送未确认，请先刷新列表查看，避免重复发送") }
            } finally { mutable.update { it.copy(commentSending = false) } }
        }
    }
    fun likeComment(comment: ComicComment) {
        if (!repo.signedIn.value || comment.id in state.value.commentLikeBusy) return
        mutable.update { it.copy(commentLikeBusy = it.commentLikeBusy + comment.id) }
        viewModelScope.launch {
            try {
                val liked = withContext(Dispatchers.IO) { repo.api.toggleCommentLike(comment.id) }
                val update: (ComicComment) -> ComicComment = { c -> if (c.id != comment.id) c else c.copy(isLiked = liked, likes = (c.likes + if (liked == c.isLiked) 0 else if (liked) 1 else -1).coerceAtLeast(0)) }
                for (i in replyBack.indices) replyBack[i] = replyBack[i].let { it.copy(parent = it.parent?.let(update), replies = it.replies.map(update)) }
                mutable.update { it.copy(comments = it.comments.map(update), pinnedComments = it.pinnedComments.map(update), replies = it.replies.map(update), replyParent = it.replyParent?.let(update)) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (e is PicaException && e.kind == PicaException.Kind.AUTH) repo.invalidateSession()
                mutable.update { it.copy(commentError = (e as? PicaException)?.message ?: "点赞未确认，请刷新评论") }
            } finally { mutable.update { it.copy(commentLikeBusy = it.commentLikeBusy - comment.id) } }
        }
    }
    fun openSearch(category: String? = null) {
        navigate(Screen.SEARCH)
        if (category != null) mutable.update { it.copy(searchCategories = setOf(category), results = emptyList(), currentPage = 1, totalPages = 1, searchSubmitted = false) }
        if (repo.signedIn.value && state.value.categories.none { it.id != OfficialDiscussion.category.id } && !state.value.categoriesLoading) loadCategories()
    }
    fun search(query: String, more: Boolean = false, sort: SearchSort = state.value.searchSort, categories: Set<String> = state.value.searchCategories) {
        if (!requireLogin() || more && state.value.loading) return
        // A subsequent page always uses the exact applied criteria, even if a draft changed.
        val keyword = if (more) state.value.query else query.trim()
        val appliedSort = if (more) state.value.searchSort else sort
        val appliedCategories = if (more) state.value.searchCategories else categories.filter(String::isNotBlank).toSet()
        val page = if (more) state.value.currentPage + 1 else 1
        mutable.update { it.copy(query = keyword, searchSort = appliedSort, searchCategories = appliedCategories, searchSubmitted = true,
            results = if (more) it.results else emptyList(), currentPage = if (more) it.currentPage else 1, totalPages = if (more) it.totalPages else 1) }
        work {
            val values = repo.search(keyword, page, appliedSort, appliedCategories)
            mutable.update { it.copy(results = (if (more) it.results + values.items else values.items).distinctBy(Comic::id), currentPage = values.page, totalPages = values.pages) }
        }
    }
    fun openComic(id: String, offline: Boolean = false) {
        navigate(Screen.DETAIL)
        mutable.update { it.copy(comic = null, requestedComicId = id, chapters = emptyList(), offline = offline, isOfficialDiscussion = false) }
        work {
            val c = repo.comic(id, offline)
            mutable.update { it.copy(comic = c) }
            val eps = repo.chapters(id, offline)
            mutable.update { it.copy(chapters = eps) }
        }
    }
    fun continueReading() {
        val c = state.value.comic ?: return
        val chapters = state.value.chapters
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { repo.progress(c.id) }
            val chapter = chapters.firstOrNull { it.order == saved?.chapterOrder } ?: chapters.firstOrNull()
            if (chapter != null) openChapter(chapter)
        }
    }
    fun openChapter(chapter: Chapter, forceOffline: Boolean = state.value.offline) {
        val c = state.value.comic ?: return
        if (state.value.screen != Screen.READER) navigate(Screen.READER)
        mutable.update { it.copy(chapter = chapter, images = emptyList(), offline = forceOffline) }
        work {
            val pages = repo.readerPages(c.id, chapter.order, forceOffline)
            val saved = repo.progress(c.id)
            val initial = if (saved?.chapterOrder == chapter.order) saved.pageIndex.coerceIn(0, pages.lastIndex) else 0
            mutable.update { it.copy(images = pages, initialPage = initial) }
            repo.saveProgress(c.id, chapter.order, initial)
        }
    }
    fun adjacentChapter(direction: Int) {
        val chapters = state.value.chapters
        val index = chapters.indexOfFirst { it.order == state.value.chapter?.order }
        chapters.getOrNull(index + direction)?.let { openChapter(it) }
    }
    fun openDownloaded(record: DownloadRecord) {
        navigate(Screen.READER)
        mutable.update { it.copy(comic = null, chapter = Chapter("", record.chapterTitle, record.chapterOrder), images = emptyList(), offline = true) }
        work {
            val c = repo.comic(record.remoteComicId, offline = true)
            val eps = repo.chapters(c.id, offline = true)
            val chapter = eps.firstOrNull { it.order == record.chapterOrder } ?: Chapter("", record.chapterTitle, record.chapterOrder)
            val pages = repo.readerPages(c.id, chapter.order, offline = true)
            val saved = repo.progress(c.id)
            val initial = if (saved?.chapterOrder == chapter.order) saved.pageIndex.coerceIn(0, pages.lastIndex) else 0
            mutable.update { it.copy(comic = c, chapters = eps, chapter = chapter, images = pages, initialPage = initial) }
            repo.saveProgress(c.id, chapter.order, initial)
        }
    }
    fun saveProgress(id: String, order: Int, index: Int) {
        viewModelScope.launch(Dispatchers.IO) { repo.saveProgress(id, order, index) }
    }
    fun download(chapters: List<Chapter>) {
        val comic = state.value.comic ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chapters.forEach { container.downloads.enqueue(comic, it) }
                mutable.update { it.copy(notice = "已加入 ${chapters.size} 个章节的下载队列") }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { failed(e) }
        }
    }
    fun pause(key: String) { downloadAction { container.downloads.pause(key) } }
    fun resume(key: String) { downloadAction { container.downloads.resume(key) } }
    private fun downloadAction(action: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { failed(e) }
        }
    }
    fun logout() {
        cancelProfileLoads()
        loadingJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            state.value.downloads.filter { it.state in setOf(DownloadRecord.QUEUED, DownloadRecord.RUNNING) }.forEach { container.downloads.pause(it.chapterKey) }
            repo.logout()
            mutable.update { it.copy(home = emptyList(), results = emptyList(), comic = null, chapters = emptyList(), images = emptyList(), favourites = emptyList(), selfProfile = null, myComments = emptyList(), myCommentPage = 1, myCommentPages = 1, myCommentTotal = 0, profileError = null, myCommentsError = null, viewedUser = null, avatarPreview = null) }
            withContext(Dispatchers.Main) { tab(Screen.PROFILE) }
        }
    }
}
