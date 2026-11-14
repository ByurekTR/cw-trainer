package com.cwtrainer.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.cwtrainer.app.audio.AudioPlayer
import com.cwtrainer.app.data.CwDatabase
import com.cwtrainer.app.data.Repository
import com.cwtrainer.app.data.Exporter
import com.cwtrainer.app.data.SettingsStore
import com.cwtrainer.app.listen.ListeningController
import com.cwtrainer.app.widget.CwWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CwApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency container: one of each, alive for the life of the process. */
class AppContainer(context: Context) {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val db: CwDatabase = Room.databaseBuilder(context, CwDatabase::class.java, "cwtrainer.db").build()
    val repo = Repository(db.dao(), SettingsStore(context), scope)
    val player = AudioPlayer(context)

    /** Background listening session; outlives every screen. */
    val listening = ListeningController(player, repo, scope)
    val exporter = Exporter(context, repo)

    init {
        // Keep the home-screen widget in step with progress.
        scope.launch {
            combine(repo.sessions, repo.settings) { sessions, settings ->
                Triple(sessions.size, sessions.lastOrNull()?.startedAt, settings?.kochLevel)
            }.distinctUntilChanged().collect {
                try {
                    CwWidget().updateAll(context)
                } catch (_: Exception) {
                    // No widget placed, or the launcher is not ready: nothing to update.
                }
            }
        }
    }
}
