package com.cwtrainer.app.ui

import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cwtrainer.app.AppContainer
import com.cwtrainer.app.CwApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Volume keys always adjust media volume, even while nothing is sounding.
        volumeControlStream = AudioManager.STREAM_MUSIC
        val container = (application as CwApp).container
        setContent {
            CwTheme { App(container) }
        }
    }

    override fun onStop() {
        super.onStop()
        // Belt and braces: never keep sounding with the app in the background (v1 has no service).
        (application as CwApp).container.player.stop()
    }
}

sealed interface Route {
    data object Home : Route
    data class Drill(val id: String) : Route
    data object Progress : Route
    data object Reference : Route
    data object Settings : Route
}

/** A tiny back stack is all the navigation this app needs. */
@Composable
fun App(container: AppContainer) {
    val settings by container.repo.settings.collectAsStateWithLifecycle()
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1, onBack = back)

    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Cw.Bg))
        return
    }
    when (val route = stack[stack.lastIndex]) {
        Route.Home -> HomeScreen(container, s) { stack.add(it) }
        is Route.Drill -> DrillScreen(container, s, route.id, back)
        Route.Progress -> ProgressScreen(container, s, back)
        Route.Reference -> ReferenceScreen(container, s, back)
        Route.Settings -> SettingsScreen(container, s, back)
    }
}
