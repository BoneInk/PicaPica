@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.boneink.picapica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.boneink.picapica.network.UserProfile
import com.boneink.picapica.network.RecentComment
import com.boneink.picapica.network.ComicComment

@Composable
fun UserAvatar(user: UserProfile, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, tag: String = "user-avatar", onClickLabel: String = "查看${user.name}的资料") {
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
        .testTag(tag).then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier), contentAlignment = Alignment.Center) {
        Text(user.name.take(1), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        if (user.avatarUrl.isNotBlank()) AsyncImage(model = user.avatarUrl, contentDescription = "${user.name}的头像", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun UserOverlays(state: UiState, model: PicaViewModel) {
    state.viewedUser?.let { user ->
        ModalBottomSheet(onDismissRequest = model::closeUserDetails, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 28.dp).testTag("user-details"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    UserAvatar(user, Modifier.size(80.dp), if (user.avatarUrl.isNotBlank()) ({ model.showAvatar(user) }) else null, tag = "user-details-avatar", onClickLabel = "查看头像大图")
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(user.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(user.level?.let { "Lv.$it" }, user.title.takeIf(String::isNotBlank), if (user.verified) "已认证" else null).joinToString("  "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (user.slogan.isNotBlank()) Text(user.slogan, style = MaterialTheme.typography.bodyLarge)
                HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    user.exp?.let { exp -> Column { Text("经验", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(exp.toString(), style = MaterialTheme.typography.titleMedium) } }
                    val gender = when (user.gender) { "m" -> "男"; "f" -> "女"; "bot" -> "机器人"; else -> null }
                    gender?.let { Column { Text("性别", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(it, style = MaterialTheme.typography.titleMedium) } }
                    if (user.createdAt.isNotBlank()) Column { Text("加入时间", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(user.createdAt.take(10), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
    state.avatarPreview?.let { user -> AvatarPreview(user, model::closeAvatar) }
}

@Composable
private fun AvatarPreview(user: UserProfile, onDismiss: () -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val request = remember(user.avatarUrl, retry) { ImageRequest.Builder(context).data(user.avatarUrl).setParameter("manualRetry", retry, memoryCacheKey = null).size(coil.size.Size.ORIGINAL).crossfade(false).build() }
    val transform = rememberTransformableState { scale, pan, _ -> zoom = (zoom * scale).coerceIn(1f, 4f); offset = if (zoom > 1f) offset + pan else Offset.Zero }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF111113.toInt()))
                val bars = androidx.core.view.WindowCompat.getInsetsController(window, view)
                bars.isAppearanceLightStatusBars = false
                bars.isAppearanceLightNavigationBars = false
                if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            }
            onDispose { }
        }
        Box(Modifier.fillMaxSize().background(Color(0xFF111113)).testTag("avatar-preview")) {
            Box(Modifier.fillMaxSize().padding(top = 80.dp, bottom = 48.dp).clipToBounds().transformable(transform, canPan = { zoom > 1f })
                .pointerInput(user.avatarUrl) { detectTapGestures(onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f; offset = Offset.Zero }) }) {
                AsyncImage(request, if (loaded) "头像大图已加载" else "头像大图", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = offset.x, translationY = offset.y),
                    onSuccess = { loaded = true; failed = false }, onError = { loaded = false; failed = true })
                if (!loaded && !failed) CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.Center), color = PicaPink, strokeWidth = 2.dp)
                if (failed) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("头像暂时无法加载", color = Color.White)
                    TextButton(onClick = { retry++; failed = false }) { Text("重新加载", color = PicaPink) }
                }
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("avatar-close")) { Icon(Icons.Outlined.Close, "关闭头像", tint = Color.White) }
                Text(user.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = { zoom = 1f; offset = Offset.Zero }) { Text("还原", color = PicaPink) }
            }
        }
    }
}

@Composable
fun RecentCommentCard(comment: RecentComment, state: UiState, model: PicaViewModel, modifier: Modifier = Modifier, compact: Boolean = false) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.fillMaxWidth().testTag("recent-comment-${comment.id}").clickable(enabled = comment.comicId != null, onClickLabel = "查看这条评论及回复") { model.openCommentHistory(comment) }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (comment.parentId != null) "回复  ${comment.createdAt.take(10)}" else comment.createdAt.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(comment.content, style = MaterialTheme.typography.bodyLarge, maxLines = if (compact) 3 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${comment.likes} 赞", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (comment.replies > 0 && !comment.hidden) TextButton(onClick = { model.toggleInlineReplies(comment.id) }, modifier = Modifier.testTag("inline-toggle-${comment.id}")) {
                    Text(if (state.inlineReplies[comment.id]?.expanded == true) "收起回复" else "展开 ${comment.replies} 条回复", style = MaterialTheme.typography.bodySmall)
                } else Text("${comment.replies} 回复", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (comment.comicId != null) Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { model.openCommentHistory(comment) }.padding(12.dp).testTag("recent-source-${comment.id}"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                Text(comment.comicTitle.ifBlank { "查看评论区" }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("评论区", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(17.dp))
            } else Text("作品暂不可查看", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InlineReplies(comment.id, state, model, setOf(comment.id))
            HorizontalDivider(Modifier.padding(top = 6.dp), thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
fun MyCommentsScreen(state: UiState, model: PicaViewModel) {
    if (!state.signedIn) { EmptyState("登录后查看最近评论", "", "登录") { model.navigate(Screen.LOGIN) }; return }
    LazyColumn(Modifier.fillMaxSize().testTag("my-comments-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("最近评论 ${state.myCommentTotal}", "刷新") { model.loadMyComments() } }
        state.myCommentsError?.let { error -> item { ErrorMessage(error) { model.loadMyComments() } } }
        items(state.myComments, key = RecentComment::id) { RecentCommentCard(it, state, model) }
        if (state.myCommentsLoading) item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) } }
        if (state.myComments.isEmpty() && !state.myCommentsLoading && state.myCommentsError == null) item { EmptyState("还没有评论记录") }
        if (state.myComments.isNotEmpty() && state.myCommentPage < state.myCommentPages) item {
            OutlinedButton(onClick = { model.loadMyComments(more = true) }, enabled = !state.myCommentsLoading, modifier = Modifier.fillMaxWidth().testTag("my-comments-more")) { Text("加载更多") }
        }
    }
}

@Composable
private fun InlineReplies(id: String, state: UiState, model: PicaViewModel, path: Set<String>) {
    val replies = state.inlineReplies[id] ?: return
    if (!replies.expanded) return
    Column(Modifier.fillMaxWidth().testTag("inline-replies-$id").clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(if (path.size <= 2) 12.dp else 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        replies.items.filterNot { it.id in path }.forEach { reply ->
            Column(Modifier.fillMaxWidth().testTag("inline-reply-${reply.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val user = reply.user ?: UserProfile("", reply.author)
                    UserAvatar(user, Modifier.size(28.dp), { model.showUser(user) }, tag = "inline-avatar-${reply.id}")
                    Text(reply.author, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(reply.createdAt.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(reply.content, style = MaterialTheme.typography.bodyMedium)
                if (reply.replies > 0 && !reply.hidden && path.size < 8) TextButton(onClick = { model.toggleInlineReplies(reply.id) }, modifier = Modifier.testTag("inline-toggle-${reply.id}")) {
                    Text(if (state.inlineReplies[reply.id]?.expanded == true) "收起回复" else "展开 ${reply.replies} 条回复", style = MaterialTheme.typography.bodySmall)
                }
                if (path.size < 8) InlineReplies(reply.id, state, model, path + reply.id)
            }
        }
        if (replies.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        replies.error?.let { error -> ErrorMessage(error) { model.loadInlineReplies(id, more = replies.loaded) } }
        if (replies.loaded && replies.items.isEmpty() && !replies.loading && replies.error == null) Text("暂无可见回复", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (replies.page < replies.pages && !replies.loading) TextButton(onClick = { model.loadInlineReplies(id, more = true) }, modifier = Modifier.testTag("inline-more-$id")) { Text("更多回复", style = MaterialTheme.typography.bodySmall) }
    }
}
