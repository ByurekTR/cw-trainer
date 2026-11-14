package com.cwtrainer.app.data

import com.cwtrainer.core.srs.SrsItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/** Everything persistent, as hot flows the UI can read synchronously. All data stays on the device. */
class Repository(
    private val dao: CwDao,
    private val store: SettingsStore,
    scope: CoroutineScope,
) {
    /** null until the first read from disk has completed. */
    val settings: StateFlow<Settings?> =
        store.flow.map<Settings, Settings?> { it }.stateIn(scope, SharingStarted.Eagerly, null)

    val items: StateFlow<Map<String, SrsItem>> =
        dao.observeItems().map { list -> list.associate { it.id to it.toCore() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val sessions: StateFlow<List<SessionEntity>> =
        dao.observeSessions().stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun saveItems(items: Collection<SrsItem>) {
        if (items.isNotEmpty()) dao.upsertItems(items.map { it.toEntity() })
    }

    suspend fun addSession(session: SessionEntity) = dao.insertSession(session)

    suspend fun updateSettings(transform: (Settings) -> Settings) = store.update(transform)

    suspend fun resetProgress() {
        dao.clearItems()
        dao.clearSessions()
        store.update { it.copy(kochLevel = Settings().kochLevel, lastDrill = "") }
    }

    companion object {
        fun today(): Long = LocalDate.now().toEpochDay()
    }
}

/** Figures derived from the session log for the home and progress screens. */
class ProgressStats(sessions: List<SessionEntity>, today: Long = Repository.today()) {
    val secondsByDay: Map<Long, Int> = sessions.groupBy { it.day }.mapValues { e -> e.value.sumOf { it.seconds } }
    val minutesToday: Int = (secondsByDay[today] ?: 0) / 60
    val totalMinutes: Int = sessions.sumOf { it.seconds } / 60

    /** Consecutive days with practice, ending today (or yesterday if today is still open). */
    val streak: Int = run {
        var day = if (secondsByDay.containsKey(today)) today else today - 1
        var n = 0
        while (secondsByDay.containsKey(day)) { n++; day-- }
        n
    }

    companion object {
        const val DAILY_TARGET_MIN = 15
        const val DAILY_TARGET_MAX = 20
    }
}
