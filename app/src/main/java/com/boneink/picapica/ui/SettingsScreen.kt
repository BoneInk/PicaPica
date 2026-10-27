package com.boneink.picapica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.boneink.picapica.BuildConfig
import com.boneink.picapica.data.*

@Composable
fun SettingsScreen(state: UiState, model: PicaViewModel) {
    var choice by remember { mutableStateOf<String?>(null) }
    var confirmCache by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    val prefs = state.preferences
    LazyColumn(Modifier.fillMaxSize().testTag("settings-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            SettingsGroup("外观") {
                PreferenceRow(Icons.Outlined.Palette, "主题", prefs.theme.title, "setting-theme") { choice = "theme" }
            }
        }
        item {
            SettingsGroup("阅读") {
                PreferenceRow(Icons.Outlined.MenuBook, "翻页方式", state.readingMode.title, "setting-mode") { choice = "mode" }
                SettingsDivider()
                PreferenceRow(Icons.Outlined.ScreenRotation, "屏幕方向", prefs.orientation.title, "setting-orientation") { choice = "orientation" }
                SettingsDivider()
                PreferenceRow(Icons.Outlined.Brightness6, "阅读亮度", if (prefs.brightness < 0) "跟随系统" else "${(prefs.brightness * 100).toInt()}%", "setting-brightness") { choice = "brightness" }
                SettingsDivider()
                PreferenceSwitch(Icons.Outlined.VolumeUp, "音量键翻页", prefs.volumePaging, "setting-volume") { value -> model.updatePreferences { it.copy(volumePaging = value) } }
                SettingsDivider()
                PreferenceSwitch(Icons.Outlined.LightMode, "阅读时保持亮屏", prefs.keepScreenOn, "setting-keep-awake") { value -> model.updatePreferences { it.copy(keepScreenOn = value) } }
            }
        }
        item {
            SettingsGroup("图片与网络") {
                PreferenceRow(Icons.Outlined.HighQuality, "图片质量", prefs.imageQuality.title, "setting-quality") { choice = "quality" }
                SettingsDivider()
                Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { ConnectionSelector(model) }
                SettingsDivider()
                PreferenceRow(Icons.Outlined.CleaningServices, "清理临时图片缓存", if (state.clearingCache) "正在清理" else "", "setting-cache", enabled = !state.clearingCache) { confirmCache = true }
            }
        }
        item {
            SettingsGroup("关于") {
                PreferenceRow(Icons.Outlined.Info, "PicaPica", BuildConfig.VERSION_NAME, "setting-version", enabled = false) { }
                SettingsDivider()
                PreferenceRow(Icons.Outlined.Code, "开源许可", "", "setting-licenses") { licenses = true }
            }
        }
        if (state.signedIn) item {
            OutlinedButton(onClick = model::logout, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("logout")) { Text("退出登录") }
            Text("退出后会暂停下载，已下载章节和阅读记录保留在本机。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
    }
    when (choice) {
        "theme" -> SettingsChoice("主题", AppTheme.entries.map { it.title }, prefs.theme.ordinal, { choice = null }) { index -> model.updatePreferences { it.copy(theme = AppTheme.entries[index]) } }
        "mode" -> SettingsChoice("翻页方式", ReadingMode.entries.map { it.title }, state.readingMode.ordinal, { choice = null }) { index -> model.container.readerSettings.setMode(ReadingMode.entries[index]) }
        "orientation" -> SettingsChoice("屏幕方向", ReaderOrientation.entries.map { it.title }, prefs.orientation.ordinal, { choice = null }) { index -> model.updatePreferences { it.copy(orientation = ReaderOrientation.entries[index]) } }
        "quality" -> SettingsChoice("图片质量", ImageQuality.entries.map { it.title }, prefs.imageQuality.ordinal, { choice = null }, description = "部分图片在不同质量下可能相同，已下载章节不受影响。") { index -> model.updatePreferences { it.copy(imageQuality = ImageQuality.entries[index]) } }
        "brightness" -> BrightnessDialog(prefs.brightness, { choice = null }) { value -> model.updatePreferences { it.copy(brightness = value) };choice = null }
    }
    if (confirmCache) AlertDialog(onDismissRequest = { confirmCache = false }, title = { Text("清理临时图片缓存？") },
        text = { Text("已下载章节、离线封面和阅读记录会保留。") },
        confirmButton = { TextButton(onClick = { confirmCache = false;model.clearImageCache() }, modifier = Modifier.testTag("confirm-clear-cache")) { Text("清理") } },
        dismissButton = { TextButton(onClick = { confirmCache = false }) { Text("取消") } })
    if (licenses) {
        val text = remember { model.container.appContext.assets.open("licenses/protocol-notices.txt").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text("开源许可") }, text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) { item { Text(text, style = MaterialTheme.typography.bodySmall) } }
        }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("关闭") } })
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(content = content) }
    }
}
@Composable
private fun SettingsDivider() { HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp), thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant) }
@Composable
private fun PreferenceRow(icon: ImageVector, title: String, value: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).clickable(enabled = enabled, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tag != "setting-version") Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun PreferenceSwitch(icon: ImageVector, title: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}
@Composable
private fun SettingsChoice(title: String, items: List<String>, selected: Int, onDismiss: () -> Unit, description: String? = null, onSelect: (Int) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            items.forEachIndexed { index, label ->
                Row(Modifier.fillMaxWidth().clickable { onSelect(index);onDismiss() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == index, onClick = { onSelect(index);onDismiss() })
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
@Composable
private fun BrightnessDialog(current: Float, onDismiss: () -> Unit, onConfirm: (Float) -> Unit) {
    var system by remember { mutableStateOf(current < 0) }
    var brightness by remember { mutableFloatStateOf(if (current < 0) .5f else current) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("阅读亮度") }, text = {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(system, onCheckedChange = { system = it }, modifier = Modifier.testTag("brightness-follow-system"));Text("跟随系统") }
            Slider(value = brightness, onValueChange = { brightness = it }, enabled = !system, valueRange = .05f..1f, modifier = Modifier.testTag("brightness-slider"))
            Text(if (system) "跟随系统" else "${(brightness * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(if (system) -1f else brightness) }, modifier = Modifier.testTag("brightness-confirm")) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
