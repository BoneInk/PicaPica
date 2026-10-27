@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.boneink.picapica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.boneink.picapica.data.ContentBlockRules
import com.boneink.picapica.network.OfficialDiscussion

@Composable
fun BlockingScreen(state: UiState, model: PicaViewModel) {
    val rules = state.blockRules
    var editing by remember { mutableStateOf<String?>(null) }
    var reset by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (state.signedIn && state.categories.size <= 1) model.loadCategories() }
    LazyColumn(Modifier.fillMaxSize().testTag("blocking-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            BlockSwitch("内容屏蔽", rules.enabled, "block-enabled") { value -> model.updateBlocking { it.copy(enabled = value) } }
            Text("命中任一规则的作品会从首页、搜索、排行榜、云收藏和阅读历史中隐藏。下载文件和收藏记录保留，关闭屏蔽即可恢复显示。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            RuleRow("关键词", rules.keywords, "block-keywords") { editing = "关键词" }
            RuleRow("标签", rules.tags, "block-tags") { editing = "标签" }
            RuleRow("分类", rules.categories, "block-categories") { editing = "分类" }
            Text("关键词匹配标题、作者及已有简介；标签与分类按完整名称匹配。列表未提供的标签，会在读取作品详情后补充。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            BlockSwitch("屏蔽 AI 生成内容", rules.hideAi, "block-ai") { value -> model.updateBlocking { it.copy(hideAi = value) } }
            Text("根据作品的 AI 标记、分类或标签判断，未标记的作品无法自动识别。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { TextButton(onClick = { reset = true }, enabled = rules.count > 0, modifier = Modifier.testTag("block-reset")) { Text("清空屏蔽规则") } }
    }
    editing?.let { kind ->
        val values = when (kind) { "关键词" -> rules.keywords; "标签" -> rules.tags; else -> rules.categories }
        var text by remember(kind) { mutableStateOf(values.joinToString("\n")) }
        var selected by remember(kind) { mutableStateOf(values) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("屏蔽$kind") }, text = {
            LazyColumn(Modifier.heightIn(max = 420.dp).testTag("block-rule-editor"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { OutlinedTextField(text, { text = it.take(25000) }, modifier = Modifier.fillMaxWidth().testTag("block-rule-input"), label = { Text("每行一个，最多 200 个") }, minLines = 3, maxLines = 6) }
                if (kind == "分类") {
                    if (state.categoriesLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.categoriesError?.let { error -> item { ErrorMessage(error) { model.loadCategories() } } }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.categories.filterNot { it.id == OfficialDiscussion.category.id }.forEach { category ->
                                val active = category.title in text.lines().map(String::trim)
                                FilterChip(active, onClick = {
                                    selected = text.lines().map(String::trim).filter(String::isNotBlank).toSet().let { if (active) it - category.title else it + category.title }
                                    text = selected.joinToString("\n")
                                }, label = { Text(category.title) }, modifier = Modifier.testTag("block-category-${category.id}"))
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = {
            val next = text.lines().map(String::trim).filter(String::isNotBlank).toSet()
            model.updateBlocking { when (kind) { "关键词" -> it.copy(keywords = next); "标签" -> it.copy(tags = next); else -> it.copy(categories = next) } }; editing = null
        }, modifier = Modifier.testTag("block-rule-save")) { Text("保存") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("清空屏蔽规则？") }, confirmButton = {
        TextButton(onClick = { model.updateBlocking { ContentBlockRules(enabled = it.enabled) }; reset = false }, modifier = Modifier.testTag("block-reset-confirm")) { Text("清空") }
    }, dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } })
}

@Composable
private fun BlockSwitch(title: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = onChange)
    }
}
@Composable
private fun RuleRow(title: String, values: Set<String>, tag: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag(tag), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$title  ${values.size}", style = MaterialTheme.typography.titleMedium)
            Text(values.take(4).joinToString("、").ifBlank { "未设置" }, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
