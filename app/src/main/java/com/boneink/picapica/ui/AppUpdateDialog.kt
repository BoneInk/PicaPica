package com.boneink.picapica.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.boneink.picapica.BuildConfig
import com.boneink.picapica.update.AppUpdateState
import com.boneink.picapica.update.AppUpdateViewModel
import java.io.File

@Composable
internal fun AppUpdateDialog(state: AppUpdateState, model: AppUpdateViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var installError by rememberSaveable { mutableStateOf<String?>(null) }
    val ready = state as? AppUpdateState.Ready

    fun install(available: AppUpdateState.Ready) {
        installError = null
        if (!available.apk.isFile) {
            model.download(available.release)
            return
        }
        try {
            context.startActivity(updateInstallerIntent(context, available.apk))
        } catch (_: ActivityNotFoundException) {
            installError = "设备无法打开系统安装器。"
        } catch (_: SecurityException) {
            installError = "系统禁止了此次安装。"
        }
    }

    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) ready?.let(::install)
        else installError = "允许 PicaPica 安装应用后即可继续。"
    }

    LaunchedEffect(state) {
        if (state == AppUpdateState.Idle) model.check()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("app-update-dialog"),
        title = { Text("应用更新") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    AppUpdateState.Idle, AppUpdateState.Checking -> {
                        Text("正在检查更新…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    AppUpdateState.Current -> Text("当前已是最新版本（${BuildConfig.VERSION_NAME}）。")
                    is AppUpdateState.Available -> {
                        Text("发现新版本 ${state.release.version}，安装包 ${Formatter.formatFileSize(context, state.release.bytes)}。")
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.release.notesUrl)))
                            } catch (_: ActivityNotFoundException) {
                                installError = "设备无法打开发布说明。"
                            }
                        }) { Text("查看发布说明") }
                    }
                    is AppUpdateState.Downloading -> {
                        Text("正在下载 ${state.release.version}（${state.percent}%）")
                        LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    is AppUpdateState.Ready -> {
                        Text("${state.release.version} 已下载并验证，可以安装。")
                        if (!context.packageManager.canRequestPackageInstalls() && installError == null)
                            Text("首次更新需要在系统设置里允许 PicaPica 安装应用。")
                    }
                    is AppUpdateState.Failed -> Text(state.message)
                }
                installError?.let { Text(it) }
            }
        },
        confirmButton = {
            when (state) {
                is AppUpdateState.Available -> TextButton(onClick = { installError = null; model.download(state.release) },
                    modifier = Modifier.testTag("app-update-download")) { Text("下载更新") }
                is AppUpdateState.Ready -> TextButton(onClick = {
                    if (context.packageManager.canRequestPackageInstalls()) install(state)
                    else {
                        try {
                            requestPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                        } catch (_: ActivityNotFoundException) {
                            installError = "设备未提供安装权限设置。"
                        }
                    }
                }, modifier = Modifier.testTag("app-update-install")) { Text("安装更新") }
                is AppUpdateState.Failed -> TextButton(onClick = { state.release?.let(model::download) ?: model.check() },
                    modifier = Modifier.testTag("app-update-retry")) { Text("重试") }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

internal fun updateInstallerIntent(context: Context, apk: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
