package com.boneink.picapica

import android.os.Bundle
import android.os.Build
import android.view.KeyEvent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boneink.picapica.data.AppTheme
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import com.boneink.picapica.ui.PicaPicaScreen
import com.boneink.picapica.ui.PicaTheme
import com.boneink.picapica.ui.PicaViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    var readerVolumeHandler: ((Int) -> Unit)? = null
    override fun onResume() {
        super.onResume()
        @Suppress("DEPRECATION")
        val currentDisplay = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        val mode = currentDisplay?.mode
        val rate = currentDisplay?.supportedModes?.filter { it.physicalWidth == mode?.physicalWidth && it.physicalHeight == mode.physicalHeight }
            ?.maxOfOrNull { it.refreshRate } ?: 0f
        // A preference only: Android retains control over battery, thermal and user limits.
        window.attributes = window.attributes.apply { preferredRefreshRate = if (rate > 60f) rate else 0f }
    }
    override fun onPause() {
        window.attributes = window.attributes.apply { preferredRefreshRate = 0f }
        super.onPause()
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handler = readerVolumeHandler
        if (handler != null && keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.repeatCount == 0) handler(if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (readerVolumeHandler != null && keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val container = (application as PicaPicaApp).container
            val activeAccount by container.accounts.active.collectAsStateWithLifecycle()
            key(activeAccount.id) {
                val factory = remember(container, activeAccount.id) { object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = PicaViewModel(container) { this@MainActivity.viewModelStore.clear() } as T
                } }
                val model: PicaViewModel = viewModel(factory = factory)
                val preferences by container.settings.values.collectAsStateWithLifecycle()
                val dark = when (preferences.theme) { AppTheme.SYSTEM -> isSystemInDarkTheme(); AppTheme.DARK -> true; AppTheme.LIGHT -> false }
                PicaTheme(darkTheme = dark) { PicaPicaScreen(model) }
            }
        }
    }
}
