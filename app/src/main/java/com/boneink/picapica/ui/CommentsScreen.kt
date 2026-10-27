package com.boneink.picapica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boneink.picapica.network.ComicComment

@Composable
fun CommentsScreen(state: UiState, model: PicaViewModel) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val parent = state.replyParent
    val draft = state.commentDrafts[model.commentDraftKey()].orEmpty()
    val listStates = rememberSaveableStateHolder()
    val pinned = state.pinnedComments
    val rows = if (parent == null) state.comments.filterNot { c -> pinned.any { it.id == c.id } } else state.replies
    Column(Modifier.fillMaxSize()) {
        state.commentError?.let { error -> Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { ErrorMessage(error) { model.loadComments() } } }
        listStates.SaveableStateProvider(model.commentDraftKey()) {
        val list = rememberLazyListState()
        LazyColumn(Modifier.weight(1f).testTag("comments-list"), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (parent == null) "全部评论 ${state.commentTotal}" else "回复", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (parent != null) TextButton(onClick = model::showAllComments, modifier = Modifier.testTag("all-comic-comments")) { Text(if (state.isOfficialDiscussion) "全部评论" else "作品评论区", style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { model.loadComments() }, enabled = !state.commentLoading) { Text("刷新") }
                }
            }
            if (parent != null) item {
                if (state.historyCommentId == parent.id) Text("来自评论历史", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("history-comment-anchor"))
                CommentRow(parent, state, model, showReply = false)
                HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
            else items(pinned, key = { "pinned-${it.id}" }) { CommentRow(it, state, model) }
            items(rows, key = { it.id }) { CommentRow(it, state, model) }
            if (state.commentLoading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) } }
            if (rows.isEmpty() && (parent != null || pinned.isEmpty()) && !state.commentLoading && state.commentError == null) item { EmptyState(if (parent == null) "还没有评论" else "还没有回复") }
            if (!state.commentLoading && (if (parent == null) state.commentPage < state.commentPages else state.replyPage < state.replyPages)) item {
                OutlinedButton(onClick = { model.loadComments(more = true) }, modifier = Modifier.fillMaxWidth().testTag("comments-more")) { Text("加载更多") }
            }
        }
        }
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            Column {
            HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.imePadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (state.commentContextReady && state.comic?.allowComment == false) Text("这部作品已关闭评论", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (state.replyUnavailable) Text("这条评论暂时无法回复", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = draft, onValueChange = model::setCommentDraft,
                        placeholder = { Text(if (parent == null) "输入评论" else "回复 ${parent.author}", style = MaterialTheme.typography.bodyMedium) },
                        enabled = !state.commentSending && state.signedIn && state.commentContextReady, maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.weight(1f).testTag("comment-draft"), shape = MaterialTheme.shapes.large)
                    FilledIconButton(onClick = { focus.clearFocus(); keyboard?.hide(); model.sendComment(draft) }, enabled = draft.isNotBlank() && !state.commentSending && state.signedIn && state.commentContextReady,
                        modifier = Modifier.size(48.dp).testTag("comment-send")) { Icon(Icons.Outlined.Send, "发送评论", Modifier.size(20.dp)) }
                }
            }
            }
        }
    }
}

@Composable
private fun CommentRow(comment: ComicComment, state: UiState, model: PicaViewModel, showReply: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp).testTag("comment-${comment.id}"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        UserAvatar(comment.user ?: com.boneink.picapica.network.UserProfile("", comment.author), Modifier.size(40.dp),
            comment.user?.let { user -> ({ model.showUser(user) }) }, tag = "comment-avatar-${comment.id}")
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(comment.author, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).then(if (comment.user != null) Modifier.clickable { model.showUser(comment.user) } else Modifier))
                if (comment.pinned) Text("置顶", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (comment.createdAt.isNotBlank()) Text(comment.createdAt.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(comment.content, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (showReply) TextButton(enabled = !comment.hidden, onClick = { model.openReplies(comment) }, modifier = Modifier.testTag("comment-replies-${comment.id}"), contentPadding = PaddingValues(end = 10.dp)) {
                    Text(if (comment.replies > 0) "${comment.replies} 条回复" else "回复", style = MaterialTheme.typography.bodySmall)
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(15.dp))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { model.likeComment(comment) }, enabled = state.signedIn && comment.id !in state.commentLikeBusy,
                    modifier = Modifier.testTag("comment-like-${comment.id}"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.ThumbUp, if (comment.isLiked) "取消点赞" else "点赞", Modifier.size(15.dp), tint = if (comment.isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" ${comment.likes}", style = MaterialTheme.typography.bodySmall, color = if (comment.isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
