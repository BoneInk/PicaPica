package com.boneink.picapica.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

internal sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object Current : AppUpdateState
    data class Available(val release: UpdateRelease) : AppUpdateState
    data class Downloading(val release: UpdateRelease, val percent: Int) : AppUpdateState
    data class Ready(val release: UpdateRelease, val apk: File) : AppUpdateState
    data class Failed(val message: String, val release: UpdateRelease? = null) : AppUpdateState
}

internal class AppUpdateViewModel(application: Application, private val updates: GitHubUpdates) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, GitHubUpdates(application))

    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state = mutableState.asStateFlow()

    fun check() {
        if (state.value is AppUpdateState.Checking || state.value is AppUpdateState.Downloading) return
        mutableState.value = AppUpdateState.Checking
        viewModelScope.launch {
            try {
                mutableState.value = updates.check()?.let(AppUpdateState::Available) ?: AppUpdateState.Current
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = AppUpdateState.Failed((error as? UpdateException)?.message ?: "检查更新失败，请检查网络后重试。")
            }
        }
    }

    fun download(release: UpdateRelease) {
        if (state.value is AppUpdateState.Checking || state.value is AppUpdateState.Downloading) return
        mutableState.value = AppUpdateState.Downloading(release, 0)
        viewModelScope.launch {
            try {
                val apk = updates.download(release) { percent -> mutableState.value = AppUpdateState.Downloading(release, percent) }
                mutableState.value = AppUpdateState.Ready(release, apk)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = AppUpdateState.Failed(
                    (error as? UpdateException)?.message ?: "下载失败，请检查网络和可用空间后重试。", release,
                )
            }
        }
    }
}
