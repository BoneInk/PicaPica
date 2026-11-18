@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.boneink.picapica.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import com.boneink.picapica.MainActivity
import com.boneink.picapica.data.ReaderOrientation
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.saveable.rememberSaveable
import com.boneink.picapica.data.ReadingMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.boneink.picapica.network.PageImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private data class ReaderSlot(val section: ReaderChapter, val pageIndex: Int, val chapterStart: Int) {
    val page get() = section.pages[pageIndex]
    val order get() = section.chapter.order
    val pageCount get() = section.pages.size
}

private fun spreadPages(spread: Int, pagesPerSpread: Int, pageCount: Int): IntRange {
    val first = spread * pagesPerSpread
    return first until minOf(first + pagesPerSpread, pageCount)
}

@Composable
fun ReaderScreen(state: UiState, model: PicaViewModel) {
    val comic = state.comic
    val chapter = state.chapter
    var chrome by rememberSaveable { mutableStateOf(true) }
    var topChromeHeight by remember { mutableIntStateOf(0) }
    var bottomChromeHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val view = LocalView.current
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
    DisposableEffect(activity) {
        val oldOrientation = activity?.requestedOrientation
        val oldBrightness = activity?.window?.attributes?.screenBrightness
        val oldAwake = activity?.window?.attributes?.flags?.and(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        onDispose {
            if (oldAwake) activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (oldBrightness != null) activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = oldBrightness } }
            if (oldOrientation != null && activity?.isChangingConfigurations == false) activity.requestedOrientation = oldOrientation
        }
    }
    LaunchedEffect(activity, state.preferences.orientation, state.preferences.brightness, state.preferences.keepScreenOn) {
        activity?.requestedOrientation = when (state.preferences.orientation) {
            ReaderOrientation.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ReaderOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ReaderOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = state.preferences.brightness } }
        if (state.preferences.keepScreenOn) activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    DisposableEffect(Unit) {
        val activity = view.context as? Activity
        val window = activity?.window
        val oldBackground = window?.decorView?.background
        val oldContrast = if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced else null
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF111113.toInt()))
        if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced = false
        onDispose {
            window?.setBackgroundDrawable(oldBackground)
            if (android.os.Build.VERSION.SDK_INT >= 29 && oldContrast != null) window?.isNavigationBarContrastEnforced = oldContrast
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            controller?.show(WindowInsetsCompat.Type.systemBars())
            val dark = view.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            controller?.isAppearanceLightStatusBars = !dark
            controller?.isAppearanceLightNavigationBars = !dark
        }
    }
    SideEffect {
        val controller = (view.context as? Activity)?.let { WindowCompat.getInsetsController(it.window, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        if (chrome) controller?.show(WindowInsetsCompat.Type.systemBars()) else controller?.hide(WindowInsetsCompat.Type.systemBars())
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF111113)).testTag("reader")) {
        val wide = maxWidth >= WideLayoutMinWidth
        val landscapeControls = wide && maxWidth > maxHeight
        val horizontalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
        val pageWidth = if (wide) Modifier.widthIn(max = 720.dp) else Modifier
        val fullViewport = Modifier.fillMaxSize().windowInsetsPadding(horizontalInsets)
        val viewport = fullViewport.padding(
            top = if (chrome) with(density) { topChromeHeight.toDp() } else 0.dp,
            bottom = if (chrome) with(density) { bottomChromeHeight.toDp() } else 0.dp,
        )
        if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = PicaAccent)
        else if (state.error != null) Column(Modifier.align(Alignment.Center).padding(25.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.error, color = Color.White)
            TextButton(onClick = { chapter?.let { model.openChapter(it) } }) { Text("重试", color = PicaAccent) }
            TextButton(onClick = model::goBack) { Text("返回书架", color = Color.White) }
        }
        if (state.images.isNotEmpty() && state.readerChapters.isNotEmpty() && comic != null && chapter != null) key(comic.id, state.readerSessionId, state.offline, state.readingMode) {
            val horizontal = state.readingMode == ReadingMode.HORIZONTAL
            val doublePage = horizontal && state.preferences.doublePage
            val pagesPerSpread = if (doublePage) 2 else 1
            val horizontalOrder = chapter.order.takeIf { horizontal }
            val slots = remember(state.readerChapters, horizontalOrder) {
                buildList {
                    state.readerChapters.filter { horizontalOrder == null || it.chapter.order == horizontalOrder }.forEach { section ->
                        val start = size
                        section.pages.indices.forEach { add(ReaderSlot(section, it, start)) }
                    }
                }
            }
            val initial = remember { slots.indexOfFirst { it.order == chapter.order && it.pageIndex == state.initialPage }.coerceAtLeast(0) }
            val list = rememberLazyListState(initialFirstVisibleItemIndex = initial)
            // Keep the comic page as the anchor when the pager changes between singles and pairs.
            var horizontalAnchor by remember { mutableIntStateOf(initial) }
            val pager = key(pagesPerSpread) {
                rememberPagerState(initialPage = horizontalAnchor / pagesPerSpread,
                    pageCount = { (slots.size + pagesPerSpread - 1) / pagesPerSpread })
            }
            val scope = rememberCoroutineScope()
            val latestSlots by rememberUpdatedState(slots)
            fun position(): Int {
                val lastIndex = latestSlots.lastIndex.coerceAtLeast(0)
                if (!horizontal) return list.firstVisibleItemIndex.coerceIn(0, lastIndex)
                val spread = pager.settledPage.coerceIn(0, lastIndex / pagesPerSpread)
                val visible = spreadPages(spread, pagesPerSpread, lastIndex + 1)
                return horizontalAnchor.takeIf { it in visible } ?: visible.last
            }
            val slotIndex by remember(pager, list) { derivedStateOf { position() } }
            val active = slots[slotIndex]
            val index = active.pageIndex
            fun recordAt(at: Int, persist: Boolean) {
                latestSlots.getOrNull(at)?.let { slot ->
                    val lastPageVisible = pagesPerSpread == 2 && slot.pageIndex / pagesPerSpread == (slot.pageCount - 1) / pagesPerSpread
                    model.recordReaderPosition(comic.id, state.readerSessionId, slot.order, slot.pageIndex, slot.pageCount, persist, lastPageVisible)
                }
            }
            val autoNext = state.preferences.autoLoadNextChapter
            val next = state.nextReaderChapter()
            val scrolling = if (horizontal) pager.isScrollInProgress else list.isScrollInProgress
            var zoomedSpread by remember(pager) { mutableIntStateOf(-1) }
            DisposableEffect(list, pager, state.preferences.volumePaging) {
                activity?.readerVolumeHandler = if (state.preferences.volumePaging) ({ delta -> scope.launch {
                    if (horizontal) {
                        if (!pager.isScrollInProgress) {
                            val current = pager.settledPage
                            val target = (current + delta).coerceIn(0, pager.pageCount - 1)
                            if (target != current) {
                                zoomedSpread = -1
                                pager.scrollToPage(target)
                            }
                        }
                    } else if (!list.isScrollInProgress) {
                        val target = (position() + delta).coerceIn(0, latestSlots.lastIndex)
                        if (target != position()) list.scrollToItem(target)
                    }
                }; Unit }) else null
                onDispose { activity?.readerVolumeHandler = null }
            }
            var slider by remember { mutableFloatStateOf(state.initialPage.toFloat()) }
            var dragging by remember { mutableStateOf(false) }
            LaunchedEffect(list, pager) {
                snapshotFlow { position() }.collectLatest { value ->
                    if (horizontal) horizontalAnchor = value
                    recordAt(value, false)
                    delay(300)
                    recordAt(value, true)
                }
            }
            LaunchedEffect(list, slots.size, autoNext, state.loading) {
                if (!horizontal && autoNext && !state.loading) {
                    snapshotFlow {
                        val layout = list.layoutInfo
                        val lastPage = layout.visibleItemsInfo.lastOrNull { it.index == slots.lastIndex }
                        val contentEnd = layout.viewportEndOffset - layout.afterContentPadding
                        val margin = (contentEnd - layout.viewportStartOffset - layout.beforeContentPadding).coerceAtLeast(0) / 2
                        lastPage != null && lastPage.offset + lastPage.size <= contentEnd + margin
                    }.collect { nearEnd ->
                        if (nearEnd) model.loadNextReaderChapter()
                    }
                }
            }
            DisposableEffect(list, pager) { onDispose { recordAt(position(), true) } }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { recordAt(position(), true) }
            if (horizontal) HorizontalPager(state = pager,
                userScrollEnabled = zoomedSpread != pager.currentPage,
                modifier = (if (doublePage) fullViewport else viewport).testTag("reader-pager")) { spread ->
                val visible = spreadPages(spread, pagesPerSpread, slots.size)
                val active = pager.currentPage == spread
                val onZoom: (Boolean) -> Unit = { zoomed ->
                    if (zoomed && pager.currentPage == spread) zoomedSpread = spread
                    else if (!zoomed && zoomedSpread == spread) zoomedSpread = -1
                }
                if (doublePage) {
                    FullHeightSpread(slots, visible, model, active, onZoom, onTap = { chrome = !chrome })
                } else {
                    ReaderImage(slots[spread].page, spread, model, horizontal = true, active = active,
                        onZoom = onZoom, onTap = { chrome = !chrome })
                }
            } else LazyColumn(state = list, modifier = viewport.testTag("reader-pages"),
                contentPadding = PaddingValues(bottom = if (chrome) 0.dp else 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                itemsIndexed(slots, key = { _, slot -> "${slot.order}:${slot.pageIndex}" }) { i, slot ->
                    ReaderImage(slot.page, slot.pageIndex, model, modifier = pageWidth, tagIndex = i, onTap = { chrome = !chrome })
                }
                if (!autoNext || next == null || state.nextChapterLoading || state.nextChapterError != null) item(key = "chapter-end") {
                    Column(Modifier.fillMaxWidth().padding(15.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        when {
                            next == null -> Text(if (state.offline) "没有更多离线章节" else "已到最后一章", color = Color(0xFFB0B4BA), modifier = Modifier.testTag("reader-end"))
                            !autoNext -> TextButton(onClick = { recordAt(position(), true); model.openChapter(next) }, modifier = Modifier.testTag("reader-manual-next")) { Text("下一章 →", color = PicaAccent) }
                            state.nextChapterLoading -> CircularProgressIndicator(Modifier.size(24.dp).testTag("reader-next-loading"), strokeWidth = 2.dp, color = PicaAccent)
                            state.nextChapterError != null -> {
                                Text(state.nextChapterError, color = Color(0xFFB0B4BA))
                                TextButton(onClick = { model.loadNextReaderChapter(retry = true) }, modifier = Modifier.testTag("reader-next-retry")) { Text("重试", color = PicaAccent) }
                            }
                        }
                    }
                }
            }
            if (chrome) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomChromeHeight = it.height }, color = Color(0xF018191B), contentColor = Color.White) {
                Box(Modifier.fillMaxWidth().windowInsetsPadding(horizontalInsets), contentAlignment = Alignment.Center) {
                    Column(pageWidth.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = if (landscapeControls) 4.dp else 10.dp).testTag("reader-controls")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val hasPrevious = state.chapters.any { it.order < active.order }
                            val hasNext = state.chapters.any { it.order > active.order }
                            TextButton(onClick = { recordAt(position(), true); model.adjacentChapter(-1) }, enabled = hasPrevious) { Text("上一章", color = if (hasPrevious) Color.White else Color.Gray) }
                            val visible = spreadPages(index / pagesPerSpread, pagesPerSpread, active.pageCount)
                            val pagesLabel = if (visible.first == visible.last) "${index + 1}" else "${visible.first + 1}–${visible.last + 1}"
                            Text("$pagesLabel / ${active.pageCount}", style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center, modifier = Modifier.weight(1f).testTag("reader-position"))
                            TextButton(onClick = { recordAt(position(), true); model.adjacentChapter(1) }, enabled = hasNext) { Text("下一章", color = if (hasNext) Color.White else Color.Gray) }
                        }
                        val modeControls: @Composable () -> Unit = {
                            Row(horizontalArrangement = Arrangement.Center) {
                                ReadingMode.entries.forEach { mode ->
                                    TextButton(onClick = { if (mode != state.readingMode) { recordAt(position(), true); model.setReadingMode(mode) } },
                                        enabled = !scrolling, modifier = Modifier.testTag("reader-mode-${mode.name.lowercase()}")) {
                                        Icon(if (mode == ReadingMode.HORIZONTAL) Icons.Outlined.Swipe else Icons.Outlined.SwapVert, null, Modifier.size(17.dp), tint = if (mode == state.readingMode) PicaAccent else Color(0xFFB0B4BA))
                                        Spacer(Modifier.width(6.dp))
                                        Text(mode.title, color = if (mode == state.readingMode) PicaAccent else Color(0xFFB0B4BA), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Slider(value = if (dragging) slider else index.toFloat(),
                                onValueChange = { dragging = true; slider = it }, valueRange = 0f..(active.pageCount - 1).coerceAtLeast(1).toFloat(),
                                onValueChangeFinished = { scope.launch {
                                    val target = slider.toInt().coerceIn(0, active.pageCount - 1)
                                    if (horizontal) {
                                        pager.scrollToPage(target / pagesPerSpread)
                                        horizontalAnchor = target
                                    } else list.scrollToItem(active.chapterStart + target)
                                    dragging = false
                                } },
                                thumb = { Box(Modifier.size(12.dp).clip(CircleShape).background(PicaAccent)) },
                                track = { sliderState ->
                                    Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                                        val fraction = ((sliderState.value - sliderState.valueRange.start) /
                                            (sliderState.valueRange.endInclusive - sliderState.valueRange.start)).coerceIn(0f, 1f)
                                        drawRoundRect(Color(0xFF43484E), cornerRadius = CornerRadius(size.height))
                                        drawRoundRect(PicaAccent, size = Size(size.width * fraction, size.height), cornerRadius = CornerRadius(size.height))
                                    }
                                },
                                colors = SliderDefaults.colors(thumbColor = PicaAccent, activeTrackColor = PicaAccent), modifier = Modifier.weight(1f).testTag("reader-slider"))
                            if (landscapeControls) modeControls()
                        }
                        if (!landscapeControls) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { modeControls() }
                    }
                }
            }
        }
        if (chrome || state.error != null || state.loading) Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topChromeHeight = it.height }, color = Color(0xF018191B), contentColor = Color.White) {
            Box(Modifier.fillMaxWidth().windowInsetsPadding(horizontalInsets), contentAlignment = Alignment.Center) {
                Row(pageWidth.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = model::goBack, modifier = Modifier.testTag("reader-back")) { Icon(Icons.Outlined.ArrowBack, "返回") }
                    Column(Modifier.weight(1f)) {
                        Text(chapter?.title ?: "阅读", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(if (state.offline) "离线阅读" else "${state.readingMode.title}  双击放大", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB0B4BA))
                    }
                    IconButton(onClick = { chrome = false }) { Icon(Icons.Outlined.Fullscreen, "隐藏工具栏") }
                }
            }
        }
    }
}

private data class SpreadTransform(val zoom: Float, val pan: Offset, val paneWidth: Float, val height: Float) {
    fun horizontalPanLimit(aspect: Float): Float = ((height * aspect * zoom - paneWidth) / 2f).coerceAtLeast(0f)
    val verticalPanLimit get() = height * (zoom - 1f) / 2f
}

private fun PageImage.aspectRatio(): Float = if (width > 0 && height > 0) width.toFloat() / height else .7f

@Composable
private fun FullHeightSpread(
    slots: List<ReaderSlot>,
    pages: IntRange,
    model: PicaViewModel,
    active: Boolean,
    onZoom: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val aspects = remember { mutableStateMapOf<Int, Float>() }
    val maxAspect = pages.maxOf { aspects[it] ?: slots[it].page.aspectRatio() }
    val spread = SpreadTransform(zoom, pan, size.width.toFloat() / pages.count(), size.height.toFloat())
    val transform = rememberTransformableState { scale, delta, _ ->
        zoom = (zoom * scale).coerceIn(1f, 4f)
        val bounds = spread.copy(zoom = zoom)
        val x = bounds.horizontalPanLimit(maxAspect)
        val y = bounds.verticalPanLimit
        pan = if (zoom > 1.01f) Offset((pan.x + delta.x).coerceIn(-x, x), (pan.y + delta.y).coerceIn(-y, y)) else Offset.Zero
    }
    LaunchedEffect(zoom) { onZoom(zoom > 1.01f) }
    LaunchedEffect(active) {
        if (!active) {
            zoom = 1f
            pan = Offset.Zero
        }
    }
    Row(Modifier.fillMaxSize().onSizeChanged { size = it }
        .transformable(state = transform, canPan = { zoom > 1.01f })
        .pointerInput(pages.first) {
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f; pan = Offset.Zero },
            )
        }) {
        for (index in pages) {
            ReaderImage(slots[index].page, index, model, Modifier.weight(1f), horizontal = true, spread = spread,
                onAspectRatio = { aspects[index] = it }, onTap = onTap)
        }
    }
}

@Composable
private fun ReaderImage(
    page: PageImage,
    index: Int,
    model: PicaViewModel,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
    active: Boolean = true,
    tagIndex: Int = index,
    spread: SpreadTransform? = null,
    onAspectRatio: (Float) -> Unit = {},
    onZoom: (Boolean) -> Unit = {},
    onTap: () -> Unit,
) {
    val context = LocalContext.current
    var aspect by remember(page.id) { mutableFloatStateOf(page.aspectRatio()) }
    var loaded by remember(page.id, page.localPath) { mutableStateOf(false) }
    var error by remember(page.id, page.localPath) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var zoom by remember(page.id) { mutableFloatStateOf(1f) }
    var offset by remember(page.id) { mutableStateOf(Offset.Zero) }
    val gestures = if (spread == null) {
        LaunchedEffect(zoom) { onZoom(zoom > 1.01f) }
        LaunchedEffect(active) { if (!active) { zoom = 1f; offset = Offset.Zero } }
        val transform = rememberTransformableState { scale, pan, _ ->
            zoom = (zoom * scale).coerceIn(1f, 4f)
            offset = if (zoom > 1.01f) offset + pan else Offset.Zero
        }
        Modifier.transformable(state = transform, canPan = { zoom > 1.01f })
            .pointerInput(page.id) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f; offset = Offset.Zero }) }
    } else Modifier
    val imageZoom = spread?.zoom ?: zoom
    val imageOffset = if (spread == null) offset else {
        val maxX = spread.horizontalPanLimit(aspect)
        spread.pan.copy(x = spread.pan.x.coerceIn(-maxX, maxX))
    }
    val contentScale = when {
        spread != null -> ContentScale.FillHeight
        horizontal -> ContentScale.Fit
        else -> ContentScale.FillWidth
    }
    Box((if (horizontal) modifier.fillMaxSize() else modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(.05f, 5f))).clipToBounds().testTag("reader-page-$tagIndex")
        .semantics {
            contentDescription = "第 ${index + 1} 页${if (loaded) "已加载" else if (error) "加载失败" else "加载中"}"
            if (imageZoom > 1.01f) stateDescription = "已放大"
        }
        .then(gestures)) {
        val source: Any = page.localPath?.let { model.repo.files.resolve(it) } ?: page.url
        val request = remember(source, retry) { ImageRequest.Builder(context).data(source).crossfade(false).build() }
        AsyncImage(model = request, contentDescription = null, contentScale = contentScale, clipToBounds = spread == null,
            onSuccess = {
                val drawable = it.result.drawable
                if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                    aspect = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                    onAspectRatio(aspect)
                }
                loaded = true; error = false
            }, onError = { error = true; loaded = false },
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = imageZoom, scaleY = imageZoom,
                translationX = imageOffset.x, translationY = imageOffset.y))
        if (!loaded && !error) CircularProgressIndicator(Modifier.size(25.dp).align(Alignment.Center), strokeWidth = 2.dp, color = PicaAccent)
        if (error) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("第 ${index + 1} 页加载失败", color = Color(0xFFB0B4BA))
            TextButton(onClick = { retry++; error = false }) { Text("重新加载", color = PicaAccent) }
        }
    }
}
