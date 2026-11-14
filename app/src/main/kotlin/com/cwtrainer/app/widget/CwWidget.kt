package com.cwtrainer.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.cwtrainer.app.CwApp
import com.cwtrainer.app.data.ProgressStats
import com.cwtrainer.app.data.SessionEntity
import com.cwtrainer.app.data.Settings
import com.cwtrainer.app.ui.MainActivity
import com.cwtrainer.core.koch.Koch

/**
 * Home-screen widget: streak, today's minutes against the goal, Koch lesson, and a button that
 * opens straight into a 2-minute drill. Tapping anywhere else opens the app.
 *
 * It reads the same Repository flows as the app, so it redraws by itself while its Glance
 * session is alive; AppContainer also calls updateAll() whenever sessions or the lesson change.
 */
class CwWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = (context.applicationContext as CwApp).container.repo
        // Read from disk first: right after the process starts the hot flows are still empty.
        val firstSettings = repo.loadSettings()
        val firstSessions = repo.loadSessions()
        val open = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        val quick = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_QUICK_DRILL)
        provideContent {
            val liveSettings by repo.settings.collectAsState()
            val liveSessions by repo.sessions.collectAsState()
            Content(liveSettings ?: firstSettings, liveSessions.ifEmpty { firstSessions }, open, quick)
        }
    }
}

class CwWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CwWidget()
}

private val Background = Color(0xFF171B22)
private val Accent = Color(0xFFFFB454)
private val Good = Color(0xFF5BD68B)
private val TextColor = Color(0xFFE6EAF2)
private val Dim = Color(0xFF8B95A7)

@Composable
private fun Content(settings: Settings, sessions: List<SessionEntity>, open: Intent, quick: Intent) {
    val stats = ProgressStats(sessions)
    val goal = ProgressStats.DAILY_TARGET_MIN
    Column(
        modifier = GlanceModifier.fillMaxSize().background(Background).cornerRadius(22.dp).padding(14.dp)
            .clickable(actionStartActivity(open)),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Figure("STREAK", if (stats.streak == 1) "1 day" else "${stats.streak} days", if (stats.streak > 0) Accent else Dim, GlanceModifier.defaultWeight())
            Figure("TODAY", "${stats.minutesToday} / $goal min", if (stats.minutesToday >= goal) Good else TextColor, GlanceModifier.defaultWeight())
            Figure("KOCH", "${settings.kochLevel} / ${Koch.MAX_LEVEL}", TextColor, GlanceModifier)
        }
        Spacer(modifier = GlanceModifier.defaultWeight())
        Box(
            modifier = GlanceModifier.fillMaxWidth().height(46.dp).background(Accent).cornerRadius(14.dp)
                .clickable(actionStartActivity(quick)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Quick 2-min drill",
                style = TextStyle(color = ColorProvider(Color.Black), fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

@Composable
private fun Figure(label: String, value: String, color: Color, modifier: GlanceModifier) {
    Column(modifier = modifier) {
        Text(label, style = TextStyle(color = ColorProvider(Dim), fontSize = 11.sp, fontWeight = FontWeight.Medium))
        Text(value, style = TextStyle(color = ColorProvider(color), fontSize = 17.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}
