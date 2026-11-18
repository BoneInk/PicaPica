@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.boneink.picapica.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.boneink.picapica.network.*

@Composable
internal fun CategoriesScreen(state: UiState, model: PicaViewModel) {
    val quickBlocks = LocalQuickBlocks.current
    LazyVerticalGrid(adaptiveColumns(220.dp), Modifier.fillMaxSize().testTag("categories-list"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.categories.isEmpty() && !state.categoriesLoading && state.categoriesError == null) fullWidthItem { EmptyState("暂无分类", "", "刷新", model::loadCategories) }
        state.categoriesError?.let { error -> fullWidthItem { ErrorMessage(error) { model.loadCategories() } } }
        if (state.categoriesLoading) fullWidthItem { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        items(state.categories.distinctBy(ComicCategory::id), key = ComicCategory::id) { category ->
            Surface(Modifier.heightIn(min = 90.dp).testTag("category-${category.id}").combinedClickable(onClick = { model.showCategory(category) }, onLongClick = if (category.id == OfficialDiscussion.category.id) null else quickBlocks?.let { { it.category(category.title) } }, onLongClickLabel = "屏蔽此分类（需确认）"),
                shape = MaterialTheme.shapes.small, border = panelBorder(),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(category.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, color = if (category.id == OfficialDiscussion.category.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Icon(Icons.Outlined.ArrowForward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    if (category.id == OfficialDiscussion.category.id) Icon(Icons.Outlined.Forum, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    else if (category.coverUrl.isNotBlank()) AsyncImage(category.coverUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp, 62.dp).clip(MaterialTheme.shapes.small))
                }
            }
        }
    }
}

@Composable
internal fun CategoryScreen(state: UiState, model: PicaViewModel) {
    val visibleCategory = rememberVisibleComics(state, state.categoryComics)
    ComicCollection(visibleCategory, state.loading, state.error,
        header = { if (state.selectedHomeSection == null) SortSelector(state.categorySort, model.capabilities.comicSorts) { model.loadCategory(it) }; HiddenComicsNotice(state.categoryComics.size - visibleCategory.size) { model.navigate(Screen.BLOCKING) } },
        hasMore = state.categoryPage < state.categoryPages, onMore = { model.loadCategory(more = true) }, onOpen = { model.openComic(it.id) })
}

@Composable
internal fun RankingsScreen(state: UiState, model: PicaViewModel) {
    val visibleRanking = rememberVisibleComics(state, state.ranking)
    val ranks = remember(state.ranking) { state.ranking.mapIndexed { index, comic -> comic.id to index + 1 }.toMap() }
    LazyVerticalGrid(adaptiveColumns(), Modifier.fillMaxSize().testTag("rankings-list"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        fullWidthItem {
            HiddenComicsNotice(state.ranking.size - visibleRanking.size) { model.navigate(Screen.BLOCKING) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                model.capabilities.rankingPeriods.forEach { period ->
                    QuietChip(model.capabilities.rankingTitles[period] ?: period.title, period == state.rankingPeriod, Modifier.testTag("ranking-${period.code}")) { model.loadRanking(period) }
                }
            }
        }
        if (visibleRanking.isEmpty() && !state.loading && state.error == null) fullWidthItem { EmptyState("暂无排行") }
        comicItems(visibleRanking, ranks = ranks) { model.openComic(it.id) }
    }
}

@Composable
internal fun FavouritesScreen(state: UiState, model: PicaViewModel) {
    val visibleFavourites = rememberVisibleComics(state, state.favourites)
    if (!state.signedIn) { EmptyState("登录后查看收藏", "", "登录") { model.navigate(Screen.LOGIN) }; return }
    ComicCollection(visibleFavourites, state.loading, state.error, header = {
        ListHeader("我的云收藏", state.loading) { model.loadFavourites() }
        SortSelector(state.favouriteSort, model.capabilities.favouriteSorts) { model.loadFavourites(it) }
        HiddenComicsNotice(state.favourites.size - visibleFavourites.size) { model.navigate(Screen.BLOCKING) }
    }, hasMore = state.favouritePage < state.favouritePages, onMore = { model.loadFavourites(more = true) }, onOpen = { model.openComic(it.id) }, emptyTitle = "还没有云收藏", emptySubtitle = "可在作品详情中添加收藏。")
}

@Composable
private fun ComicCollection(comics: List<Comic>, loading: Boolean, error: String?, header: @Composable () -> Unit,
    hasMore: Boolean, onMore: () -> Unit, onOpen: (Comic) -> Unit,
    emptyTitle: String = "暂无作品", emptySubtitle: String = "") {
    LazyVerticalGrid(adaptiveColumns(), Modifier.fillMaxSize().testTag("collection-list"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        fullWidthItem { header() }
        if (comics.isEmpty() && !loading && error == null) fullWidthItem { EmptyState(emptyTitle, emptySubtitle) }
        comicItems(comics, onClick = onOpen)
        if (hasMore) fullWidthItem { OutlinedButton(onMore, enabled = !loading, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().testTag("collection-more")) { Text("加载更多") } }
    }
}

@Composable
private fun SortSelector(selected: ComicSort, sorts: List<ComicSort>, onSelect: (ComicSort) -> Unit) {
    if (sorts.size < 2) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        sorts.forEach { sort -> QuietChip(sort.title, selected == sort, Modifier.testTag("sort-${sort.code}")) { onSelect(sort) } }
    }
}

@Composable
private fun ListHeader(title: String, loading: Boolean, refresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = refresh, enabled = !loading) { Icon(Icons.Outlined.Refresh, "刷新") }
    }
}
