package com.boneink.picapica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boneink.picapica.network.ConnectionRoute

@Composable
fun ConnectionSelector(model: PicaViewModel) {
    val route by model.container.network.route.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) {
        Icon(Icons.Outlined.Route, null, Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text("连接线路：${route.title}", style = MaterialTheme.typography.bodySmall)
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("连接线路") }, text = {
        Column {
            ConnectionRoute.entries.forEach { choice ->
                Row(Modifier.fillMaxWidth().clickable { model.container.network.select(choice); open = false }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = route == choice, onClick = { model.container.network.select(choice); open = false })
                    Column { Text(choice.title); Text(choice.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("完成") } })
}
