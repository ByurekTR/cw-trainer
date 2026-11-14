package com.cwtrainer.app.ui

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
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
        if (savedInstanceState == null) handle(intent)
        setContent {
            CwTheme { App(container, launch.value) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Widget button and notification tap arrive as intents with one of the actions below. */
    private fun handle(intent: Intent?) {
        val action = intent?.action ?: return
        if (action == ACTION_QUICK_DRILL || action == ACTION_OPEN_LISTENING) {
            launch.value = LaunchRequest(action, (launch.value?.serial ?: 0) + 1)
        }
    }

    private val launch = mutableStateOf<LaunchRequest?>(null)

    override fun onStop() {
        super.onStop()
        // Nothing sounds with the app in the background, except a listening session,
        // which is kept alive by ListeningService.
        val container = (application as CwApp).container
        if (!container.listening.isActive) container.player.stop()
    }

    companion object {
        const val ACTION_QUICK_DRILL = "com.cwtrainer.app.QUICK_DRILL"
        const val ACTION_OPEN_LISTENING = "com.cwtrainer.app.OPEN_LISTENING"
        const val QUICK_DRILL_ID = "chars"
    }
}

data class LaunchRequest(val action: String, val serial: Int)

sealed interface Route {
    data object Home : Route
    data class Drill(val id: String, val quick: Boolean = false) : Route
    data object Listening : Route
    data object Data : Route
    data object Progress : Route
    data object Reference : Route
    data object Settings : Route
}

/** A tiny back stack is all the navigation this app needs. */
@Composable
fun App(container: AppContainer, request: LaunchRequest? = null) {
    val settings by container.repo.settings.collectAsStateWithLifecycle()
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1, onBack = back)

    LaunchedEffect(request) {
        val target: Route = when (request?.action) {
            MainActivity.ACTION_QUICK_DRILL -> Route.Drill(MainActivity.QUICK_DRILL_ID, quick = true)
            MainActivity.ACTION_OPEN_LISTENING -> Route.Listening
            else -> return@LaunchedEffect
        }
        stack.clear()
        stack.add(Route.Home)
        stack.add(target)
    }

    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Cw.Bg))
        return
    }
    when (val route = stack[stack.lastIndex]) {
        Route.Home -> HomeScreen(container, s) { stack.add(it) }
        is Route.Drill -> DrillScreen(container, s, route.id, back, route.quick)
        Route.Listening -> ListeningScreen(container, s, back)
        Route.Data -> DataScreen(container, back)
        Route.Progress -> ProgressScreen(container, s, back)
        Route.Reference -> ReferenceScreen(container, s, back)
        Route.Settings -> SettingsScreen(container, s, back) { stack.add(Route.Data) }
    }
}
