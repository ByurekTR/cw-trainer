package com.cwtrainer.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cwtrainer.app.AppContainer
import com.cwtrainer.app.data.ProgressStats
import com.cwtrainer.app.data.Repository
import com.cwtrainer.app.data.SessionEntity
import com.cwtrainer.app.data.Settings
import com.cwtrainer.core.drill.ItemIds
import com.cwtrainer.core.drill.ReceiveDrill
import com.cwtrainer.core.koch.Koch
import com.cwtrainer.core.srs.SrsItem
import com.cwtrainer.core.srs.SrsScheduler

@Composable
fun ProgressScreen(container: AppContainer, settings: Settings, onBack: () -> Unit) {
    val sessions by container.repo.sessions.collectAsStateWithLifecycle()
    val items by container.repo.items.collectAsStateWithLifecycle()
    val stats = remember(sessions) { ProgressStats(sessions) }
    val level = settings.kochLevel

    Page("Progress", onBack = onBack) {
        Panel {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Label("Koch lesson")
                    Text("$level of ${Koch.MAX_LEVEL}", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                val next = Koch.next(level)
                Text(if (next == null) "All characters learned" else "Next: $next", color = Cw.Dim, fontSize = 15.sp)
            }
            Spacer(Modifier.height(10.dp))
            Meter(level.toFloat() / Koch.MAX_LEVEL)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                Figure("Character speed", "${settings.charWpm} WPM", Modifier.weight(1f))
                Figure("Effective speed", "${wpmText(settings.effectiveWpm())} WPM", Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            Row(Modifier.fillMaxWidth()) {
                Figure("Streak", if (stats.streak == 1) "1 day" else "${stats.streak} days", Modifier.weight(1f))
                Figure("Today", "${stats.minutesToday} min", Modifier.weight(1f))
                Figure("All time", "${stats.totalMinutes} min", Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Label("Minutes per day, last 14 days")
            Spacer(Modifier.height(8.dp))
            DayBars(stats)
            Spacer(Modifier.height(6.dp))
            Text(
                "The line marks ${ProgressStats.DAILY_TARGET_MIN} minutes. " +
                    "${ProgressStats.DAILY_TARGET_MIN}–${ProgressStats.DAILY_TARGET_MAX} minutes every day beats one long session a week.",
                color = Cw.Dim, fontSize = 13.sp,
            )
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            Label("Speed history (receive drill)")
            Spacer(Modifier.height(8.dp))
            WpmHistory(sessions, settings.charWpm.toDouble())
        }

        Spacer(Modifier.height(12.dp))
        Panel {
            Label("Characters: accuracy and reaction time")
            Spacer(Modifier.height(8.dp))
            Heatmap(level, items)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("weak", color = Cw.Dim, fontSize = 12.sp)
                for (i in 0..4) Box(Modifier.height(12.dp).weight(1f).clip(RoundedCornerShape(3.dp)).background(heat(i / 4.0)))
                Text("solid", color = Cw.Dim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Colour is recent accuracy. The small number is the average reaction time in the instant-recognition drill. " +
                    "${SrsScheduler.dueCount(items.values, System.currentTimeMillis())} items are due for review.",
                color = Cw.Dim, fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Label(label)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 50 % and below is fully "weak"; 100 % is fully "solid". */
private fun heat(accuracy: Double): Color {
    val f = ((accuracy - 0.5) / 0.5).coerceIn(0.0, 1.0).toFloat()
    return if (f < 0.5f) lerp(Cw.Bad, Cw.Accent, f * 2f) else lerp(Cw.Accent, Cw.Good, (f - 0.5f) * 2f)
}

@Composable
private fun Heatmap(level: Int, items: Map<String, SrsItem>) {
    val unlocked = Koch.unlocked(level).toSet()
    for (row in Koch.ORDER.chunked(6)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (sym in row) {
                val item = items[ItemIds.char(sym)]
                val seen = item != null && item.total > 0
                val bg = when {
                    sym !in unlocked -> Cw.Bg
                    !seen -> Cw.SurfaceHi
                    else -> heat(item!!.recentAccuracy)
                }
                Column(
                    Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(8.dp)).background(bg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val fg = if (seen && sym in unlocked) Color.Black else Cw.Dim
                    Text(sym, color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (seen && sym in unlocked) {
                        Text(percent(item!!.recentAccuracy).replace(" ", ""), color = fg, fontSize = 11.sp)
                        if (item.avgRtMs > 0) Text("${item.avgRtMs.toInt()} ms", color = fg, fontSize = 10.sp)
                    }
                }
            }
            repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DayBars(stats: ProgressStats) {
    val today = Repository.today()
    val minutes = (13 downTo 0).map { (stats.secondsByDay[today - it] ?: 0) / 60f }
    val top = maxOf(ProgressStats.DAILY_TARGET_MAX.toFloat(), minutes.max())
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val slot = size.width / minutes.size
        val bar = slot * 0.64f
        minutes.forEachIndexed { i, m ->
            val h = (m / top) * size.height
            val x = i * slot + (slot - bar) / 2
            drawRoundRect(
                color = Cw.SurfaceHi, topLeft = Offset(x, size.height - 3f), size = Size(bar, 3f),
                cornerRadius = CornerRadius(2f, 2f),
            )
            if (m > 0f) {
                drawRoundRect(
                    color = if (m >= ProgressStats.DAILY_TARGET_MIN) Cw.Good else Cw.Accent,
                    topLeft = Offset(x, size.height - h), size = Size(bar, h.coerceAtLeast(3f)),
                    cornerRadius = CornerRadius(4f, 4f),
                )
            }
        }
        val y = size.height - (ProgressStats.DAILY_TARGET_MIN / top) * size.height
        drawLine(Cw.Dim, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
    }
}

@Composable
private fun WpmHistory(sessions: List<SessionEntity>, charWpm: Double) {
    val receive = remember(sessions) { sessions.filter { it.drillId == ReceiveDrill.id }.takeLast(60) }
    if (receive.size < 2) {
        Text("Your effective speed will be plotted here after a couple of receive sessions.", color = Cw.Dim, fontSize = 14.sp)
        return
    }
    val lo = 5f
    val hi = (maxOf(charWpm, receive.maxOf { it.charWpm }) + 2).toFloat()
    Row {
        Column(Modifier.height(120.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("${hi.toInt()}", color = Cw.Dim, fontSize = 11.sp)
            Text("WPM", color = Cw.Dim, fontSize = 11.sp)
            Text("${lo.toInt()}", color = Cw.Dim, fontSize = 11.sp)
        }
        Canvas(Modifier.weight(1f).height(120.dp).padding(start = 8.dp)) {
            fun x(i: Int) = i * size.width / (receive.size - 1)
            fun y(wpm: Double) = size.height - ((wpm.toFloat() - lo) / (hi - lo)).coerceIn(0f, 1f) * size.height
            for (g in 1..3) {
                val gy = size.height * g / 4f
                drawLine(Cw.SurfaceHi, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
            }
            val charPath = Path()
            val effPath = Path()
            receive.forEachIndexed { i, s ->
                if (i == 0) { charPath.moveTo(x(i), y(s.charWpm)); effPath.moveTo(x(i), y(s.effWpm)) }
                else { charPath.lineTo(x(i), y(s.charWpm)); effPath.lineTo(x(i), y(s.effWpm)) }
            }
            drawPath(charPath, Cw.Dim, style = Stroke(width = 2f))
            drawPath(effPath, Cw.Accent, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val last = receive.last()
            drawCircle(Cw.Accent, radius = 7f, center = Offset(x(receive.size - 1), y(last.effWpm)))
        }
    }
    Spacer(Modifier.height(6.dp))
    Text("Amber: effective speed.  Grey: character speed.  Accuracy last session: ${percent(if (receive.last().total == 0) 0.0 else receive.last().correct.toDouble() / receive.last().total)}", color = Cw.Dim, fontSize = 13.sp)
}
