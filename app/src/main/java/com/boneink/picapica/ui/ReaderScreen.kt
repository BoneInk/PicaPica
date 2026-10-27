@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.boneink.picapica.network.PageImage
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(state: UiState, model: PicaViewModel) {
    val comic = state.comic
    val chapter = state.chapter
    var chrome by rememberSaveable { mutableStateOf(true) }
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
    Box(Modifier.fillMaxSize().background(Color(0xFF111113)).testTag("reader")) {
        if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = PicaPink)
        else if (state.error != null) Column(Modifier.align(Alignment.Center).padding(25.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.error, color = Color.White)
            TextButton(onClick = { chapter?.let { model.openChapter(it) } }) { Text("重试", color = PicaPink) }
            TextButton(onClick = model::goBack) { Text("返回书架", color = Color.White) }
        }
        if (state.images.isNotEmpty() && comic != null && chapter != null) key(comic.id, chapter.order, state.offline, state.readingMode) {
            val horizontal = state.readingMode == ReadingMode.HORIZONTAL
            val list = rememberLazyListState(initialFirstVisibleItemIndex = state.initialPage)
            val pager = rememberPagerState(initialPage = state.initialPage, pageCount = { state.images.size })
            val scope = rememberCoroutineScope()
            val index by remember { derivedStateOf { (if (horizontal) pager.settledPage else list.firstVisibleItemIndex).coerceIn(0, state.images.lastIndex) } }
            val scrolling = if (horizontal) pager.isScrollInProgress else list.isScrollInProgress
            var zoomed by remember { mutableStateOf(false) }
            DisposableEffect(list, pager, state.preferences.volumePaging) {
                activity?.readerVolumeHandler = if (state.preferences.volumePaging) ({ delta -> scope.launch {
                    if (!(if (horizontal) pager.isScrollInProgress else list.isScrollInProgress)) {
                        val current = if (horizontal) pager.settledPage else list.firstVisibleItemIndex
                        val target = (current + delta).coerceIn(0, state.images.lastIndex)
                        if (target != current) {
                            zoomed = false
                            if (horizontal) pager.scrollToPage(target) else list.scrollToItem(target)
                        }
                    }
                }; Unit }) else null
                onDispose { activity?.readerVolumeHandler = null }
            }
            var slider by remember { mutableFloatStateOf(state.initialPage.toFloat()) }
            var dragging by remember { mutableStateOf(false) }
            LaunchedEffect(list, pager) {
                snapshotFlow { if (horizontal) pager.settledPage else list.firstVisibleItemIndex }.distinctUntilChanged().debounce(300).collect { value ->
                    model.saveProgress(comic.id, chapter.order, value.coerceIn(0, state.images.lastIndex))
                }
            }
            DisposableEffect(list, pager) { onDispose { model.saveProgress(comic.id, chapter.order,
                (if (horizontal) pager.settledPage else list.firstVisibleItemIndex).coerceIn(0, state.images.lastIndex)) } }
            if (horizontal) HorizontalPager(state = pager, userScrollEnabled = !zoomed,
                modifier = Modifier.fillMaxSize().padding(top = if (chrome) 96.dp else 0.dp, bottom = if (chrome) 164.dp else 0.dp).testTag("reader-pager")) { i ->
                ReaderImage(state.images[i], i, model, horizontal = true, active = pager.currentPage == i,
                    onZoom = { if (pager.currentPage == i) zoomed = it }, onTap = { chrome = !chrome })
            } else LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("reader-pages"),
                contentPadding = PaddingValues(top = if (chrome) 100.dp else 0.dp, bottom = if (chrome) 170.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(state.images, key = { _, page -> page.id }) { i, page -> ReaderImage(page, i, model, onTap = { chrome = !chrome }) }
                item {
                    Row(Modifier.fillMaxWidth().padding(15.dp), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { model.adjacentChapter(1) }, enabled = state.chapters.any { it.order > chapter.order }) { Text("下一章 →", color = PicaPink) }
                    }
                }
            }
            if (chrome) Surface(Modifier.align(Alignment.BottomCenter), color = Color(0xF018191B), contentColor = Color.White) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { model.adjacentChapter(-1) }, enabled = state.chapters.any { it.order < chapter.order }) { Text("上一章", color = if (state.chapters.any { it.order < chapter.order }) Color.White else Color.Gray) }
                        Text("${index + 1} / ${state.images.size}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag("reader-position"))
                        TextButton(onClick = { model.adjacentChapter(1) }, enabled = state.chapters.any { it.order > chapter.order }) { Text("下一章", color = if (state.chapters.any { it.order > chapter.order }) Color.White else Color.Gray) }
                    }
                    Slider(value = if (dragging) slider else index.toFloat(),
                        onValueChange = { dragging = true; slider = it }, valueRange = 0f..state.images.lastIndex.coerceAtLeast(1).toFloat(),
                        onValueChangeFinished = { scope.launch {
                            val target = slider.toInt().coerceIn(0, state.images.lastIndex)
                            if (horizontal) pager.scrollToPage(target) else list.scrollToItem(target)
                            dragging = false
                        } },
                        thumb = { Box(Modifier.size(12.dp).clip(CircleShape).background(PicaPink)) },
                        track = { sliderState ->
                            Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                                val fraction = ((sliderState.value - sliderState.valueRange.start) /
                                    (sliderState.valueRange.endInclusive - sliderState.valueRange.start)).coerceIn(0f, 1f)
                                drawRoundRect(Color(0xFF43484E), cornerRadius = CornerRadius(size.height))
                                drawRoundRect(PicaPink, size = Size(size.width * fraction, size.height), cornerRadius = CornerRadius(size.height))
                            }
                        },
                        colors = SliderDefaults.colors(thumbColor = PicaPink, activeTrackColor = PicaPink), modifier = Modifier.testTag("reader-slider"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        ReadingMode.entries.forEach { mode ->
                            TextButton(onClick = { if (mode != state.readingMode) model.setReadingMode(mode, index) },
                                enabled = !scrolling, modifier = Modifier.testTag("reader-mode-${mode.name.lowercase()}")) {
                                Icon(if (mode == ReadingMode.HORIZONTAL) Icons.Outlined.Swipe else Icons.Outlined.SwapVert, null, Modifier.size(17.dp), tint = if (mode == state.readingMode) PicaPink else Color(0xFFB0B4BA))
                                Spacer(Modifier.width(6.dp))
                                Text(mode.title, color = if (mode == state.readingMode) PicaPink else Color(0xFFB0B4BA), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        if (chrome || state.error != null || state.loading) Surface(Modifier.align(Alignment.TopCenter), color = Color(0xF018191B), contentColor = Color.White) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
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

@Composable
private fun ReaderImage(page: PageImage, index: Int, model: PicaViewModel, horizontal: Boolean = false, active: Boolean = true, onZoom: (Boolean) -> Unit = {}, onTap: () -> Unit) {
    val context = LocalContext.current
    var aspect by remember(page.id) { mutableFloatStateOf(if (page.width > 0 && page.height > 0) page.width.toFloat() / page.height else .7f) }
    var loaded by remember(page.id, page.localPath) { mutableStateOf(false) }
    var error by remember(page.id, page.localPath) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var zoom by remember(page.id) { mutableFloatStateOf(1f) }
    var offset by remember(page.id) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(zoom) { onZoom(zoom > 1.01f) }
    LaunchedEffect(active) { if (!active) { zoom = 1f; offset = Offset.Zero } }
    val transform = rememberTransformableState { scale, pan, _ ->
        zoom = (zoom * scale).coerceIn(1f, 4f)
        offset = if (zoom > 1.01f) offset + pan else Offset.Zero
    }
    Box((if (horizontal) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(.05f, 5f))).clipToBounds().testTag("reader-page-$index")
        .semantics { contentDescription = "第 ${index + 1} 页${if (loaded) "已加载" else if (error) "加载失败" else "加载中"}" }
        .transformable(state = transform, canPan = { zoom > 1.01f })
        .pointerInput(page.id) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f; offset = Offset.Zero }) }) {
        val source: Any = page.localPath?.let { model.repo.files.resolve(it) } ?: page.url
        val request = remember(source, retry) { ImageRequest.Builder(context).data(source).crossfade(false).build() }
        AsyncImage(model = request, contentDescription = null, contentScale = if (horizontal) ContentScale.Fit else ContentScale.FillWidth,
            onSuccess = {
                val drawable = it.result.drawable
                if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) aspect = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                loaded = true; error = false
            }, onError = { error = true; loaded = false },
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = offset.x, translationY = offset.y))
        if (!loaded && !error) CircularProgressIndicator(Modifier.size(25.dp).align(Alignment.Center), strokeWidth = 2.dp, color = PicaPink)
        if (error) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("第 ${index + 1} 页加载失败", color = Color(0xFFB0B4BA))
            TextButton(onClick = { retry++; error = false }) { Text("重新加载", color = PicaPink) }
        }
    }
}
