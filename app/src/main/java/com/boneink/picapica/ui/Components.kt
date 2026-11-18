@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.boneink.picapica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.boneink.picapica.PicaPicaApp
import com.boneink.picapica.network.*

@Composable
fun Cover(comic: Comic, modifier: Modifier = Modifier, radius: Dp = 8.dp, fitPortrait: Boolean = false) {
    val files = (LocalContext.current.applicationContext as PicaPicaApp).container.repo.files
    val revision by files.coverChanges.collectAsStateWithLifecycle()
    var source by remember(files, comic.coverUrl) { mutableStateOf<CoverSource?>(null) }
    LaunchedEffect(files, comic.coverUrl, revision) {
        val url = comic.coverUrl
        val local = withContext(Dispatchers.IO) { if (url.isBlank()) null else files.coverFile(url) }
        source = CoverSource(local ?: url, local != null)
    }
    val resolved = source
    var loaded by remember(comic.coverUrl, resolved) { mutableStateOf(false) }
    val shape = RoundedCornerShape(radius)
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
        .border(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f), shape)) {
        if (!loaded) CoverPlaceholder()
        if (resolved != null && comic.coverUrl.isNotBlank() && fitPortrait) AsyncImage(model = resolved.model, contentDescription = null,
            contentScale = ContentScale.Crop, alpha = .18f, modifier = Modifier.fillMaxSize())
        // Wait for local lookup before creating a remote request, including in offline mode.
        if (resolved != null && comic.coverUrl.isNotBlank()) AsyncImage(model = resolved.model, contentDescription = comic.title,
            onSuccess = { loaded = true }, onError = { loaded = false },
            contentScale = if (fitPortrait) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier.fillMaxSize().testTag(if (resolved.local && loaded) "offline-cover-loaded-${comic.id}" else if (resolved.local) "offline-cover-${comic.id}" else "remote-cover-${comic.id}"))
    }
}

private data class CoverSource(val model: Any, val local: Boolean)

@Composable
internal fun rememberVisibleComics(state: UiState, comics: List<Comic>): List<Comic> =
    remember(comics, state.knownComics, state.blockRules) { state.visibleComics(comics) }

@Composable
private fun CoverPlaceholder() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Image, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f))
    }
}

data class QuickBlockActions(
    val comic: (Comic) -> Unit, val tag: (String) -> Unit, val category: (String) -> Unit,
    val openTag: (String) -> Unit, val openCategory: (String) -> Unit,
)
val LocalQuickBlocks = staticCompositionLocalOf<QuickBlockActions?> { null }

@Composable
fun ComicCard(comic: Comic, modifier: Modifier = Modifier, rank: Int? = null, onClick: () -> Unit) {
    val actions = LocalQuickBlocks.current
    val hasMeta = comic.categories.isNotEmpty() || comic.tags.isNotEmpty() || comic.languageMark != null || comic.isAiGenerated
    val status = listOfNotNull(comic.completionLabel, comic.chapterCount.takeIf { it > 0 }?.let { "$it 话" }).joinToString("  ")
    Surface(
        modifier = modifier.testTag(if (rank == null) "comic-${comic.id}" else "ranked-${comic.id}")
            .combinedClickable(onClick = onClick, onLongClick = actions?.let { { it.comic(comic) } }, onLongClickLabel = "屏蔽这部作品（需确认）", role = Role.Button),
        shape = RectangleShape,
        color = Color.Transparent,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(.70f).clip(MaterialTheme.shapes.medium).testTag("card-media-${comic.id}")) {
                Cover(comic, Modifier.fillMaxSize(), radius = 0.dp)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Transparent,
                    .40f to Color.Transparent,
                    .63f to Color.Black.copy(alpha = .46f),
                    1f to Color.Black.copy(alpha = .88f),
                )))
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(comic.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold), color = Color.White,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("card-title-${comic.id}"))
                    if (comic.author.isNotBlank()) Text(comic.author, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = .3.sp),
                        color = Color.White.copy(alpha = .85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("card-author-${comic.id}"))
                }
                if (rank != null) Surface(Modifier.align(Alignment.TopStart).padding(8.dp), color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary, shape = MaterialTheme.shapes.extraSmall) {
                    Text("%02d".format(rank), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                if (status.isNotEmpty()) Surface(Modifier.align(Alignment.TopEnd).padding(8.dp),
                    color = Color.Black.copy(alpha = .65f), contentColor = Color.White, shape = MaterialTheme.shapes.extraSmall) {
                    Text(status, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = .4.sp),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            if (comic.latestChapter.isNotBlank()) Text(comic.latestChapter, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).testTag("card-latest-${comic.id}"))
            if (hasMeta) {
                ComicMetadataLines(comic, Modifier.padding(top = 8.dp, bottom = 4.dp), showStatus = false)
            }
        }
    }
}

@Composable
fun ComicMetadataLines(comic: Comic, modifier: Modifier = Modifier, expanded: Boolean = false, showStatus: Boolean = true) {
    Column(modifier.testTag("metadata-${comic.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showStatus && !expanded) {
            val status = listOfNotNull(comic.completionLabel, comic.chapterCount.takeIf { it > 0 }?.let { "$it 话" }, comic.pagesCount.takeIf { it > 0 }?.let { "$it 页" })
            if (status.isNotEmpty()) Text(status.joinToString("  "), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = .4.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val marks = listOfNotNull(comic.languageMark, if (comic.isAiGenerated) "AI 生成" else null)
        if (marks.isNotEmpty()) Text(marks.joinToString("  "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!expanded && comic.categories.isNotEmpty()) MetadataLabels(comic.categories, false, comic.id)
        if (!expanded && comic.tags.isNotEmpty()) MetadataLabels(comic.tags, true, comic.id)
        if (expanded && comic.chineseTeam.isNotBlank()) Text("汉化  ${comic.chineseTeam}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val counts = listOfNotNull(comic.likes?.let { "$it 赞" }, comic.views?.let { "$it 浏览" })
        if (expanded && counts.isNotEmpty()) Text(counts.joinToString("  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun LazyGridScope.comicItems(comics: List<Comic>, ranks: Map<String, Int> = emptyMap(), onClick: (Comic) -> Unit) {
    items(comics.distinctBy(Comic::id), key = Comic::id, contentType = { "comic" }) { comic ->
        ComicCard(comic, rank = ranks[comic.id]) { onClick(comic) }
    }
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(action, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.ArrowForward, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String = "", button: String? = null, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 38.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (button != null) OutlinedButton(onClick = onClick, modifier = Modifier.testTag("empty-action"), shape = MaterialTheme.shapes.small,
            border = panelBorder(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 9.dp)) {
            Text(button, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun ErrorMessage(message: String, action: String = "重试", onRetry: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("error-message"), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .62f),
        shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onRetry) { Text(action, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** Horizontally scrollable channels retain their selected state at large text sizes. */
@Composable
fun ChannelTabs(titles: List<String>, selected: Int, tags: List<String>, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            titles.forEachIndexed { index, title ->
                val active = selected == index
                Column(Modifier.testTag(tags[index]).selectable(active, role = Role.Tab, onClick = { onSelect(index) })
                    .widthIn(min = 48.dp).heightIn(min = 50.dp).padding(top = 13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = MaterialTheme.typography.titleSmall.copy(letterSpacing = .3.sp), fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(7.dp))
                    Box(Modifier.width(if (active) 22.dp else 0.dp).height(1.5.dp).background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent))
                }
            }
        }
        HairlineRule()
    }
}

@Composable
fun QuietChip(title: String, selected: Boolean, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    val borderColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .3f) else MaterialTheme.colorScheme.outlineVariant
    Box(modifier.heightIn(min = 44.dp).clip(shape).then(if (onLongClick == null) Modifier.selectable(selected, role = Role.Tab, onClick = onClick)
        else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "加入屏蔽规则（需确认）", role = Role.Tab).semantics { this.selected = selected })
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f) else Color.Transparent)
        .border(.7.dp, borderColor, shape)
        .padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = .2.sp), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun HiddenComicsNotice(hidden: Int, onRules: () -> Unit) {
    if (hidden <= 0) return
    Row(Modifier.fillMaxWidth().testTag("hidden-comics-notice"), verticalAlignment = Alignment.CenterVertically) {
        Text("已屏蔽 $hidden 部作品", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        TextButton(onClick = onRules, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("屏蔽规则", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun MetadataLabels(labels: List<String>, tag: Boolean, comicId: String) {
    val actions = LocalQuickBlocks.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        labels.forEach { label ->
            Text(if (tag) "#$label" else label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = .2.sp),
                color = if (tag) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .82f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("${if (tag) "tag" else "category-label"}-$comicId-$label").combinedClickable(
                    onClick = { if (tag) actions?.openTag?.invoke(label) else actions?.openCategory?.invoke(label) },
                    onLongClick = { if (tag) actions?.tag?.invoke(label) else actions?.category?.invoke(label) }, onLongClickLabel = "屏蔽${if (tag) "标签" else "分类"}：$label"))
        }
    }
}
