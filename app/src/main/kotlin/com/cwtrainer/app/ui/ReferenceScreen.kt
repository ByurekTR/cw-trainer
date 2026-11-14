package com.cwtrainer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.cwtrainer.app.AppContainer
import com.cwtrainer.app.data.Settings
import com.cwtrainer.core.audio.BandConditions
import com.cwtrainer.core.content.Content
import com.cwtrainer.core.morse.Keyer
import com.cwtrainer.core.morse.MorseCode
import com.cwtrainer.core.morse.Timing

/** The only place in the app where dot/dash patterns are ever shown. */
@Composable
fun ReferenceScreen(container: AppContainer, settings: Settings, onBack: () -> Unit) {
    val player = container.player
    LifecycleResumeEffect(player) {
        container.listening.stop()
        player.engine.listener = null
        player.engine.pitchHz = settings.pitchHz.toDouble()
        player.engine.conditions = BandConditions.CLEAN
        player.engine.setKeyer(null)
        player.start()
        onPauseOrDispose { player.stop() }
    }
    DisposableEffect(player) { onDispose { player.engine.stopPlayback() } }

    val timing = Timing(settings.charWpm.toDouble())
    val play: (String) -> Unit = { text ->
        player.start()
        player.engine.play(Keyer.plan(text, timing), leadInMs = 60.0)
    }
    fun pretty(pattern: String) = pattern.replace('.', '·').replace('-', '–')

    Page("Reference", onBack = onBack) {
        Text("Tap anything to hear it at ${settings.charWpm} WPM.", color = Cw.Dim, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))

        val groups = listOf(
            "Letters" to MorseCode.characters.keys.filter { it[0].isLetter() },
            "Digits" to MorseCode.characters.keys.filter { it[0].isDigit() },
            "Punctuation" to MorseCode.characters.keys.filter { !it[0].isLetterOrDigit() },
        )
        for ((title, symbols) in groups) {
            Label(title, Modifier.padding(start = 4.dp, bottom = 8.dp))
            for (row in symbols.chunked(3)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (sym in row) {
                        Panel(Modifier.weight(1f), onClick = { play(sym) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(sym, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
                                Text(
                                    pretty(MorseCode.pattern(sym) ?: ""), color = Cw.Accent, fontSize = 20.sp,
                                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1,
                                )
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Label("Prosigns (sent as one character)", Modifier.padding(start = 4.dp, bottom = 8.dp))
        for (p in MorseCode.prosigns) {
            Panel(Modifier.padding(bottom = 8.dp), onClick = { play(p.symbol) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(MorseCode.display(p.symbol), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pretty(p.pattern), color = Cw.Accent, fontSize = 20.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text(p.meaning, color = Cw.Dim, fontSize = 14.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Label("Q-codes", Modifier.padding(start = 4.dp, bottom = 8.dp))
        TermList(Content.qCodes, play)
        Spacer(Modifier.height(12.dp))
        Label("Abbreviations", Modifier.padding(start = 4.dp, bottom = 8.dp))
        TermList(Content.abbreviations, play)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TermList(terms: List<Content.Term>, play: (String) -> Unit) {
    Panel {
        for (t in terms) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Key(t.text, { play(t.text) }, Modifier.width(76.dp), height = 44.dp, fontSize = 16.sp)
                Spacer(Modifier.width(12.dp))
                Text(t.meaning, color = Cw.Dim, fontSize = 15.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}
