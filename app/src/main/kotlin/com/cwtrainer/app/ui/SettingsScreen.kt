package com.cwtrainer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cwtrainer.app.AppContainer
import com.cwtrainer.app.data.Settings
import com.cwtrainer.core.audio.BandConditions
import com.cwtrainer.core.koch.Koch
import com.cwtrainer.core.morse.Keyer
import com.cwtrainer.core.morse.Timing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(container: AppContainer, settings: Settings, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val update: ((Settings) -> Settings) -> Unit = { f -> scope.launch { container.repo.updateSettings(f) } }
    var confirmReset by remember { mutableStateOf(false) }
    val lettersDone = Koch.allLettersLearned(settings.kochLevel)
    // The pitch slider plays a preview; make sure the audio thread is not left running afterwards.
    DisposableEffect(Unit) { onDispose { container.player.stop() } }

    Page("Settings", onBack = onBack) {
        Panel {
            Label("Speed")
            SliderRow(
                "Character speed", settings.charWpm.toFloat(), 15f..30f, steps = 14,
                text = { "${it.roundToInt()} WPM" },
            ) { v -> update { it.copy(charWpm = v.roundToInt()) } }
            Text(
                "Characters always play at this speed. Keep it at 20 or more so you learn sounds, not dots and dashes.",
                color = Cw.Dim, fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            ToggleRow(
                "Effective speed: automatic",
                "Starts at 8 WPM and tightens with every character you unlock. Now: ${wpmText(Koch.effectiveWpm(settings.kochLevel, settings.charWpm.toDouble()))} WPM",
                settings.effOverride <= 0f,
            ) { auto -> update { it.copy(effOverride = if (auto) 0f else it.effectiveWpm().toFloat()) } }
            if (settings.effOverride > 0f) {
                SliderRow(
                    "Effective speed override", settings.effOverride.coerceIn(5f, settings.charWpm.toFloat()),
                    5f..settings.charWpm.toFloat(), steps = (settings.charWpm - 5) * 2 - 1,
                    text = { "${wpmText((it * 2).roundToInt() / 2.0)} WPM" },
                ) { v -> update { it.copy(effOverride = (v * 2).roundToInt() / 2f) } }
            }
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            Label("Sound")
            SliderRow(
                "Tone pitch", settings.pitchHz.toFloat(), 400f..900f, steps = 49,
                text = { "${(it / 10).roundToInt() * 10} Hz" },
            ) { v ->
                val hz = (v / 10).roundToInt() * 10
                update { it.copy(pitchHz = hz) }
                // Let the new pitch be heard straight away.
                val p = container.player
                p.engine.pitchHz = hz.toDouble()
                p.engine.conditions = BandConditions.CLEAN
                p.start()
                p.engine.play(Keyer.plan("K", Timing(settings.charWpm.toDouble())), leadInMs = 40.0)
            }
            Text("Volume follows your phone's media volume and works with headphones.", color = Cw.Dim, fontSize = 13.sp)
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            Label("Sessions")
            SliderRow(
                "Group length", settings.groupLength.toFloat(), 2f..8f, steps = 5,
                text = { "${it.roundToInt()} characters" },
            ) { v -> update { it.copy(groupLength = v.roundToInt()) } }
            Spacer(Modifier.height(10.dp))
            Text("Session length", fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Segmented(listOf(5, 10, 15, 20), settings.sessionMinutes, { "$it min" }, { m -> update { it.copy(sessionMinutes = m) } })
            Spacer(Modifier.height(6.dp))
            Text("A new character unlocks after 90 % or better over a 5-minute receive session.", color = Cw.Dim, fontSize = 13.sp)
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            Label("Band conditions (receive drills)")
            Spacer(Modifier.height(8.dp))
            for (row in BandConditions.presets.chunked(2)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (b in row) Chip(b.name, settings.band == b.name, { update { it.copy(band = b.name) } }, Modifier.weight(1f))
                }
            }
            val band = BandConditions.byName(settings.band)
            Text(
                when {
                    band.snrDb == null -> "A pure tone with nothing else."
                    else -> listOfNotNull(
                        "Noise at ${band.snrDb!!.roundToInt()} dB SNR",
                        if (band.staticCrashesPerMin > 0) "static crashes" else null,
                        if (band.qsbDepthDb > 0) "fading up to ${band.qsbDepthDb.roundToInt()} dB" else null,
                        band.qrmLevelDb?.let { "a second station ${Math.abs(band.qrmOffsetHz).roundToInt()} Hz away" },
                        if (band.driftHz > 0) "slight drift and chirp" else null,
                    ).joinToString(", ") + "."
                },
                color = Cw.Dim, fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            ToggleRow(
                "Human fist simulation",
                if (lettersDone) "Senders with imperfect timing: heavy dashes, rushed spacing, slow and deliberate."
                else "Stays on machine-perfect timing until you have learned all letters.",
                settings.fist,
            ) { on -> update { it.copy(fist = on) } }
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            Label("Sending")
            ToggleRow("Haptic feedback", "A tick under your finger on every key press.", settings.haptics) { on -> update { it.copy(haptics = on) } }
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            Label("Koch lesson")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key("−", { update { it.copy(kochLevel = Koch.clamp(it.kochLevel - 1)) } }, Modifier.width(64.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${settings.kochLevel} of ${Koch.MAX_LEVEL}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("newest: ${Koch.newest(settings.kochLevel)}", color = Cw.Dim, fontSize = 13.sp)
                }
                Key("+", { update { it.copy(kochLevel = Koch.clamp(it.kochLevel + 1)) } }, Modifier.width(64.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Lessons advance on their own. Change this only to skip ahead if you already know some characters, or to step back.",
                color = Cw.Dim, fontSize = 13.sp,
            )
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            Label("Data")
            Text(
                "Everything stays on this phone. The app has no internet permission, no accounts, no analytics and no ads.",
                color = Cw.Dim, fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            BigButton("Reset all progress", { confirmReset = true }, Modifier.fillMaxWidth(), color = Cw.SurfaceHi, textColor = Cw.Bad, height = 52.dp)
        }
        Spacer(Modifier.height(16.dp))
        Text("CW Trainer 1.0", color = Cw.Dim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all progress?") },
            text = { Text("This deletes your lesson level, every session and all spaced-repetition history. Settings are kept. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch { container.repo.resetProgress() }
                }) { Text("Reset", color = Cw.Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Keep my progress") } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(subtitle, color = Cw.Dim, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Slider that shows its value while dragging and saves once, on release. */
@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    text: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var live by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(text(live), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Cw.Accent)
        }
        Slider(
            value = live,
            onValueChange = { live = it },
            valueRange = range,
            steps = steps.coerceAtLeast(0),
            onValueChangeFinished = { onCommit(live) },
            modifier = Modifier.height(48.dp),
        )
    }
}
