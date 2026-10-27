@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.boneink.picapica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.boneink.picapica.PicaPicaApp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.boneink.picapica.network.*

@Composable
fun Cover(comic: Comic, modifier: Modifier = Modifier, radius: androidx.compose.ui.unit.Dp = 8.dp, fitPortrait: Boolean = false) {
    val files = (LocalContext.current.applicationContext as PicaPicaApp).container.repo.files
    val revision by files.coverChanges.collectAsStateWithLifecycle()
    val local = remember(comic.coverUrl, revision) { files.coverFile(comic.coverUrl) }
    var loaded by remember(comic.coverUrl, local) { mutableStateOf(false) }
    Box(modifier.clip(RoundedCornerShape(radius)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (!fitPortrait || comic.coverUrl.isBlank()) CoverArt(comic.title)
        if (fitPortrait && comic.coverUrl.isNotBlank()) AsyncImage(model = local ?: comic.coverUrl, contentDescription = null,
            contentScale = ContentScale.Crop, alpha = .18f, modifier = Modifier.fillMaxSize())
        if (comic.coverUrl.isNotBlank()) SubcomposeAsyncImage(model = local ?: comic.coverUrl, contentDescription = comic.title,
            onSuccess = { loaded = true }, onError = { loaded = false },
            contentScale = if (fitPortrait) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag(if (local != null && loaded) "offline-cover-loaded-${comic.id}" else if (local != null) "offline-cover-${comic.id}" else "remote-cover-${comic.id}"), loading = {}, error = { CoverArt(comic.title) })
    }
}
@Composable
private fun CoverArt(title: String) {
    val palette = listOf(Color(0xFF0D74CE), Color(0xFFCB1D63), Color(0xFF5753C6)) // Radix Blue, Crimson, Iris 11
    val color = palette[(title.hashCode().toLong().and(0x7fffffff) % palette.size).toInt()]
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(color.copy(alpha = .6f), color)))) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(0xFFFFE629).copy(alpha = .7f), size.width * .16f, Offset(size.width * .28f, size.height * .23f))
            rotate(26f, Offset(size.width * .8f, size.height)) {
                drawRect(color.copy(alpha = .6f), Offset(size.width * .48f, size.height * .35f), Size(size.width, size.height))
                drawRect(Color.White.copy(alpha = .18f), Offset(size.width * .37f, size.height * .35f), Size(10.dp.toPx(), size.height))
            }
        }
        Text(title.take(10), color = Color.White, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
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
    Card(modifier = modifier.testTag(if (rank == null) "comic-${comic.id}" else "ranked-${comic.id}")
        .combinedClickable(onClick = onClick, onLongClick = actions?.let { { it.comic(comic) } }, onLongClickLabel = "屏蔽这部作品", role = Role.Button), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.70f).testTag("card-media-${comic.id}")) {
            Cover(comic, Modifier.fillMaxSize(), radius = 0.dp)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .48f to Color.Transparent, .73f to Color(0xFF1C2024).copy(alpha = .72f), 1f to Color(0xFF1C2024).copy(alpha = .96f))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(comic.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("card-title-${comic.id}"))
                if (comic.author.isNotBlank()) Text(comic.author, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .85f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("card-author-${comic.id}"))
            }
            if (rank != null) Surface(Modifier.align(Alignment.TopStart).padding(8.dp), color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(6.dp)) {
                Text("%02d".format(rank), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
            }
            if (comic.completionLabel != null || comic.chapterCount > 0) Surface(Modifier.align(Alignment.TopEnd).padding(8.dp),
                color = Color(0xFF1C2024).copy(alpha = .8f), contentColor = Color.White, shape = RoundedCornerShape(6.dp)) {
                Text(listOfNotNull(comic.completionLabel, comic.chapterCount.takeIf { it > 0 }?.let { "$it 话" }).joinToString("  "), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
        if (comic.categories.isNotEmpty() || comic.tags.isNotEmpty() || comic.languageMark != null || comic.isAiGenerated)
            ComicMetadataLines(comic, Modifier.padding(10.dp), showStatus = false)
    }
}

@Composable
fun ComicMetadataLines(comic: Comic, modifier: Modifier = Modifier, expanded: Boolean = false, showStatus: Boolean = true) {
    Column(modifier.testTag("metadata-${comic.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showStatus && !expanded) {
            val status = listOfNotNull(comic.completionLabel, comic.chapterCount.takeIf { it > 0 }?.let { "$it 话" }, comic.pagesCount.takeIf { it > 0 }?.let { "$it 页" })
            if (status.isNotEmpty()) Text(status.joinToString("  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
@Composable
fun ComicGrid(items: List<Comic>, columns: Int = 2, ranks: Map<String, Int> = emptyMap(), onClick: (Comic) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { c -> ComicCard(c, Modifier.weight(1f), rank = ranks[c.id]) { onClick(c) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
@Composable
fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) {
            Text(action, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
fun EmptyState(title: String, subtitle: String = "", button: String? = null, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 35.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.AutoStories, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (button != null) FilledTonalButton(onClick = onClick, modifier = Modifier.testTag("empty-action")) { Text(button) }
    }
}
@Composable
fun ErrorMessage(message: String, action: String = "重试", onRetry: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("error-message"), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onRetry) { Text(action, color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}
/** Text channels with an underline; horizontal scrolling also supports large system fonts. */
@Composable
fun ChannelTabs(titles: List<String>, selected: Int, tags: List<String>, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        titles.forEachIndexed { index, title ->
            val active = selected == index
            Column(Modifier.testTag(tags[index]).selectable(active, role = Role.Tab, onClick = { onSelect(index) }).widthIn(min = 48.dp).heightIn(min = 48.dp).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(9.dp))
                Box(Modifier.width(18.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent))
            }
        }
    }
}

@Composable
fun QuietChip(title: String, selected: Boolean, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(18.dp)).then(if (onLongClick == null) Modifier.selectable(selected, role = Role.Tab, onClick = onClick)
        else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "加入屏蔽规则", role = Role.Tab).semantics { this.selected = selected })
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
        .padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun HiddenComicsNotice(hidden: Int, onRules: () -> Unit) {
    if (hidden <= 0) return
    Row(Modifier.fillMaxWidth().testTag("hidden-comics-notice"), verticalAlignment = Alignment.CenterVertically) {
        Text("已屏蔽 $hidden 部作品", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = onRules) { Text("屏蔽规则", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun MetadataLabels(labels: List<String>, tag: Boolean, comicId: String) {
    val actions = LocalQuickBlocks.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { label ->
            Text(if (tag) "#$label" else label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("${if (tag) "tag" else "category-label"}-$comicId-$label").combinedClickable(
                    onClick = { if (tag) actions?.openTag?.invoke(label) else actions?.openCategory?.invoke(label) },
                    onLongClick = { if (tag) actions?.tag?.invoke(label) else actions?.category?.invoke(label) }, onLongClickLabel = "屏蔽${if (tag) "标签" else "分类"}：$label"))
        }
    }
}
