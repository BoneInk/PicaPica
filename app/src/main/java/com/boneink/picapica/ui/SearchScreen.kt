@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.boneink.picapica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.boneink.picapica.network.OfficialDiscussion
import com.boneink.picapica.network.SearchSort

@Composable
internal fun SearchScreen(state: UiState, model: PicaViewModel) {
    val visibleResults = rememberVisibleComics(state, state.results)
    var query by rememberSaveable { mutableStateOf(state.query) }
    var showCategories by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit(sort: SearchSort = state.searchSort, categories: Set<String> = state.searchCategories) {
        focus.clearFocus(); keyboard?.hide(); model.search(query, sort = sort, categories = categories)
    }
    LazyVerticalGrid(adaptiveColumns(), Modifier.fillMaxSize().imePadding().testTag("search-list"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        fullWidthItem {
            Row(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("搜索漫画关键词", style = MaterialTheme.typography.bodySmall) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant, focusedBorderColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f).testTag("search-input"), shape = MaterialTheme.shapes.small)
                TextButton(onClick = { submit() }, modifier = Modifier.testTag("search-submit")) { Text("搜索") }
            }
        }
        fullWidthItem {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                model.capabilities.searchSorts.forEach { sort -> QuietChip(if (model.source == com.boneink.picapica.network.SourceKind.XIANGXIANG) "默认排序" else sort.title, state.searchSort == sort, Modifier.testTag("search-sort-${sort.code}")) { submit(sort = sort) } }
            }
            if (model.capabilities.searchCategories) Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { focus.clearFocus(); keyboard?.hide(); showCategories = true }, enabled = state.signedIn, modifier = Modifier.weight(1f).testTag("search-categories"), contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Icon(Icons.Outlined.FilterList, null, Modifier.size(18.dp))
                    Text(when (state.searchCategories.size) { 0 -> "全部分类"; 1 -> state.searchCategories.first(); else -> "分类 ${state.searchCategories.size}" },
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
                if (state.searchCategories.isNotEmpty()) TextButton(onClick = { submit(categories = emptySet()) }, modifier = Modifier.testTag("search-clear-categories")) { Text("清除分类", style = MaterialTheme.typography.bodySmall) }
            }
            if (state.searchCategories.isNotEmpty()) Text(state.searchCategories.sorted().joinToString("、"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.results.size > visibleResults.size) fullWidthItem { HiddenComicsNotice(state.results.size - visibleResults.size) { model.navigate(Screen.BLOCKING) } }
        if (!state.signedIn) fullWidthItem { EmptyState("登录后搜索", "", "登录") { model.navigate(Screen.LOGIN) } }
        else if (state.searchSubmitted && visibleResults.isEmpty() && !state.loading && state.error == null) fullWidthItem { EmptyState("没有找到相关作品") }
        comicItems(visibleResults) { model.openComic(it.id) }
        if (state.currentPage < state.totalPages) fullWidthItem {
            OutlinedButton(onClick = { model.search(state.query, more = true) }, enabled = !state.loading, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().testTag("search-more")) { Text("加载更多") }
        }
    }
    if (showCategories) SearchCategorySheet(state, model, onDismiss = { showCategories = false }) { selected ->
        showCategories = false; submit(categories = selected)
    }
}

@Composable
private fun SearchCategorySheet(state: UiState, model: PicaViewModel, onDismiss: () -> Unit, onApply: (Set<String>) -> Unit) {
    val quickBlocks = LocalQuickBlocks.current
    var selected by remember { mutableStateOf(state.searchCategories) }
    val categories = state.categories.filterNot { it.id == OfficialDiscussion.category.id }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("分类", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = emptySet() }, modifier = Modifier.testTag("search-category-reset")) { Text("重置") }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).testTag("search-category-list"), contentPadding = PaddingValues(vertical = 12.dp)) {
                if (state.categoriesLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.categoriesError?.let { error -> item { ErrorMessage(error) { model.loadCategories() } } }
                if (categories.isEmpty() && !state.categoriesLoading && state.categoriesError == null) item { EmptyState("暂无分类", "", "刷新") { model.loadCategories() } }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuietChip("全部分类", selected.isEmpty(), Modifier.testTag("search-category-all")) { selected = emptySet() }
                        categories.forEach { category ->
                            QuietChip(category.title, category.title in selected, Modifier.testTag("search-category-${category.id}"), onLongClick = quickBlocks?.let { { it.category(category.title) } }) {
                                selected = if (category.title in selected) selected - category.title else selected + category.title
                            }
                        }
                    }
                }
            }
            Button(onClick = { onApply(selected) }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).heightIn(min = 48.dp).testTag("search-category-apply"), shape = MaterialTheme.shapes.small) {
                Text(if (selected.isEmpty()) "应用" else "应用 ${selected.size} 个分类")
            }
        }
    }
}
