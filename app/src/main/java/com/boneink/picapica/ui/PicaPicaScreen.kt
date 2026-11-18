@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.boneink.picapica.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.boneink.picapica.data.DownloadRecord
import com.boneink.picapica.data.ChapterSort
import com.boneink.picapica.network.*

private data class BlockConfirmation(val title: String, val message: String, val confirm: () -> Unit)

private data class Destination(val screen: Screen, val label: String, val outline: ImageVector, val filled: ImageVector) {
    val tag = "nav-${screen.name.lowercase()}"
}

private val destinations = listOf(
    Destination(Screen.HOME, "首页", Icons.Outlined.Home, Icons.Filled.Home),
    Destination(Screen.CATEGORIES, "分类", Icons.Outlined.Category, Icons.Filled.Category),
    Destination(Screen.LIBRARY, "书架", Icons.Outlined.LocalLibrary, Icons.Filled.LocalLibrary),
    Destination(Screen.PROFILE, "我的", Icons.Outlined.PersonOutline, Icons.Filled.Person),
)

@Composable
fun PicaPicaScreen(model: PicaViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refreshVisibleAccountContent() }
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
    val topLevel = destinations.any { it.screen == state.screen }
    var blockConfirmation by remember(model, state.screen) { mutableStateOf<BlockConfirmation?>(null) }
    val actions = remember(model, state.screen) { QuickBlockActions(
        { comic -> blockConfirmation = BlockConfirmation("屏蔽这部作品？", "《${comic.title}》将从列表中隐藏。") { model.quickBlockComic(comic) } },
        { tag -> blockConfirmation = BlockConfirmation("屏蔽标签？", "带有“$tag”标签的作品将从列表中隐藏。") { model.quickBlockTag(tag) } },
        { category -> blockConfirmation = BlockConfirmation("屏蔽分类？", "“$category”分类的作品将从列表中隐藏。") { model.quickBlockCategory(category) } },
        { model.openSearch(); model.search(it) }, { if (model.capabilities.searchCategories) { model.openSearch(it); model.search("", categories = setOf(it)) } else model.showCategory(com.boneink.picapica.network.ComicCategory("xx-tag", it)) }) }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val wide = maxWidth >= WideLayoutMinWidth
        val splitDetail = maxWidth >= ExpandedLayoutMinWidth
        CompositionLocalProvider(LocalQuickBlocks provides actions, LocalWideLayout provides wide) {
            Row(Modifier.fillMaxSize()) {
                if (topLevel && wide) SideNavigation(state.screen, model::tab)
                AppScaffold(
                    state, model, snackbar, screenStates, wide, splitDetail, topLevel,
                    onBack = { backDispatcher?.onBackPressed() ?: model.goBack() },
                    modifier = Modifier.weight(1f),
                )
            }
            UserOverlays(state, model)
            InlineReplyComposer(state, model)
            blockConfirmation?.let { request ->
                AlertDialog(onDismissRequest = { blockConfirmation = null }, modifier = Modifier.testTag("block-confirmation"),
                    title = { Text(request.title) }, text = { Text(request.message) },
                    confirmButton = { TextButton(onClick = { blockConfirmation = null; request.confirm() }, modifier = Modifier.testTag("block-confirm")) { Text("屏蔽") } },
                    dismissButton = { TextButton(onClick = { blockConfirmation = null }, modifier = Modifier.testTag("block-cancel")) { Text("取消") } })
            }
        }
    }
}

@Composable
private fun AppScaffold(
    state: UiState,
    model: PicaViewModel,
    snackbar: SnackbarHostState,
    screenStates: SaveableStateHolder,
    wide: Boolean,
    splitDetail: Boolean,
    topLevel: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = state.error
    val showBottomNavigation = topLevel && !wide
    val contentWidth = when (state.screen) {
        Screen.HOME, Screen.CATEGORIES, Screen.LIBRARY, Screen.SEARCH, Screen.CATEGORY, Screen.RANKINGS -> 1240.dp
        Screen.DETAIL -> if (splitDetail) 1120.dp else 720.dp
        else -> 720.dp
    }
    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbar, modifier = if (showBottomNavigation) Modifier else Modifier.navigationBarsPadding()) },
        topBar = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val barWidth = Modifier.widthIn(max = contentWidth)
                if (state.screen == Screen.HOME) BrandBar(state, onSearch = { model.openSearch() }, onProfile = { model.tab(Screen.PROFILE) }, modifier = barWidth)
                else PageTopBar(
                    modifier = barWidth,
                    title = when (state.screen) {
                        Screen.ACCOUNTS -> "账号与数据源"
                        Screen.BLOCKING -> "内容屏蔽"
                        Screen.SETTINGS -> "设置"
                        Screen.SEARCH -> "搜索"
                        Screen.CATEGORIES -> "分类"
                        Screen.RANKINGS -> "排行榜"
                        Screen.CATEGORY -> state.selectedCategory?.title ?: "分类作品"
                        Screen.LIBRARY -> "我的书架"
                        Screen.PROFILE -> "我的"
                        Screen.MY_COMMENTS -> if (model.source == SourceKind.PICA) "我的评论" else model.capabilities.recentCommentsTitle
                        Screen.LOGIN -> "登录 ${model.source.title}"
                        Screen.REGISTER -> "注册账号"
                        Screen.EDIT_PROFILE -> "编辑个人资料"
                        Screen.COMMENTS -> if (state.isOfficialDiscussion && state.replyParent == null) "官方评论区" else if (state.replyParent?.parentId != null) "楼中楼回复" else if (state.replyParent != null) "评论回复" else "作品评论"
                        else -> "作品详情"
                    },
                    showBack = !topLevel,
                    backEnabled = !(state.loading && state.screen in setOf(Screen.REGISTER, Screen.EDIT_PROFILE)),
                    onBack = onBack,
                    actions = {
                        if (state.screen == Screen.CATEGORIES) {
                            IconButton(onClick = model::loadCategories, enabled = !state.categoriesLoading) { Icon(Icons.Outlined.Refresh, "刷新分类") }
                            IconButton(onClick = model::openRankings, modifier = Modifier.testTag("open-rankings")) { Icon(Icons.Outlined.Leaderboard, "排行榜") }
                        }
                        if (state.screen == Screen.RANKINGS) IconButton(onClick = { model.loadRanking() }, enabled = !state.loading) { Icon(Icons.Outlined.Refresh, "刷新") }
                        if (state.screen == Screen.PROFILE && state.signedIn) IconButton(onClick = model::loadSelfProfile, enabled = !state.profileLoading, modifier = Modifier.testTag("refresh-profile")) { Icon(Icons.Outlined.Refresh, "刷新个人资料") }
                        if (state.screen == Screen.PROFILE) IconButton(onClick = { model.navigate(Screen.SETTINGS) }, modifier = Modifier.testTag("open-settings")) { Icon(Icons.Outlined.Settings, "设置") }
                        if (state.screen == Screen.CATEGORY && model.capabilities.searchCategories) IconButton(onClick = { model.openSearch(state.selectedCategory?.title) }) { Icon(Icons.Outlined.Search, "在此分类中搜索") }
                        if (state.screen == Screen.COMMENTS && !state.isOfficialDiscussion && state.comic != null)
                            TextButton(onClick = model::openCommentWork, modifier = Modifier.testTag("comment-open-work")) { Text("作品详情", style = MaterialTheme.typography.bodySmall) }
                    },
                )
            }
        },
        bottomBar = { if (showBottomNavigation) BottomNavigation(state.screen, model::tab) },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = contentWidth).fillMaxSize()
                .then(if (!showBottomNavigation && state.screen !in setOf(Screen.DETAIL, Screen.COMMENTS)) Modifier.navigationBarsPadding() else Modifier)) {
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
                    Screen.ACCOUNTS -> AccountsScreen(model)
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
                    Screen.DETAIL -> DetailScreen(state, model, splitDetail)
                    Screen.COMMENTS -> screenStates.SaveableStateProvider("comments") { CommentsScreen(state, model) }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun BrandBar(state: UiState, onSearch: () -> Unit, onProfile: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PicaPica", style = MaterialTheme.typography.titleLarge.copy(letterSpacing = (-.6).sp), maxLines = 1)
        Surface(onClick = onSearch, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("open-search"),
            color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("搜索漫画", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        UserAvatar(state.selfProfile ?: UserProfile("", "P"), Modifier.size(40.dp), onProfile, tag = "home-profile")
    }
}

@Composable
private fun PageTopBar(title: String, showBack: Boolean, backEnabled: Boolean, onBack: () -> Unit,
    modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit) {
    Column(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = if (showBack) 4.dp else 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack, enabled = backEnabled) { Icon(Icons.Outlined.ArrowBack, "返回", Modifier.size(22.dp)) }
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            actions()
        }
    }
}

@Composable
private fun BottomNavigation(selected: Screen, onSelect: (Screen) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).navigationBarsPadding().testTag("bottom-navigation")) {
        HairlineRule()
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            destinations.forEach { destination ->
                val (screen, label, outline, filled) = destination
                val active = selected == screen
                val ink = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).testTag(destination.tag)
                    .selectable(active, role = Role.Tab, onClick = { onSelect(screen) })
                    .heightIn(min = 68.dp).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(Modifier.size(48.dp, 28.dp).clip(MaterialTheme.shapes.small)
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent), contentAlignment = Alignment.Center) {
                        Icon(if (active) filled else outline, null, Modifier.size(23.dp), tint = ink)
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = ink)
                }
            }
        }
    }
}

@Composable
private fun SideNavigation(selected: Screen, onSelect: (Screen) -> Unit) {
    NavigationRail(Modifier.testTag("side-navigation"), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            destinations.forEach { destination ->
                val (screen, label, outline, filled) = destination
                val active = selected == screen
                NavigationRailItem(
                    selected = active,
                    onClick = { onSelect(screen) },
                    icon = { Icon(if (active) filled else outline, null) },
                    label = { Text(label, maxLines = 1) },
                    modifier = Modifier.testTag(destination.tag),
                )
            }
        }
    }
}

@Composable
private fun HomeFeedScreen(state: UiState, model: PicaViewModel) {
    if (model.capabilities.homepage) { XiangxiangHomeScreen(state, model); return }
    val visibleHome = rememberVisibleComics(state, state.home)
    val visibleRecent = rememberVisibleComics(state, state.recent)
    LazyVerticalGrid(adaptiveColumns(), Modifier.fillMaxSize().testTag("home-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        fullWidthItem {
            visibleRecent.firstOrNull()?.let { comic ->
                Surface(onClick = { model.openComic(comic.id, offline = !state.signedIn) }, modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, shadowElevation = PicaDepth.panel, border = panelBorder()) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Cover(comic, Modifier.size(36.dp, 50.dp), radius = 2.dp)
                        Column(Modifier.weight(1f)) {
                            Text("最近阅读", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(comic.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                        Icon(Icons.Outlined.ArrowForward, "作品详情", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            SectionTitle("最新上架", if (state.signedIn) "刷新" else null, model::loadHome)
            HiddenComicsNotice(state.home.size - visibleHome.size) { model.navigate(Screen.BLOCKING) }
        }
        if (!state.signedIn) fullWidthItem { EmptyState("登录 ${model.source.title}", "", "登录账号") { model.navigate(Screen.LOGIN) } }
        else {
            comicItems(visibleHome) { model.openComic(it.id) }
            if (state.homePage < state.homePages) fullWidthItem { OutlinedButton(onClick = { model.loadHomePage(true) }, enabled = !state.loading, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().testTag("home-more")) { Text("加载更多") } }
            if (visibleHome.isEmpty() && !state.loading && state.error == null) fullWidthItem { EmptyState("暂无作品", "", "刷新", model::loadHome) }
        }
    }
}

@Composable
private fun DetailScreen(state: UiState, model: PicaViewModel, split: Boolean) {
    val c = state.comic ?: return
    if (state.detailBlocked()) {
        EmptyState("该作品已被屏蔽", "可在设置中修改屏蔽规则。", "仍要查看") { model.revealComic() }
        return
    }
    var sheet by remember(c.id) { mutableStateOf(false) }
    val displayedChapters = remember(state.chapters, state.chapterSort) {
        if (state.chapterSort == ChapterSort.DESCENDING) state.chapters.sortedByDescending { it.order } else state.chapters.sortedBy { it.order }
    }
    val onDownload = { sheet = true }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f)) {
            if (split) {
                Column(Modifier.weight(.9f).fillMaxHeight().testTag("detail-summary-pane")) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                        DetailSummary(c, state, model, split = true)
                    }
                    DetailActions(c, state, model, onDownload)
                }
                VerticalDivider(Modifier.fillMaxHeight(), thickness = .5.dp)
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight()
                .then(if (split) Modifier.navigationBarsPadding() else Modifier)
                .testTag("detail-chapters"), contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "chapter-header") {
                    Column {
                        if (!split) DetailSummary(c, state, model, split = false)
                        Row(Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("章节目录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Text("${state.chapters.size} 话", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = model::toggleChapterSort, enabled = state.chapters.size > 1, modifier = Modifier.padding(start = 8.dp).testTag("chapter-sort").semantics { stateDescription = state.chapterSort.title }) {
                                Icon(Icons.Outlined.SwapVert, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(state.chapterSort.title, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                items(displayedChapters, key = { it.order }) { chapter ->
                    val downloaded = state.downloads.any { it.chapterKey == model.repo.config.chapterKey(c.id, chapter.order) && it.state == DownloadRecord.COMPLETE }
                    val progress = state.chapterProgress[chapter.order]
                    val lastRead = state.lastRead?.chapterOrder == chapter.order
                    val readingStatus = when {
                        lastRead && progress?.completed == true -> "上次阅读  已读"
                        lastRead -> "上次阅读  第 ${(progress?.pageIndex ?: state.lastRead?.pageIndex ?: 0) + 1} 页"
                        progress?.completed == true -> "已读"
                        progress != null -> "读至第 ${progress.pageIndex + 1} 页"
                        else -> "未读"
                    }
                    ListItem(
                        headlineContent = { Text(chapter.title.ifBlank { "第 ${chapter.order} 话" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                            color = if (lastRead) MaterialTheme.colorScheme.primary else if (progress != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (lastRead) FontWeight.Medium else FontWeight.Normal) },
                        supportingContent = { Text(listOfNotNull(readingStatus.takeIf { progress != null || lastRead }, if (downloaded) "已下载  可离线阅读" else if (progress == null && !lastRead) "第 ${chapter.order} 话" else null).joinToString("  "),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("chapter-progress-${chapter.order}")) },
                        leadingContent = { Text("%02d".format(chapter.order), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingContent = { Icon(if (lastRead) Icons.Outlined.History else if (progress?.completed == true) Icons.Outlined.CheckCircleOutline else if (downloaded) Icons.Outlined.DownloadDone else Icons.Outlined.ChevronRight,
                            null, Modifier.size(20.dp), tint = if (lastRead || downloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                        colors = ListItemDefaults.colors(containerColor = if (lastRead) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f) else Color.Transparent),
                        modifier = Modifier.testTag("chapter-${chapter.order}").semantics { stateDescription = readingStatus }.clickable { model.openChapter(chapter, state.offline || downloaded) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (state.chapters.isEmpty() && !state.loading) item { EmptyState("暂无章节") }
            }
        }
        if (!split) DetailActions(c, state, model, onDownload)
    }
    if (sheet) DownloadSheet(state.chapters, onDismiss = { sheet = false }) { selected -> model.download(selected); sheet = false }
}

@Composable
private fun DetailSummary(c: Comic, state: UiState, model: PicaViewModel, split: Boolean) {
    val quickBlocks = LocalQuickBlocks.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(c, Modifier.width(if (split) 132.dp else 108.dp).aspectRatio(108f / 152f), radius = 8.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (c.author.isNotBlank()) Text(c.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(listOfNotNull(c.completionLabel, (state.chapters.size.takeIf { it > 0 } ?: c.chapterCount).takeIf { it > 0 }?.let { "$it 话" }, c.pagesCount.takeIf { it > 0 }?.let { "$it 页" }).joinToString("  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (state.offline) Text("本地书架", style = MaterialTheme.typography.bodySmall)
        }
    }
    HairlineRule(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
    ComicMetadataLines(c, Modifier.padding(horizontal = 16.dp), expanded = true)
    if (c.categories.isNotEmpty() && model.source != com.boneink.picapica.network.SourceKind.XIANGXIANG) FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        c.categories.forEach { category -> QuietChip(category, false, Modifier.testTag("detail-category-$category"), onLongClick = quickBlocks?.let { { it.category(category) } }) { if (model.capabilities.searchCategories) { model.openSearch(category); model.search("", categories = setOf(category)) } else model.showCategory(com.boneink.picapica.network.ComicCategory("xx-tag", category)) } }
    }
    if (c.tags.isNotEmpty()) FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        c.tags.forEach { tag -> QuietChip(tag, false, Modifier.testTag("detail-tag-$tag"), onLongClick = quickBlocks?.let { { it.tag(tag) } }) { model.openSearch(); model.search(tag) } }
    }
    if (!state.offline) FilterChip(selected = c.isFavourite, onClick = model::toggleFavourite, enabled = state.signedIn && !state.loading,
        label = { Text(if (c.isFavourite) "已加入云收藏" else "加入云收藏") },
        leadingIcon = { Icon(if (c.isFavourite) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp)) },
        shape = MaterialTheme.shapes.small, border = FilterChipDefaults.filterChipBorder(enabled = state.signedIn && !state.loading, selected = c.isFavourite, borderWidth = .5.dp),
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
}

@Composable
private fun DetailActions(c: Comic, state: UiState, model: PicaViewModel, onDownload: () -> Unit) {
    Surface(shadowElevation = PicaDepth.chrome, color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = model::continueReading, enabled = state.chapters.isNotEmpty() && !state.loading,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("read-continue"), shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Outlined.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (state.lastRead != null) "继续阅读" else "开始阅读")
                }
                OutlinedButton(onClick = onDownload, enabled = c.allowDownload && state.chapters.isNotEmpty() && state.signedIn,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("download-chapters"), shape = MaterialTheme.shapes.small, border = panelBorder()) { Icon(Icons.Outlined.Download, "离线下载", Modifier.size(21.dp)) }
            }
        }
    }
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
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp).heightIn(min = 50.dp).testTag("download-confirm"), shape = MaterialTheme.shapes.small) { Text("下载 ${selected.size} 个章节") }
        }
    }
}

@Composable
private fun LibraryScreen(state: UiState, model: PicaViewModel) {
    val visibleRecent = rememberVisibleComics(state, state.recent)
    val selected = state.libraryTab.ordinal
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        ChannelTabs(listOf("离线下载", "最近阅读", "云收藏"), selected,
            listOf("library-downloads", "library-history", "library-cloud")) { model.selectLibraryTab(LibraryTab.entries[it]) }
        when (state.libraryTab) {
            LibraryTab.CLOUD -> FavouritesScreen(state, model)
            LibraryTab.HISTORY -> LazyVerticalGrid(adaptiveColumns(), Modifier.fillMaxSize().testTag("library-list"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                fullWidthItem { HiddenComicsNotice(state.recent.size - visibleRecent.size) { model.navigate(Screen.BLOCKING) } }
                if (visibleRecent.isEmpty()) fullWidthItem { EmptyState("暂无阅读记录") }
                comicItems(visibleRecent) { model.openComic(it.id, offline = !state.signedIn) }
            }
            LibraryTab.DOWNLOADS -> LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("library-list"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            }
        }
    }
}

@Composable
private fun ProfileScreen(state: UiState, model: PicaViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLow, border = panelBorder()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
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
                if (!state.signedIn) FilledTonalButton(onClick = { model.navigate(Screen.LOGIN) }, shape = MaterialTheme.shapes.small) { Text("登录") }
            }
            TextButton(onClick = { model.navigate(Screen.ACCOUNTS) }, modifier = Modifier.fillMaxWidth().testTag("profile-accounts")) {
                Icon(Icons.Outlined.SwitchAccount, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("${model.source.title}  切换账号")
            }
            state.selfProfile?.let { user ->
                HairlineRule()
                if (user.slogan.isNotBlank()) Text(user.slogan, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ProfileStatistics(user)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (user.stats.isEmpty()) user.exp?.let { Text("$it 经验", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)) }
                    if (user.exp == null || user.stats.isNotEmpty()) Spacer(Modifier.weight(1f))
                    if (model.capabilities.editProfile) OutlinedButton(onClick = model::editProfile, enabled = !state.profileLoading, shape = MaterialTheme.shapes.small, border = panelBorder(), modifier = Modifier.testTag("profile-edit")) {
                        Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("编辑资料")
                    }
                }
            }
            if (state.signedIn) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val signedToday = state.checkIn?.signed == true && state.checkIn.date == java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()
                FilledTonalButton(onClick = model::checkIn, enabled = !signedToday && !state.checkInBusy && !state.checkInLoading,
                    shape = MaterialTheme.shapes.small, modifier = Modifier.testTag("profile-check-in")) {
                    Icon(if (signedToday) Icons.Outlined.CheckCircleOutline else Icons.Outlined.EventAvailable, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.checkInBusy) "正在签到" else if (state.checkInLoading) "读取签到状态" else if (signedToday) "今日已签到" else "每日签到")
                }
                state.checkIn?.monthDays?.let { Text("本月已签到 $it 天", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (state.profileLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.signedIn) state.profileError?.let { error -> ErrorMessage(error) { model.loadSelfProfile() } }
            if (state.signedIn) state.checkInError?.let { error -> ErrorMessage(error) { model.loadCheckInStatus() } }
            if (state.signedIn && model.source == SourceKind.XIANGXIANG) {
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                TextButton(onClick = { uriHandler.openUri(model.repo.config.apiBase.resolve("home/user/index").toString()) }, modifier = Modifier.fillMaxWidth()) { Text("网站账号管理") }
            }
            if (state.signedIn && model.capabilities.recentComments) {
                SectionTitle(model.capabilities.recentCommentsTitle, "全部") { model.openMyComments() }
                state.myCommentsError?.let { error -> ErrorMessage(error) { model.loadMyComments() } }
                state.myComments.take(3).forEach { comment -> RecentCommentCard(comment, state, model, compact = true) }
                if (state.myCommentsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.myComments.isEmpty() && !state.myCommentsLoading && state.myCommentsError == null) Text("还没有评论记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = model::openMyComments, modifier = Modifier.fillMaxWidth().testTag("profile-my-comments")) { Text(if (model.source == SourceKind.PICA) "查看全部评论" else "查看${model.capabilities.recentCommentsTitle}") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

}
