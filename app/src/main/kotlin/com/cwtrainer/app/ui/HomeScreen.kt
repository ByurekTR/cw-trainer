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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cwtrainer.app.AppContainer
import com.cwtrainer.app.data.ProgressStats
import com.cwtrainer.app.data.Repository
import com.cwtrainer.app.data.Settings
import com.cwtrainer.app.listen.ListenStatus
import com.cwtrainer.core.drill.Drills
import com.cwtrainer.core.drill.ReceiveDrill
import com.cwtrainer.core.drill.Recommender
import com.cwtrainer.core.koch.Koch

@Composable
fun HomeScreen(container: AppContainer, settings: Settings, open: (Route) -> Unit) {
    val sessions by container.repo.sessions.collectAsStateWithLifecycle()
    val items by container.repo.items.collectAsStateWithLifecycle()
    val level = settings.kochLevel
    val stats = remember(sessions) { ProgressStats(sessions) }
    val recommendation = remember(level, items, sessions, settings.lastDrill, settings.charWpm) {
        val today = Repository.today()
        Recommender.next(
            level = level, items = items, now = System.currentTimeMillis(),
            charWpm = settings.charWpm.toDouble(), lastDrillId = settings.lastDrill.ifEmpty { null },
            receiveDoneToday = sessions.any { it.day == today && it.drillId == ReceiveDrill.id },
        )
    }

    Page("CW Trainer", onBack = null) {
        // Today
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Today")
                    Text(
                        "${stats.minutesToday} of ${ProgressStats.DAILY_TARGET_MIN}–${ProgressStats.DAILY_TARGET_MAX} min",
                        fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Label("Streak")
                    Text(
                        if (stats.streak == 1) "1 day" else "${stats.streak} days",
                        fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        color = if (stats.streak > 0) Cw.Accent else Cw.Dim,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Meter(
                stats.minutesToday.toFloat() / ProgressStats.DAILY_TARGET_MIN,
                color = if (stats.minutesToday >= ProgressStats.DAILY_TARGET_MIN) Cw.Good else Cw.Accent,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Lesson $level of ${Koch.MAX_LEVEL}  ·  ${settings.charWpm} WPM characters, " +
                    "${wpmText(settings.effectiveWpm())} WPM effective",
                color = Cw.Dim, fontSize = 14.sp,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Continue training
        Panel(onClick = { open(Route.Drill(recommendation.drill.id)) }, color = Cw.Accent) {
            Text("Continue training", color = Color.Black, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(recommendation.drill.title, color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(recommendation.reason, color = Color(0xCC000000), fontSize = 14.sp)
        }

        Spacer(Modifier.height(12.dp))
        val listen by container.listening.state.collectAsStateWithLifecycle()
        Panel(onClick = { open(Route.Listening) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Background listening", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (listen.status) {
                            ListenStatus.PLAYING -> "Playing: ${listen.elapsedSec / 60} of ${listen.totalSec / 60} min"
                            ListenStatus.PAUSED -> "Paused at ${listen.elapsedSec / 60} of ${listen.totalSec / 60} min"
                            else -> "10, 20 or 30 minutes from your queue, screen off"
                        },
                        fontSize = 14.sp, color = if (listen.active) Cw.Accent else Cw.Dim,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("\u203A", color = Cw.Dim, fontSize = 28.sp)
            }
        }

        Spacer(Modifier.height(20.dp))
        Label("Drills", Modifier.padding(start = 4.dp, bottom = 8.dp))
        for (drill in Drills.all) {
            val lock = drill.lockReason(level)
            Panel(
                onClick = if (lock == null) ({ open(Route.Drill(drill.id)) }) else null,
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(drill.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (lock == null) Cw.Text else Cw.Dim)
                        Text(lock ?: drill.summary, fontSize = 14.sp, color = Cw.Dim)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (lock == null) "›" else "Locked", color = Cw.Dim, fontSize = if (lock == null) 28.sp else 13.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton("Progress", { open(Route.Progress) }, Modifier.weight(1f))
            QuietButton("Reference", { open(Route.Reference) }, Modifier.weight(1f))
            QuietButton("Settings", { open(Route.Settings) }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
    }
}
