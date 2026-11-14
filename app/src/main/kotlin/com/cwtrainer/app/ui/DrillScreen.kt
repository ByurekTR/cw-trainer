package com.cwtrainer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.cwtrainer.app.AppContainer
import com.cwtrainer.app.data.Settings
import com.cwtrainer.app.session.DrillController
import com.cwtrainer.app.session.Phase
import com.cwtrainer.app.session.Summary
import com.cwtrainer.core.drill.DrillMode
import com.cwtrainer.core.drill.Drills
import com.cwtrainer.core.drill.Keyboard
import com.cwtrainer.core.drill.SymbolResult
import com.cwtrainer.core.koch.Koch
import com.cwtrainer.core.morse.MorseCode
import kotlinx.coroutines.delay

@Composable
fun DrillScreen(container: AppContainer, settings: Settings, drillId: String, onExit: () -> Unit, quick: Boolean = false) {
    val drill = remember(drillId) { Drills.byId(drillId) }
    if (drill.mode == DrillMode.SEND) {
        SendScreen(container, settings, onExit)
        return
    }
    // "Again" starts a fresh session by changing the key.
    var run by remember { mutableIntStateOf(0) }
    key(run) {
        // A quick drill (from the widget) is two minutes and starts by itself, the first time only.
        val ctrl = remember { DrillController(container, drillId, if (quick) QUICK_SECONDS else null) }
        LaunchedEffect(ctrl) { if (quick && run == 0) ctrl.start() }
        DisposableEffect(ctrl) { onDispose { ctrl.close() } }
        LifecycleResumeEffect(ctrl) {
            ctrl.onResume()
            onPauseOrDispose { ctrl.onPause() }
        }
        LaunchedEffect(ctrl) {
            while (true) {
                delay(1000)
                ctrl.tick()
            }
        }
        KeepScreenOn()

        Page(drill.title, onBack = onExit, scroll = false) {
            when (ctrl.phase) {
                Phase.READY -> Ready(ctrl)
                Phase.ACTIVE, Phase.FEEDBACK -> {
                    StatusRow(ctrl)
                    when (drill.mode) {
                        DrillMode.COPY -> CopyBody(ctrl)
                        DrillMode.RECOGNISE -> RecogniseBody(ctrl)
                        else -> HeadCopyBody(ctrl)
                    }
                }
                Phase.DONE -> SummaryBody(ctrl, onExit) { run++ }
            }
        }
    }
}

private const val QUICK_SECONDS = 120

// ---- before the session ---------------------------------------------------------------------

@Composable
private fun ColumnScope.Ready(ctrl: DrillController) {
    val drill = ctrl.drill
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Panel {
            Text(drill.summary, fontSize = 17.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "Lesson ${ctrl.level} of ${Koch.MAX_LEVEL}  ·  ${wpmText(ctrl.charWpm)} WPM characters, " +
                    "${wpmText(ctrl.effWpm)} WPM effective  ·  ${ctrl.sessionSeconds / 60} min",
                color = Cw.Dim, fontSize = 14.sp,
            )
            if (drill.keyboard == Keyboard.UNLOCKED) {
                Spacer(Modifier.height(12.dp))
                Label("Your characters")
                Text(
                    Koch.unlocked(ctrl.level).joinToString("  "),
                    fontSize = 20.sp, fontFamily = FontFamily.Monospace, lineHeight = 30.sp,
                )
            }
        }
        val fresh = ctrl.newCharacter
        if (fresh != null) {
            Spacer(Modifier.height(12.dp))
            Panel(color = Cw.SurfaceHi) {
                Label("New character")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fresh, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Cw.Accent, modifier = Modifier.width(96.dp))
                    QuietButton(if (ctrl.playing) "Playing…" else "Hear it", { ctrl.preview(fresh) }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Text("Listen until you know its sound as one shape. Do not count dots and dashes.", color = Cw.Dim, fontSize = 14.sp)
            }
        }
        if (drill.mode == DrillMode.HEAD_COPY && ctrl.charWpm < 15) {
            Spacer(Modifier.height(12.dp))
            Text("Head copy works best at 15 WPM character speed or more.", color = Cw.Dim, fontSize = 14.sp)
        }
        if (drill.usesBand) {
            Spacer(Modifier.height(12.dp))
            Panel(onClick = { ctrl.cycleBand() }) {
                Label("Band conditions (tap to change)")
                Text(ctrl.band.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    BigButton("Start", { ctrl.start() }, Modifier.fillMaxWidth().padding(bottom = 16.dp), height = 68.dp, fontSize = 20.sp)
}

// ---- during the session ---------------------------------------------------------------------

@Composable
private fun StatusRow(ctrl: DrillController) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "${clock(ctrl.elapsed)} / ${clock(ctrl.sessionSeconds)}" +
                    if (ctrl.total > 0) "   ${percent(ctrl.correct.toDouble() / ctrl.total)}" else "",
                fontSize = 15.sp, color = Cw.Dim, fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(6.dp))
            Meter(ctrl.elapsed.toFloat() / ctrl.sessionSeconds, height = 4.dp)
        }
        Spacer(Modifier.width(12.dp))
        if (ctrl.drill.usesBand) Chip(ctrl.band.name, ctrl.band.snrDb != null, { ctrl.cycleBand() })
        Spacer(Modifier.width(8.dp))
        Chip("Finish", false, { ctrl.finish() })
    }
}

private fun resultText(results: List<SymbolResult>): AnnotatedString = buildAnnotatedString {
    for (r in results) {
        withStyle(SpanStyle(color = if (r.correct) Cw.Good else Cw.Bad)) { append(MorseCode.display(r.symbol)) }
        append(if (MorseCode.isProsign(r.symbol)) " " else "")
    }
}

private fun plain(symbols: List<String>): String =
    symbols.joinToString("") { if (MorseCode.isProsign(it)) " ${MorseCode.display(it)} " else it }

@Composable
private fun ColumnScope.CopyBody(ctrl: DrillController) {
    val feedback = ctrl.phase == Phase.FEEDBACK
    val prompt = ctrl.prompt
    Panel(Modifier.weight(1f)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (feedback && prompt != null) {
                Label("Sent")
                // Shown as words where the text has them, coloured per character.
                Text(resultText(ctrl.results), fontSize = 26.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp)
                Text(prompt.send.replace("<", "").replace(">", ""), color = Cw.Dim, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(10.dp))
                Label("You typed")
                Text(plain(ctrl.typed).ifBlank { "—" }, fontSize = 22.sp, fontFamily = FontFamily.Monospace, color = Cw.Dim, lineHeight = 30.sp)
                if (prompt.hint.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("${prompt.send}: ${prompt.hint}", color = Cw.Accent, fontSize = 15.sp)
                }
            } else {
                Label(if (ctrl.playing) "Listening… type what you hear" else "Type what you heard, then Check")
                Text(
                    plain(ctrl.typed) + "▁",
                    fontSize = 30.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, lineHeight = 38.sp,
                )
            }
        }
        if (ctrl.fistName != null) Text("Sender: ${ctrl.fistName}", color = Cw.Dim, fontSize = 13.sp)
    }
    Spacer(Modifier.height(8.dp))
    if (feedback) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton("Hear again", { ctrl.replay(countAsReplay = false) }, Modifier.weight(1f), height = 64.dp)
            BigButton("Next", { ctrl.next() }, Modifier.weight(2f), height = 64.dp)
        }
    } else {
        CopyKeyboard(ctrl)
    }
}

@Composable
private fun CopyKeyboard(ctrl: DrillController) {
    val rows: List<List<String>> = remember(ctrl.level, ctrl.drill.keyboard) {
        if (ctrl.drill.keyboard == Keyboard.FULL) {
            listOf(
                "1234567890".map { it.toString() },
                "QWERTYUIOP".map { it.toString() },
                "ASDFGHJKL".map { it.toString() },
                "ZXCVBNM".map { it.toString() } + listOf("/", "?"),
                listOf(".", ",", "=", "<AR>", "<SK>", "<BT>", "<KN>"),
            )
        } else {
            val unlocked = Koch.unlocked(ctrl.level)
            // Few keys: keep Koch order, big. Many keys: letters, digits, punctuation, so they are easy to find.
            val ordered = if (unlocked.size <= 8) unlocked else
                unlocked.filter { it[0].isLetter() }.sorted() + unlocked.filter { it[0].isDigit() }.sorted() +
                    unlocked.filter { !it[0].isLetterOrDigit() }
            val perRow = when {
                ordered.size <= 4 -> ordered.size
                ordered.size <= 8 -> 4
                ordered.size <= 18 -> 6
                else -> 7
            }
            ordered.chunked(perRow)
        }
    }
    val full = ctrl.drill.keyboard == Keyboard.FULL
    val keyHeight = when {
        full -> 46.dp
        rows.size <= 2 -> 72.dp
        rows.size <= 4 -> 56.dp
        else -> 48.dp
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (full) 4.dp else 6.dp)) {
                for (k in row) {
                    Key(
                        MorseCode.display(k), { ctrl.type(k) }, Modifier.weight(1f), height = keyHeight,
                        fontSize = if (full) 17.sp else if (rows.size <= 2) 28.sp else 21.sp,
                    )
                }
                // Keep keys the same width on a short last row.
                if (!full) repeat(rows[0].size - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key("↻", { ctrl.replay() }, Modifier.weight(1f), height = 60.dp)
            Key("space", { ctrl.type(" ") }, Modifier.weight(2f), height = 60.dp, fontSize = 15.sp, textColor = Cw.Dim)
            Key("⌫", { ctrl.backspace() }, Modifier.weight(1f), height = 60.dp)
            Key("Check", { ctrl.check() }, Modifier.weight(2f), height = 60.dp, color = Cw.Accent, textColor = Color.Black, fontSize = 18.sp)
        }
    }
}

@Composable
private fun ColumnScope.RecogniseBody(ctrl: DrillController) {
    val prompt = ctrl.prompt ?: return
    val feedback = ctrl.phase == Phase.FEEDBACK
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (feedback) {
                Text(
                    MorseCode.display(prompt.send), fontSize = 72.sp, fontWeight = FontWeight.Bold,
                    color = if (ctrl.lastCorrect) Cw.Good else Cw.Bad,
                )
                val rt = ctrl.lastRtMs
                Text(
                    when {
                        !ctrl.lastCorrect -> "You tapped ${MorseCode.display(ctrl.chosen ?: "")}. Listen again."
                        rt != null -> "$rt ms"
                        else -> "Correct"
                    },
                    color = Cw.Dim, fontSize = 16.sp,
                )
                if (prompt.hint.isNotEmpty()) Text(prompt.hint, color = Cw.Accent, fontSize = 15.sp, textAlign = TextAlign.Center)
            } else {
                Text(if (ctrl.playing) "♪" else "?", fontSize = 72.sp, color = Cw.Dim)
                Text("Tap what you hear", color = Cw.Dim, fontSize = 16.sp)
            }
        }
    }
    val choices = prompt.choices
    val wide = choices.any { it.length > 1 && !MorseCode.isProsign(it) }
    val perRow = when {
        wide -> 2
        choices.size <= 4 -> choices.size.coerceAtLeast(1)
        choices.size <= 6 -> 3
        choices.size <= 12 -> 4
        choices.size <= 24 -> 6
        else -> 7
    }
    val rows = choices.chunked(perRow)
    val keyHeight = when {
        rows.size <= 2 -> 76.dp
        rows.size <= 4 -> 60.dp
        else -> 50.dp
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (choice in row) {
                    val isAnswer = feedback && choice == prompt.send
                    val isMiss = feedback && choice == ctrl.chosen && !ctrl.lastCorrect
                    Key(
                        MorseCode.display(choice), { ctrl.choose(choice) }, Modifier.weight(1f),
                        enabled = !feedback, height = keyHeight,
                        color = when { isAnswer -> Cw.Good; isMiss -> Cw.Bad; else -> Cw.SurfaceHi },
                        textColor = if (isAnswer || isMiss) Color.Black else Cw.Text,
                        fontSize = if (wide) 20.sp else if (rows.size <= 2) 28.sp else 22.sp,
                    )
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuietButton("Hear again", { ctrl.replay(countAsReplay = !feedback) }, Modifier.weight(1f))
        BigButton("Next", { ctrl.next() }, Modifier.weight(1f), enabled = feedback && !ctrl.lastCorrect)
    }
}

@Composable
private fun ColumnScope.HeadCopyBody(ctrl: DrillController) {
    val prompt = ctrl.prompt ?: return
    Panel(Modifier.weight(1f)) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (ctrl.revealed) {
                    Label(prompt.kind)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        prompt.send.replace("<", "").replace(">", ""),
                        fontSize = if (prompt.send.length > 24) 22.sp else 34.sp,
                        fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center, lineHeight = if (prompt.send.length > 24) 30.sp else 42.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Did you have it in your head before the reveal?", color = Cw.Dim, fontSize = 15.sp, textAlign = TextAlign.Center)
                } else {
                    Text(if (ctrl.playing) "♪" else "…", fontSize = 72.sp, color = Cw.Dim)
                    Text(
                        "No typing, no writing. Hold it in your head, then reveal.",
                        color = Cw.Dim, fontSize = 16.sp, textAlign = TextAlign.Center,
                    )
                }
            }
        }
        if (ctrl.fistName != null) Text("Sender: ${ctrl.fistName}", color = Cw.Dim, fontSize = 13.sp)
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ctrl.revealed) {
            BigButton("✗  Missed", { ctrl.mark(false) }, Modifier.weight(1f), color = Cw.Bad, height = 72.dp)
            BigButton("✓  Got it", { ctrl.mark(true) }, Modifier.weight(1f), color = Cw.Good, height = 72.dp)
        } else {
            QuietButton("Hear again", { ctrl.replay() }, Modifier.weight(1f), height = 72.dp)
            BigButton("Reveal", { ctrl.reveal() }, Modifier.weight(1f), height = 72.dp)
        }
    }
}

// ---- after the session ----------------------------------------------------------------------

@Composable
private fun ColumnScope.SummaryBody(ctrl: DrillController, onExit: () -> Unit, onAgain: () -> Unit) {
    val s: Summary = ctrl.summary ?: return
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Panel {
            Label("Session")
            Text(
                if (s.total == 0) "No answers" else percent(s.accuracy),
                fontSize = 48.sp, fontWeight = FontWeight.Bold,
                color = when { s.total == 0 -> Cw.Dim; s.accuracy >= 0.9 -> Cw.Good; else -> Cw.Accent },
            )
            Text(
                "${s.correct} of ${s.total} correct  ·  ${clock(s.seconds)}  ·  " +
                    "${wpmText(s.charWpm)}/${wpmText(s.effWpm)} WPM",
                color = Cw.Dim, fontSize = 14.sp,
            )
        }
        val unlocked = s.unlocked
        if (unlocked != null) {
            Spacer(Modifier.height(12.dp))
            Panel(color = Cw.SurfaceHi) {
                Label("Unlocked")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(unlocked, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Cw.Accent, modifier = Modifier.width(96.dp))
                    QuietButton(if (ctrl.playing) "Playing…" else "Hear it", { ctrl.preview(unlocked) }, Modifier.weight(1f))
                }
                Text("90 % or better over a full session: the next character joins your set.", color = Cw.Dim, fontSize = 14.sp)
            }
        } else if (s.unlockHint != null) {
            Spacer(Modifier.height(12.dp))
            Text(s.unlockHint, color = Cw.Dim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (s.symbols.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Panel {
                Label("Accuracy, weakest first")
                Spacer(Modifier.height(4.dp))
                for (st in s.symbols.take(41)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(MorseCode.display(st.symbol), fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(88.dp), maxLines = 1)
                        Meter(st.accuracy.toFloat(), Modifier.weight(1f), color = if (st.accuracy >= 0.9) Cw.Good else if (st.accuracy >= 0.7) Cw.Accent else Cw.Bad)
                        Text("${st.correct}/${st.total}", color = Cw.Dim, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuietButton("Again", onAgain, Modifier.weight(1f), height = 64.dp)
        BigButton("Done", onExit, Modifier.weight(1f), height = 64.dp)
    }
}
