package com.boneink.picapica

import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boneink.picapica.data.AppTheme
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import com.boneink.picapica.ui.PicaPicaScreen
import com.boneink.picapica.ui.PicaTheme
import com.boneink.picapica.ui.PicaViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    var readerVolumeHandler: ((Int) -> Unit)? = null
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
            val factory = remember(container) { object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = PicaViewModel(container) as T
            } }
            val model: PicaViewModel = viewModel(factory = factory)
            val preferences by container.settings.values.collectAsStateWithLifecycle()
            val dark = when (preferences.theme) { AppTheme.SYSTEM -> isSystemInDarkTheme(); AppTheme.DARK -> true; AppTheme.LIGHT -> false }
            PicaTheme(darkTheme = dark) { PicaPicaScreen(model) }
        }
    }
}
