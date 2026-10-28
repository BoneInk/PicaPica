@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.boneink.picapica.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boneink.picapica.data.DownloadRecord
import com.boneink.picapica.network.*

@Composable
fun PicaPicaScreen(model: PicaViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val error = state.error
    val focus = LocalFocusManager.current
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.screen, state.viewedUser, state.avatarPreview) { focus.clearFocus(); keyboard?.hide() }
    val snackbar = remember { SnackbarHostState() }
    val screenStates = rememberSaveableStateHolder()
    BackHandler(enabled = state.screen !in setOf(Screen.HOME)) { model.goBack() }
    LaunchedEffect(state.notice, state.blockUndo) { state.notice?.let {
        val undo = state.blockUndo?.takeIf { it.message == state.notice }
        val result = snackbar.showSnackbar(it, actionLabel = if (undo != null) "撤销" else null, withDismissAction = true)
        if (result == SnackbarResult.ActionPerformed && undo != null) model.undoQuickBlock(undo) else model.clearNotice()
    } }
    if (state.screen == Screen.READER) {
        ReaderScreen(state, model)
        return
    }
    val activity = androidx.activity.compose.LocalActivity.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    SideEffect { activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
        isAppearanceLightStatusBars = !dark;isAppearanceLightNavigationBars = !dark
    } } }
    val topLevel = state.screen in setOf(Screen.HOME, Screen.CATEGORIES, Screen.LIBRARY, Screen.PROFILE)
    val actions = remember(model) { QuickBlockActions(model::quickBlockComic, model::quickBlockTag, model::quickBlockCategory,
        { model.openSearch(); model.search(it) }, { model.openSearch(it); model.search("", categories = setOf(it)) }) }
    CompositionLocalProvider(LocalQuickBlocks provides actions) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbar, modifier = if (topLevel) Modifier else Modifier.navigationBarsPadding()) },
        topBar = {
            if (state.screen == Screen.HOME) BrandBar(state, onSearch = { model.openSearch() }, onProfile = { model.tab(Screen.PROFILE) })
            else TopAppBar(
                title = { Text(when (state.screen) {
                    Screen.BLOCKING -> "内容屏蔽"
                    Screen.SETTINGS -> "设置"
                    Screen.SEARCH -> "搜索"
                    Screen.CATEGORIES -> "分类"
                    Screen.RANKINGS -> "排行榜"
                    Screen.CATEGORY -> state.selectedCategory?.title ?: "分类作品"
                    Screen.LIBRARY -> "我的书架"
                    Screen.PROFILE -> "我的"
                    Screen.MY_COMMENTS -> "我的评论"
                    Screen.LOGIN -> "登录 PicaPica"
                    Screen.REGISTER -> "注册账号"
                    Screen.EDIT_PROFILE -> "编辑个人资料"
                    Screen.COMMENTS -> if (state.isOfficialDiscussion && state.replyParent == null) "官方评论区" else if (state.replyParent?.parentId != null) "楼中楼回复" else if (state.replyParent != null) "评论回复" else "作品评论"
                    else -> "作品详情"
                }, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { if (!topLevel) IconButton(onClick = { backDispatcher?.onBackPressed() ?: model.goBack() },
                    enabled = !(state.loading && state.screen in setOf(Screen.REGISTER, Screen.EDIT_PROFILE))) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    if (state.screen == Screen.CATEGORIES) {
                        IconButton(onClick = model::loadCategories, enabled = !state.categoriesLoading) { Icon(Icons.Outlined.Refresh, "刷新分类") }
                        IconButton(onClick = model::openRankings, modifier = Modifier.testTag("open-rankings")) { Icon(Icons.Outlined.Leaderboard, "排行榜") }
                    }
                    if (state.screen == Screen.PROFILE) IconButton(onClick = { model.navigate(Screen.SETTINGS) }, modifier = Modifier.testTag("open-settings")) { Icon(Icons.Outlined.Settings, "设置") }
                    if (state.screen == Screen.CATEGORY) IconButton(onClick = { model.openSearch(state.selectedCategory?.title) }) { Icon(Icons.Outlined.Search, "在此分类中搜索") }
                    if (state.screen == Screen.COMMENTS && !state.isOfficialDiscussion && state.comic != null)
                        TextButton(onClick = model::openCommentWork, modifier = Modifier.testTag("comment-open-work")) { Text("作品详情", style = MaterialTheme.typography.bodySmall) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { if (topLevel) BottomNavigation(state.screen, model::tab) },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).then(if (!topLevel && state.screen !in setOf(Screen.DETAIL, Screen.COMMENTS)) Modifier.navigationBarsPadding() else Modifier)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("loading"), color = MaterialTheme.colorScheme.primary)
            if (error != null && state.screen !in setOf(Screen.LOGIN, Screen.REGISTER, Screen.EDIT_PROFILE)) Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                ErrorMessage(error, if (state.signedIn || state.offline) "重试" else "登录") {
                    if (!state.signedIn && !state.offline) model.navigate(Screen.LOGIN)
                    else when (state.screen) {
                        Screen.SEARCH -> model.search(state.query)
                        Screen.CATEGORIES -> model.loadCategories()
                        Screen.CATEGORY -> model.loadCategory()
                        Screen.RANKINGS -> model.loadRanking()
                        Screen.LIBRARY -> if (state.libraryTab == LibraryTab.CLOUD) model.loadFavourites() else model.loadHome()
                        Screen.DETAIL -> (state.comic?.id ?: state.requestedComicId)?.let { model.openComic(it, state.offline) }
                        else -> model.loadHome()
                    }
                }
            }
            when (state.screen) {
                Screen.HOME -> screenStates.SaveableStateProvider("home") { HomeFeedScreen(state, model) }
                Screen.CATEGORIES -> screenStates.SaveableStateProvider("categories") { CategoriesScreen(state, model) }
                Screen.RANKINGS -> screenStates.SaveableStateProvider("rankings") { RankingsScreen(state, model) }
                Screen.BLOCKING -> BlockingScreen(state, model)
                Screen.SETTINGS -> SettingsScreen(state, model)
                Screen.SEARCH -> screenStates.SaveableStateProvider("search") { SearchScreen(state, model) }
                Screen.CATEGORY -> CategoryScreen(state, model)
                Screen.LIBRARY -> LibraryScreen(state, model)
                Screen.PROFILE -> ProfileScreen(state, model)
                Screen.MY_COMMENTS -> MyCommentsScreen(state, model)
                Screen.LOGIN -> LoginScreen(state, model)
                Screen.REGISTER -> RegisterScreen(state, model)
                Screen.EDIT_PROFILE -> EditProfileScreen(state, model)
                Screen.DETAIL -> DetailScreen(state, model)
                Screen.COMMENTS -> screenStates.SaveableStateProvider("comments") { CommentsScreen(state, model) }
                else -> Unit
            }
        }
    }
    UserOverlays(state, model)
    InlineReplyComposer(state, model)
    }
}

@Composable
private fun BrandBar(state: UiState, onSearch: () -> Unit, onProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        UserAvatar(state.selfProfile ?: com.boneink.picapica.network.UserProfile("", "P"), Modifier.size(36.dp), onProfile, tag = "home-profile")
        Surface(onClick = onSearch, modifier = Modifier.weight(1f).height(40.dp).testTag("open-search"),
            color = MaterialTheme.colorScheme.surfaceContainerLow, shape = CircleShape, shadowElevation = PicaDepth.panel, border = panelBorder()) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("搜索漫画", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BottomNavigation(selected: Screen, onSelect: (Screen) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp, modifier = Modifier.shadow(PicaDepth.chrome).heightIn(min = 72.dp)) {
        val entries = listOf(Triple(Screen.HOME, Icons.Outlined.Home, Icons.Filled.Home), Triple(Screen.CATEGORIES, Icons.Outlined.Category, Icons.Filled.Category), Triple(Screen.LIBRARY, Icons.Outlined.LocalLibrary, Icons.Filled.LocalLibrary), Triple(Screen.PROFILE, Icons.Outlined.PersonOutline, Icons.Filled.Person))
        entries.forEachIndexed { index, (screen, outline, filled) ->
            val label = listOf("首页", "分类", "书架", "我的")[index]
            NavigationBarItem(selected = selected == screen, onClick = { onSelect(screen) }, modifier = Modifier.testTag("nav-${screen.name.lowercase()}"),
                icon = { Icon(if (selected == screen) filled else outline, label, Modifier.size(24.dp)) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun HomeFeedScreen(state: UiState, model: PicaViewModel) {
        LazyColumn(Modifier.fillMaxSize().testTag("home-list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {
            item {
                state.visibleComics(state.recent).firstOrNull()?.let { comic ->
                    Surface(onClick = { model.openComic(comic.id, offline = !state.signedIn) }, modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, shadowElevation = PicaDepth.panel, border = panelBorder()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Cover(comic, Modifier.size(30.dp, 40.dp), radius = 4.dp)
                            Column(Modifier.weight(1f)) {
                                Text("最近阅读", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(comic.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            }
                            Icon(Icons.Outlined.ArrowForward, "作品详情", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                SectionTitle("最新上架", if (state.signedIn) "刷新" else null, model::loadHome)
                HiddenComicsNotice(state.home.size - state.visibleComics(state.home).size) { model.navigate(Screen.BLOCKING) }
            }
            if (!state.signedIn) item { EmptyState("登录 PicACG", "", "登录账号") { model.navigate(Screen.LOGIN) } }
            else {
                items(state.visibleComics(state.home).chunked(2), key = { row -> row.first().id }) { row ->
                    ComicGrid(row) { model.openComic(it.id) }; Spacer(Modifier.height(12.dp))
                }
                if (state.homePage < state.homePages) item { OutlinedButton(onClick = { model.loadHomePage(true) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth().testTag("home-more")) { Text("加载更多") } }
                if (state.visibleComics(state.home).isEmpty() && !state.loading && state.error == null) item { EmptyState("暂无作品", "", "刷新", model::loadHome) }
            }
        }
}

@Composable
private fun DetailScreen(state: UiState, model: PicaViewModel) {
    val c = state.comic ?: return
    if (state.detailBlocked()) {
        EmptyState("该作品已被屏蔽", "可在设置中修改屏蔽规则。", "仍要查看") { model.revealComic() }
        return
    }
    var sheet by remember(c.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background))).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(c, Modifier.width(104.dp).height(146.dp).shadow(PicaDepth.card, MaterialTheme.shapes.medium), radius = 12.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(c.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (c.author.isNotBlank()) Text(c.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(listOfNotNull(c.completionLabel, (state.chapters.size.takeIf { it > 0 } ?: c.chapterCount).takeIf { it > 0 }?.let { "$it 话" }, c.pagesCount.takeIf { it > 0 }?.let { "$it 页" }).joinToString("  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (state.offline) Text("本地书架", style = MaterialTheme.typography.bodySmall)
                    }
                }
                ComicMetadataLines(c, Modifier.padding(horizontal = 16.dp), expanded = true)
                if (c.categories.isNotEmpty()) FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    c.categories.forEach { category -> QuietChip(category, false, Modifier.testTag("detail-category-$category"), onLongClick = { model.quickBlockCategory(category) }) { model.openSearch(category); model.search("", categories = setOf(category)) } }
                }
                if (c.tags.isNotEmpty()) FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    c.tags.forEach { tag -> QuietChip(tag, false, Modifier.testTag("detail-tag-$tag"), onLongClick = { model.quickBlockTag(tag) }) { model.openSearch(); model.search(tag) } }
                }
                if (!state.offline) FilterChip(selected = c.isFavourite, onClick = model::toggleFavourite, enabled = state.signedIn && !state.loading,
                    label = { Text(if (c.isFavourite) "已加入云收藏" else "加入云收藏") },
                    leadingIcon = { Icon(if (c.isFavourite) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp)) },
                    shape = RoundedCornerShape(20.dp), border = FilterChipDefaults.filterChipBorder(enabled = state.signedIn && !state.loading, selected = c.isFavourite, borderWidth = .5.dp),
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp).testTag("comic-favourite"))
                if (c.description.isNotBlank()) Text(c.description, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.offline) Surface(onClick = model::openComments, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).testTag("open-comments"),
                    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = PicaDepth.panel, border = panelBorder()) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("作品评论", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp))
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("章节目录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text("${state.chapters.size} 话", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.chapters, key = { it.order }) { chapter ->
                val downloaded = state.downloads.any { it.chapterKey == model.repo.config.chapterKey(c.id, chapter.order) && it.state == DownloadRecord.COMPLETE }
                ListItem(
                    headlineContent = { Text(chapter.title.ifBlank { "第 ${chapter.order} 话" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = { Text(if (downloaded) "已下载  可离线阅读" else "第 ${chapter.order} 话", style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Text("%02d".format(chapter.order), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { Icon(if (downloaded) Icons.Outlined.DownloadDone else Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = if (downloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                    modifier = Modifier.testTag("chapter-${chapter.order}").clickable { model.openChapter(chapter, state.offline || downloaded) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (state.chapters.isEmpty() && !state.loading) item { EmptyState("暂无章节") }
        }
        Surface(shadowElevation = PicaDepth.chrome, color = MaterialTheme.colorScheme.surface) {
            Column {
            HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = model::continueReading, enabled = state.chapters.isNotEmpty() && !state.loading,
                    modifier = Modifier.weight(1f).height(46.dp).testTag("read-continue"), shape = CircleShape) {
                    Icon(Icons.Outlined.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("开始阅读")
                }
                OutlinedButton(onClick = { sheet = true }, enabled = c.allowDownload && state.chapters.isNotEmpty() && state.signedIn,
                    modifier = Modifier.height(46.dp).testTag("download-chapters"), shape = CircleShape, border = androidx.compose.foundation.BorderStroke(.5.dp, MaterialTheme.colorScheme.outline)) { Icon(Icons.Outlined.Download, "离线下载", Modifier.size(21.dp)) }
            }
            }
        }
    }
    if (sheet) DownloadSheet(state.chapters, onDismiss = { sheet = false }) { selected -> model.download(selected); sheet = false }
}

@Composable
private fun DownloadSheet(chapters: List<Chapter>, onDismiss: () -> Unit, onDownload: (List<Chapter>) -> Unit) {
    var selected by remember { mutableStateOf(listOfNotNull(chapters.firstOrNull()?.order).toSet()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("下载章节", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = if (selected.size == chapters.size) emptySet() else chapters.map { it.order }.toSet() }) { Text(if (selected.size == chapters.size) "取消全选" else "全选") }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 330.dp)) {
                items(chapters, key = { it.order }) { chapter ->
                    Row(Modifier.fillMaxWidth().testTag("download-select-${chapter.order}").clickable { selected = if (chapter.order in selected) selected - chapter.order else selected + chapter.order }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = chapter.order in selected, onCheckedChange = { checked -> selected = if (checked) selected + chapter.order else selected - chapter.order })
                        Text(chapter.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
            Button(onClick = { onDownload(chapters.filter { it.order in selected }) }, enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp).height(50.dp).testTag("download-confirm"), shape = RoundedCornerShape(12.dp)) { Text("下载 ${selected.size} 个章节") }
        }
    }
}

@Composable
private fun LibraryScreen(state: UiState, model: PicaViewModel) {
    val selected = state.libraryTab.ordinal
    Column(Modifier.fillMaxSize()) {
        ChannelTabs(listOf("离线下载", "最近阅读", "云收藏"), selected,
            listOf("library-downloads", "library-history", "library-cloud")) { model.selectLibraryTab(LibraryTab.entries[it]) }
        if (selected == 2) FavouritesScreen(state, model) else LazyColumn(Modifier.fillMaxSize().testTag("library-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selected == 0) {
                if (state.downloads.isEmpty()) item { EmptyState("暂无下载", "可在作品详情中下载章节。") }
                items(state.downloads, key = { it.chapterKey }) { row ->
                    val complete = row.state == DownloadRecord.COMPLETE
                    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = PicaDepth.panel, border = panelBorder()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth().testTag("download-${row.remoteComicId}-${row.chapterOrder}").clickable(enabled = complete) { model.openDownloaded(row) },
                                horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                val metadata = state.knownComics[row.remoteComicId] ?: Comic(row.remoteComicId, row.comicTitle, coverUrl = row.coverUrl, completionKnown = false)
                                Cover(metadata, Modifier.size(50.dp, 68.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(row.comicTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    ComicMetadataLines(metadata)
                                    Text(row.chapterTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(when (row.state) {
                                        DownloadRecord.COMPLETE -> "已下载  ${row.totalPages} 页  离线可读"
                                        DownloadRecord.RUNNING -> "下载中  ${row.completedPages} / ${row.totalPages} 页"
                                        DownloadRecord.PAUSED -> "已暂停  ${row.completedPages} / ${row.totalPages} 页"
                                        DownloadRecord.FAILED -> "下载失败  点重试继续"
                                        else -> "等待下载  已保存 ${row.completedPages} 页"
                                    }, style = MaterialTheme.typography.bodySmall, color = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.testTag("download-status-${row.chapterOrder}"))
                                }
                                if (complete) Icon(Icons.Outlined.ChevronRight, "打开离线章节")
                            }
                            if (!complete) {
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(progress = { if (row.totalPages > 0) row.completedPages.toFloat() / row.totalPages else 0f }, modifier = Modifier.fillMaxWidth())
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (row.error != null) Text(row.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                                    if (row.state in setOf(DownloadRecord.RUNNING, DownloadRecord.QUEUED)) TextButton(onClick = { model.pause(row.chapterKey) }) { Text("暂停") }
                                    else TextButton(onClick = { model.resume(row.chapterKey) }, modifier = Modifier.testTag("download-resume-${row.chapterOrder}")) { Text(if (row.state == DownloadRecord.FAILED) "重试" else "继续") }
                                }
                            } else if (state.signedIn) {
                                if (row.error != null) Text(row.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { model.resume(row.chapterKey) }, modifier = Modifier.align(Alignment.End)) { Text("校验与修复", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            } else {
                item { HiddenComicsNotice(state.recent.size - state.visibleComics(state.recent).size) { model.navigate(Screen.BLOCKING) } }
                if (state.visibleComics(state.recent).isEmpty()) item { EmptyState("暂无阅读记录") }
                items(state.visibleComics(state.recent).chunked(2), key = { it.first().id }) { row -> ComicGrid(row) { model.openComic(it.id, offline = !state.signedIn) } }
            }
        }
    }
}

@Composable
private fun ProfileScreen(state: UiState, model: PicaViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = PicaDepth.card, border = panelBorder()) {
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f), MaterialTheme.colorScheme.surfaceContainerLow))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                UserAvatar(state.selfProfile ?: com.boneink.picapica.network.UserProfile("", state.name), Modifier.size(62.dp),
                    state.selfProfile?.let { user -> ({ model.showUser(user) }) }, tag = "profile-avatar")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (state.signedIn) state.name else "未登录", style = MaterialTheme.typography.headlineSmall)
                    state.selfProfile?.let { user ->
                        if (user.title.isNotBlank() && user.title != state.name) Text(user.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        user.level?.let { Text("Lv.$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
                if (!state.signedIn) FilledTonalButton(onClick = { model.navigate(Screen.LOGIN) }) { Text("登录") }
            }
            state.selfProfile?.let { user ->
                if (user.slogan.isNotBlank()) Text(user.slogan, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    user.exp?.let { Text("$it 经验", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)) }
                    if (user.exp == null) Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = model::editProfile, enabled = !state.profileLoading, border = panelBorder(), modifier = Modifier.testTag("profile-edit")) {
                        Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("编辑资料")
                    }
                }
            }
            if (state.profileLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.signedIn) {
                state.profileError?.let { error -> ErrorMessage(error) { model.loadSelfProfile() } }
                SectionTitle("最近评论", "全部") { model.openMyComments() }
                state.myCommentsError?.let { error -> ErrorMessage(error) { model.loadMyComments() } }
                state.myComments.take(3).forEach { comment -> RecentCommentCard(comment, state, model, compact = true) }
                if (state.myCommentsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.myComments.isEmpty() && !state.myCommentsLoading && state.myCommentsError == null) Text("还没有评论记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = model::openMyComments, modifier = Modifier.fillMaxWidth().testTag("profile-my-comments")) { Text("查看全部评论") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

}
