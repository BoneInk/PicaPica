package com.boneink.picapica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.boneink.picapica.network.*

@Composable
internal fun XiangxiangHomeScreen(state: UiState, model: PicaViewModel) {
    val feed = state.homeFeed
    val uriHandler = LocalUriHandler.current
    var selected by rememberSaveable { mutableStateOf("newest") }
    val tabs = feed?.sections.orEmpty().filter { it.key in setOf("newest", "siangsiang", "recommend") }
    val active = tabs.firstOrNull { it.key == selected } ?: tabs.firstOrNull()
    val known = remember(feed, state.knownComics) { feed?.sections.orEmpty().flatMap { it.comics }.associateBy(Comic::id) + state.knownComics }
    val banners = feed?.banners.orEmpty().filter { banner ->
        known[banner.comicId]?.let { !state.blockRules.blocks(it) } ?: (!state.blockRules.enabled || state.blockRules.count == 0)
    }
    LazyColumn(Modifier.fillMaxSize().testTag("home-list"), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietChip("分类", false, Modifier.weight(1f)) { model.tab(Screen.CATEGORIES) }
                QuietChip("排行榜", false, Modifier.weight(1f)) { model.openRankings() }
                QuietChip("签到", false, Modifier.weight(1f)) { model.tab(Screen.PROFILE) }
            }
        }
        if (!state.signedIn) item {
            EmptyState("登录香香腐宅", "", "登录账号") { model.navigate(Screen.LOGIN) }
            TextButton(onClick = { model.navigate(Screen.SETTINGS) }, modifier = Modifier.fillMaxWidth().testTag("home-select-site")) { Text("选择访问站点") }
        }
        else {
            if (banners.isNotEmpty()) item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(banners, key = HomeBanner::comicId) { banner ->
                        Surface(onClick = { model.openComic(banner.comicId) }, shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.width(310.dp).aspectRatio(2.6f).testTag("home-banner-${banner.comicId}")) {
                            AsyncImage(banner.imageUrl, "查看推荐作品", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            if (tabs.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(tabs, key = HomeSection::key) { section ->
                            QuietChip(section.title, active?.key == section.key, Modifier.testTag("home-tab-${section.key}")) { selected = section.key }
                        }
                    }
                    active?.let { HomeSectionStrip(it, state, model) }
                }
            }
            if (feed?.articles?.isNotEmpty() == true) item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("腐宅快报")
                    feed.articles.take(6).forEach { article ->
                        Column(Modifier.fillMaxWidth().testTag("home-article-${article.id}").clickable { uriHandler.openUri(article.url) }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(article.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (article.date.isNotBlank()) Text(article.date.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HairlineRule()
                    }
                }
            }
            feed?.sections.orEmpty().filter { it !in tabs }.forEach { section ->
                item(key = "section-${section.key}") { HomeSectionStrip(section, state, model) }
            }
            if (feed == null && !state.loading && state.error == null) item { EmptyState("首页暂时没有内容", "", "刷新", model::loadHome) }
            item { TextButton(onClick = model::loadHome, enabled = !state.loading, modifier = Modifier.fillMaxWidth().testTag("home-refresh")) { Text("刷新首页") } }
        }
    }
}

@Composable
private fun HomeSectionStrip(section: HomeSection, state: UiState, model: PicaViewModel) {
    val visible = rememberVisibleComics(state, section.comics)
    Column(Modifier.fillMaxWidth().testTag("home-section-${section.key}"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(horizontal = 16.dp)) { SectionTitle(section.title, "更多") { model.openHomeSection(section) } }
        if (visible.isEmpty()) Text("暂无可展示的作品", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(visible.take(10), key = Comic::id) { comic -> ComicCard(comic, Modifier.width(150.dp)) { model.openComic(comic.id) } }
        }
    }
}
