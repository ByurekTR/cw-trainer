package com.cwtrainer.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cwtrainer.app.AppContainer
import com.cwtrainer.app.data.Settings
import com.cwtrainer.app.listen.ListenState
import com.cwtrainer.app.listen.ListenStatus
import com.cwtrainer.app.listen.ListeningController
import com.cwtrainer.app.listen.ListeningService
import com.cwtrainer.core.listen.PlaylistGenerator
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
fun ListeningScreen(container: AppContainer, settings: Settings, onBack: () -> Unit) {
    val listening = container.listening
    val state by listening.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update: ((Settings) -> Settings) -> Unit = { f -> scope.launch { container.repo.updateSettings(f) } }

    val begin: () -> Unit = {
        ListeningService.start(context, settings.listenMinutes, settings.listenGapSec, settings.listenReveal)
    }
    // Android 13+ asks before showing notifications. The session starts either way; without
    // the permission the controls may only appear on the lock screen and in the media panel.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { begin() }
    val start: () -> Unit = {
        val needsAsking = Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsking) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else begin()
    }

    Page("Background listening", onBack = onBack, scroll = false) {
        when (state.status) {
            ListenStatus.IDLE -> Setup(settings, update, start)
            ListenStatus.FINISHED -> Finished(state) { listening.acknowledge() }
            else -> NowPlaying(state, listening, context)
        }
    }
}

@Composable
private fun ColumnScope.Setup(settings: Settings, update: ((Settings) -> Settings) -> Unit, start: () -> Unit) {
    val kinds = remember(settings.kochLevel) {
        PlaylistGenerator(settings.kochLevel, settings.groupLength, 0L, Random(0)) { null }.kinds
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Panel {
            Text("Listen while you do something else. The session keeps playing with the screen off and has play, pause and skip controls in the notification and on the lock screen.", fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            Label("From your queue")
            Text(kinds.joinToString(", ") { it.label }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Weak, slow and due items come up more often. More kinds unlock with your Koch lesson (now ${settings.kochLevel}).",
                color = Cw.Dim, fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Panel {
            Label("Length")
            Spacer(Modifier.height(8.dp))
            Segmented(listOf(10, 20, 30), settings.listenMinutes, { "$it min" }, { m -> update { it.copy(listenMinutes = m) } })
            Spacer(Modifier.height(8.dp))
            SliderRow(
                "Gap after each item", settings.listenGapSec.toFloat(), 0f..10f, steps = 9,
                text = { "${it.roundToInt()} s" },
            ) { v -> update { it.copy(listenGapSec = v.roundToInt()) } }
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                "Reveal the text afterwards",
                "After each item has finished, its text appears on this screen. Nothing is spoken.",
                settings.listenReveal,
            ) { on -> update { it.copy(listenReveal = on) } }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "${settings.charWpm} WPM characters, ${wpmText(settings.effectiveWpm())} WPM effective, band: ${settings.band}. " +
                "Change these in Settings. Listening time counts toward your daily minutes and streak.",
            color = Cw.Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
    BigButton("Start listening", start, Modifier.fillMaxWidth().padding(bottom = 16.dp), height = 68.dp, fontSize = 20.sp)
}

@Composable
private fun ColumnScope.NowPlaying(state: ListenState, listening: ListeningController, context: android.content.Context) {
    val playing = state.status == ListenStatus.PLAYING
    Panel {
        Label(if (playing) "Playing" else "Paused")
        Text(
            "${clock(state.elapsedSec)} / ${clock(state.totalSec)}",
            fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            color = if (playing) Cw.Text else Cw.Dim,
        )
        Spacer(Modifier.height(10.dp))
        Meter(if (state.totalSec == 0) 0f else state.elapsedSec.toFloat() / state.totalSec)
        Spacer(Modifier.height(8.dp))
        Text("${state.itemsPlayed} items played. You can turn the screen off.", color = Cw.Dim, fontSize = 14.sp)
    }
    Spacer(Modifier.height(12.dp))
    Panel(Modifier.weight(1f)) {
        Label(if (state.reveal) "Heard so far (newest first)" else "Reveal is off")
        Spacer(Modifier.height(6.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (!state.reveal) {
                Text("Just listen. Turn reveal on before the next session to check yourself.", color = Cw.Dim, fontSize = 14.sp)
            } else if (state.revealed.isEmpty()) {
                Text("Each item's text appears here once it has finished sounding.", color = Cw.Dim, fontSize = 14.sp)
            }
            state.revealed.asReversed().forEachIndexed { i, text ->
                Text(
                    text, fontFamily = FontFamily.Monospace, fontSize = if (i == 0) 20.sp else 15.sp,
                    fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (i == 0) Cw.Accent else Cw.Dim, modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuietButton("Stop", { listening.stop() }, Modifier.weight(1f), height = 68.dp)
        QuietButton("Skip", { listening.skip() }, Modifier.weight(1f), height = 68.dp)
        // Play goes through the service so that audio focus is requested again.
        BigButton(
            if (playing) "Pause" else "Play",
            { if (playing) listening.pause() else ListeningService.resume(context) },
            Modifier.weight(1.4f), height = 68.dp,
        )
    }
}

@Composable
private fun ColumnScope.Finished(state: ListenState, done: () -> Unit) {
    Column(Modifier.weight(1f)) {
        Panel {
            Label("Session complete")
            Text("${state.elapsedSec / 60} minutes", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Cw.Good)
            Text("${state.itemsPlayed} items. Added to today's minutes.", color = Cw.Dim, fontSize = 14.sp)
        }
    }
    BigButton("Done", done, Modifier.fillMaxWidth().padding(bottom = 16.dp), height = 64.dp)
}
