package com.cwtrainer.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.cwtrainer.core.export.SessionRecord
import com.cwtrainer.core.srs.SrsItem
import kotlinx.coroutines.flow.Flow

/** Spaced-repetition state of one character, word, prosign or term. Mirrors core's SrsItem. */
@Entity(tableName = "srs_items")
data class SrsItemEntity(
    @PrimaryKey val id: String,
    val ease: Double,
    val intervalDays: Double,
    val reps: Int,
    val lapses: Int,
    val dueAt: Long,
    val lastReviewedAt: Long,
    val correct: Int,
    val total: Int,
    val avgRtMs: Double,
    val recentAccuracy: Double,
) {
    fun toCore() = SrsItem(id, ease, intervalDays, reps, lapses, dueAt, lastReviewedAt, correct, total, avgRtMs, recentAccuracy)
}

fun SrsItem.toEntity() =
    SrsItemEntity(id, ease, intervalDays, reps, lapses, dueAt, lastReviewedAt, correct, total, avgRtMs, recentAccuracy)

/** One finished practice session. v2's CSV export is a straight dump of these two tables. */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    /** Local calendar day (days since 1970-01-01), for streaks and per-day totals. */
    val day: Long,
    val drillId: String,
    val seconds: Int,
    val charWpm: Double,
    val effWpm: Double,
    val total: Int,
    val correct: Int,
    val level: Int,
    val band: String,
) {
    fun toRecord() = SessionRecord(startedAt, day, drillId, seconds, charWpm, effWpm, total, correct, level, band)
}

fun SessionRecord.toEntity() =
    SessionEntity(startedAt = startedAt, day = day, drillId = drillId, seconds = seconds, charWpm = charWpm,
        effWpm = effWpm, total = total, correct = correct, level = level, band = band)

@Dao
interface CwDao {
    @Query("SELECT * FROM srs_items")
    fun observeItems(): Flow<List<SrsItemEntity>>

    @Query("SELECT * FROM srs_items")
    suspend fun allItems(): List<SrsItemEntity>

    @Upsert
    suspend fun upsertItems(items: List<SrsItemEntity>)

    @Query("DELETE FROM srs_items")
    suspend fun clearItems()

    @Query("SELECT * FROM sessions ORDER BY startedAt")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startedAt")
    suspend fun allSessions(): List<SessionEntity>

    @Insert
    suspend fun insertSession(session: SessionEntity)

    @Insert
    suspend fun insertSessions(sessions: List<SessionEntity>)

    @Query("DELETE FROM sessions")
    suspend fun clearSessions()
}

@Database(entities = [SrsItemEntity::class, SessionEntity::class], version = 1, exportSchema = false)
abstract class CwDatabase : RoomDatabase() {
    abstract fun dao(): CwDao
}
