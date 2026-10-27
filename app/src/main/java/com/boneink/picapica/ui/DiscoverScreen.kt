package com.boneink.picapica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.boneink.picapica.network.*

@Composable
internal fun CategoriesScreen(state: UiState, model: PicaViewModel) {
    LazyColumn(Modifier.fillMaxSize().testTag("categories-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ListHeader("分类", state.categoriesLoading, model::loadCategories) }
        if (state.categories.isEmpty() && !state.categoriesLoading && state.categoriesError == null) item { EmptyState("暂无分类", "", "刷新", model::loadCategories) }
        state.categoriesError?.let { error -> item { ErrorMessage(error) { model.loadCategories() } } }
        if (state.categoriesLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        items(state.categories.chunked(2), key = { it.first().id }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { category ->
                    Surface(Modifier.weight(1f).heightIn(min = 90.dp).testTag("category-${category.id}").clickable { model.showCategory(category) },
                        shape = RoundedCornerShape(10.dp), color = if (category.id == OfficialDiscussion.category.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(category.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, color = if (category.id == OfficialDiscussion.category.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Icon(Icons.Outlined.ArrowForward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            if (category.id == OfficialDiscussion.category.id) Icon(Icons.Outlined.Forum, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                            else if (category.coverUrl.isNotBlank()) AsyncImage(category.coverUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp, 62.dp).clip(RoundedCornerShape(5.dp)))
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun CategoryScreen(state: UiState, model: PicaViewModel) {
    ComicCollection(state.visibleComics(state.categoryComics), state.loading, state.error,
        header = { SortSelector(state.categorySort) { model.loadCategory(it) }; HiddenComicsNotice(state.categoryComics.size - state.visibleComics(state.categoryComics).size) { model.navigate(Screen.BLOCKING) } },
        hasMore = state.categoryPage < state.categoryPages, onMore = { model.loadCategory(more = true) }, onOpen = { model.openComic(it.id) })
}

@Composable
internal fun RankingsScreen(state: UiState, model: PicaViewModel) {
    LazyColumn(Modifier.fillMaxSize().testTag("rankings-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ListHeader("排行榜", state.loading) { model.loadRanking() }
            HiddenComicsNotice(state.ranking.size - state.visibleComics(state.ranking).size) { model.navigate(Screen.BLOCKING) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RankingPeriod.entries.forEach { period ->
                    QuietChip(period.title, period == state.rankingPeriod, Modifier.testTag("ranking-${period.code}")) { model.loadRanking(period) }
                }
            }
        }
        if (state.visibleComics(state.ranking).isEmpty() && !state.loading && state.error == null) item { EmptyState("暂无排行") }
        itemsIndexed(state.visibleComics(state.ranking), key = { _, comic -> comic.id }) { _, comic ->
            val index = state.ranking.indexOfFirst { it.id == comic.id }
            Surface(Modifier.fillMaxWidth().testTag("ranked-${comic.id}").clickable { model.openComic(comic.id) }, shape = RoundedCornerShape(8.dp), color = if (index < 3) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("%02d".format(index + 1), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = if (index < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Cover(comic, Modifier.size(60.dp, 84.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(comic.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        ComicMetadataLines(comic)
                        if (comic.author.isNotBlank()) Text(comic.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun FavouritesScreen(state: UiState, model: PicaViewModel) {
    if (!state.signedIn) { EmptyState("登录后查看收藏", "", "登录") { model.navigate(Screen.LOGIN) }; return }
    ComicCollection(state.visibleComics(state.favourites), state.loading, state.error, header = {
        ListHeader("我的云收藏", state.loading) { model.loadFavourites() }
        SortSelector(state.favouriteSort) { model.loadFavourites(it) }
        HiddenComicsNotice(state.favourites.size - state.visibleComics(state.favourites).size) { model.navigate(Screen.BLOCKING) }
    }, hasMore = state.favouritePage < state.favouritePages, onMore = { model.loadFavourites(more = true) }, onOpen = { model.openComic(it.id) }, emptyTitle = "还没有云收藏", emptySubtitle = "可在作品详情中添加收藏。")
}

@Composable
private fun ComicCollection(comics: List<Comic>, loading: Boolean, error: String?, header: @Composable () -> Unit,
    hasMore: Boolean, onMore: () -> Unit, onOpen: (Comic) -> Unit,
    emptyTitle: String = "暂无作品", emptySubtitle: String = "") {
    LazyColumn(Modifier.fillMaxSize().testTag("collection-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { header() }
        if (comics.isEmpty() && !loading && error == null) item { EmptyState(emptyTitle, emptySubtitle) }
        items(comics.chunked(2), key = { it.first().id }) { row -> ComicGrid(row, onClick = onOpen) }
        if (hasMore) item { OutlinedButton(onMore, enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("collection-more")) { Text("加载更多") } }
    }
}

@Composable
private fun SortSelector(selected: ComicSort, onSelect: (ComicSort) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        ComicSort.entries.forEach { sort -> QuietChip(sort.title, selected == sort, Modifier.testTag("sort-${sort.code}")) { onSelect(sort) } }
    }
}

@Composable
private fun ListHeader(title: String, loading: Boolean, refresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = refresh, enabled = !loading) { Icon(Icons.Outlined.Refresh, "刷新") }
    }
}
