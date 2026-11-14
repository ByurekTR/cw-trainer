package com.cwtrainer.app.data

import com.cwtrainer.core.export.Backup
import com.cwtrainer.core.srs.SrsItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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

    // Direct reads, for code that runs before the hot flows above have loaded (widget, export).
    suspend fun loadSettings(): Settings = store.flow.first()
    suspend fun loadSessions(): List<SessionEntity> = dao.allSessions()
    suspend fun loadItems(): List<SrsItem> = dao.allItems().map { it.toCore() }

    suspend fun createBackup(appVersion: String): Backup = Backup(
        appVersion = appVersion,
        exportedAt = System.currentTimeMillis(),
        settings = loadSettings().toMap(),
        items = loadItems(),
        sessions = loadSessions().map { it.toRecord() },
    )

    /** Replaces all progress with the backup's. Settings in the backup are applied on top of the current ones. */
    suspend fun restore(backup: Backup) {
        dao.clearItems()
        dao.clearSessions()
        if (backup.items.isNotEmpty()) dao.upsertItems(backup.items.map { it.toEntity() })
        if (backup.sessions.isNotEmpty()) dao.insertSessions(backup.sessions.map { it.toEntity() })
        store.update { it.withMap(backup.settings).copy(lastDrill = "") }
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
