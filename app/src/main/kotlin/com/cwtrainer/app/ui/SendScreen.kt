package com.cwtrainer.app.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.cwtrainer.app.AppContainer
import com.cwtrainer.app.data.Settings
import com.cwtrainer.app.session.SendController
import com.cwtrainer.core.send.TimingScore

@Composable
fun SendScreen(container: AppContainer, settings: Settings, onExit: () -> Unit) {
    val ctrl = remember { SendController(container) }
    DisposableEffect(ctrl) { onDispose { ctrl.close() } }
    LifecycleResumeEffect(ctrl) {
        ctrl.onResume()
        onPauseOrDispose { ctrl.onPause() }
    }
    KeepScreenOn()
    val view = LocalView.current
    val haptics = settings.haptics
    val buzz: () -> Unit = {
        if (haptics) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    Page("Send practice", onBack = onExit, scroll = false) {
        Segmented(listOf(false, true), ctrl.iambic, { if (it) "Iambic paddle (B)" else "Straight key" }, { ctrl.setMode(it) })
        Spacer(Modifier.height(8.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label("Try sending")
                        Text(ctrl.target, fontSize = 22.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    }
                    Chip("New", false, { ctrl.newTarget() })
                }
            }
            Spacer(Modifier.height(8.dp))
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Decoded", Modifier.weight(1f))
                    Text(if (ctrl.liveWpm > 0) "${ctrl.liveWpm} WPM" else "", color = Cw.Dim, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Chip("Clear", false, { ctrl.clear() })
                }
                Text(
                    ctrl.decoded.takeLast(80).ifEmpty { "…" },
                    fontSize = 24.sp, fontFamily = FontFamily.Monospace, lineHeight = 32.sp,
                    color = if (ctrl.decoded.isEmpty()) Cw.Dim else Cw.Text,
                )
            }
            Spacer(Modifier.height(8.dp))
            ScorePanel(ctrl.score)
            if (ctrl.iambic) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Key("−", { ctrl.changeKeyerWpm(ctrl.keyerWpm - 1) }, Modifier.width(56.dp))
                    Text("Keyer ${ctrl.keyerWpm} WPM", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 16.sp)
                    Key("+", { ctrl.changeKeyerWpm(ctrl.keyerWpm + 1) }, Modifier.width(56.dp))
                    Chip("Swap", ctrl.swapPaddles, { ctrl.toggleSwap() })
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // The key itself: at the bottom, where a thumb reaches it.
        if (ctrl.iambic) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PressArea(if (ctrl.swapPaddles) "DAH" else "DIT", Modifier.weight(1f).height(190.dp)) { down, _ ->
                    if (down) buzz()
                    ctrl.paddle(dit = true, pressed = down)
                }
                PressArea(if (ctrl.swapPaddles) "DIT" else "DAH", Modifier.weight(1f).height(190.dp)) { down, _ ->
                    if (down) buzz()
                    ctrl.paddle(dit = false, pressed = down)
                }
            }
        } else {
            PressArea("KEY", Modifier.fillMaxWidth().padding(bottom = 16.dp).height(190.dp)) { down, t ->
                if (down) buzz()
                ctrl.straight(down, t)
            }
        }
    }
}

@Composable
private fun ScorePanel(score: TimingScore?) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Timing")
                Text(
                    if (score == null) "Send a few characters" else "${score.score} / 100",
                    fontSize = if (score == null) 16.sp else 30.sp, fontWeight = FontWeight.Bold,
                    color = when {
                        score == null -> Cw.Dim
                        score.score >= 85 -> Cw.Good
                        score.score >= 65 -> Cw.Accent
                        else -> Cw.Bad
                    },
                )
            }
            if (score != null) Text("%.0f WPM".format(score.wpm), color = Cw.Dim, fontSize = 15.sp)
        }
        if (score != null) {
            Spacer(Modifier.height(8.dp))
            Ratio("Dash : dot", score.dashDotRatio, 3.0)
            Ratio("Gap inside a character", score.intraGapRatio, 1.0)
            Ratio("Gap between characters", score.charGapRatio, 3.0)
            Ratio("Gap between words", score.wordGapRatio, 7.0)
            val advice = advice(score)
            if (advice != null) {
                Spacer(Modifier.height(6.dp))
                Text(advice, color = Cw.Accent, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun Ratio(label: String, value: Double?, ideal: Double) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), color = Cw.Dim, fontSize = 14.sp)
        val off = if (value == null) 0.0 else Math.abs(value - ideal) / ideal
        Text(
            if (value == null) "—" else "%.1f".format(value),
            fontFamily = FontFamily.Monospace, fontSize = 14.sp,
            color = when { value == null -> Cw.Dim; off <= 0.15 -> Cw.Good; off <= 0.35 -> Cw.Accent; else -> Cw.Bad },
        )
        Text("  ideal %.0f".format(ideal), color = Cw.Dim, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

/** The single most useful thing to fix, if anything stands out. */
private fun advice(s: TimingScore): String? {
    val dash = s.dashDotRatio
    val chr = s.charGapRatio
    val wrd = s.wordGapRatio
    val variation = s.markVariation
    return when {
        dash != null && dash < 2.5 -> "Hold your dashes longer: three dots' worth."
        dash != null && dash > 3.6 -> "Your dashes are heavy. Shorten them toward three dots."
        chr != null && chr < 2.4 -> "Characters are running together. Leave three dots between them."
        chr != null && chr > 4.5 -> "Close up the gaps between characters."
        wrd != null && wrd < 5.5 -> "Leave a longer pause between words: seven dots."
        variation != null && variation > 0.2 -> "Element lengths vary a lot. Aim for an even rhythm."
        else -> null
    }
}

/** A touch surface that reports press and release with the touch event's own timestamps. */
@Composable
private fun PressArea(label: String, modifier: Modifier, onPress: (down: Boolean, uptimeMs: Long) -> Unit) {
    val callback = rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(if (pressed) Cw.Accent else Cw.SurfaceHi)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    callback.value(true, down.uptimeMillis)
                    val up = waitForUpOrCancellation()
                    pressed = false
                    callback.value(false, up?.uptimeMillis ?: SystemClock.uptimeMillis())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = if (pressed) Color.Black else Cw.Dim, letterSpacing = 4.sp)
    }
}
