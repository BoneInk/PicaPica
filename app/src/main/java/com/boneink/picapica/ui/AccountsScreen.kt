package com.boneink.picapica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.boneink.picapica.network.SourceKind

@Composable
internal fun AccountsScreen(model: PicaViewModel) {
    val accounts by model.container.accounts.accounts.collectAsState()
    var adding by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("accounts-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(accounts, key = { it.id }) { account ->
            val signedIn by model.container.repository(account.id).signedIn.collectAsState()
            Surface(Modifier.fillMaxWidth().testTag("account-${account.id}").clickable { model.switchAccount(account.id) },
                shape = MaterialTheme.shapes.small, border = panelBorder(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Outlined.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(account.name, style = MaterialTheme.typography.titleMedium)
                        Text("${account.source.title}  ${if (signedIn) "已登录" else "未登录"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (account.id == model.account.id) Icon(Icons.Outlined.Check, "当前账号") else Icon(Icons.Outlined.ChevronRight, null)
                }
            }
        }
        item { OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add-account"), shape = MaterialTheme.shapes.small) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("添加账号")
        } }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("选择数据源") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceKind.entries.forEach { source -> OutlinedButton(onClick = { adding = false; model.addAccount(source) },
                modifier = Modifier.fillMaxWidth().testTag("add-source-${source.name}"), shape = MaterialTheme.shapes.small) { Text(source.title) } }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } })
}
