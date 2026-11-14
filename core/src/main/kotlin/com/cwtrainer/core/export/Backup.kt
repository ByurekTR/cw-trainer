package com.cwtrainer.core.export

import com.cwtrainer.core.srs.SrsItem

/** One finished practice session, independent of the database layer. */
data class SessionRecord(
    val startedAt: Long,
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
    val accuracy: Double? get() = if (total == 0) null else correct.toDouble() / total
}

/**
 * Everything needed to move to a new phone: settings (as plain key/value text),
 * every spaced-repetition item and the full session log.
 */
data class Backup(
    val appVersion: String,
    val exportedAt: Long,
    val settings: Map<String, String>,
    val items: List<SrsItem>,
    val sessions: List<SessionRecord>,
    val formatVersion: Int = BackupCodec.FORMAT_VERSION,
)

class BackupException(message: String) : Exception(message)

object BackupCodec {
    const val FORMAT_VERSION = 1
    const val MAGIC = "cw-trainer-backup"

    fun encode(b: Backup): String = Json.write(
        linkedMapOf(
            "type" to MAGIC,
            "formatVersion" to b.formatVersion,
            "appVersion" to b.appVersion,
            "exportedAt" to b.exportedAt,
            "settings" to LinkedHashMap<String, Any?>(b.settings),
            "items" to b.items.map {
                linkedMapOf<String, Any?>(
                    "id" to it.id, "ease" to it.ease, "intervalDays" to it.intervalDays, "reps" to it.reps,
                    "lapses" to it.lapses, "dueAt" to it.dueAt, "lastReviewedAt" to it.lastReviewedAt,
                    "correct" to it.correct, "total" to it.total, "avgRtMs" to it.avgRtMs,
                    "recentAccuracy" to it.recentAccuracy,
                )
            },
            "sessions" to b.sessions.map {
                linkedMapOf<String, Any?>(
                    "startedAt" to it.startedAt, "day" to it.day, "drillId" to it.drillId, "seconds" to it.seconds,
                    "charWpm" to it.charWpm, "effWpm" to it.effWpm, "total" to it.total, "correct" to it.correct,
                    "level" to it.level, "band" to it.band,
                )
            },
        ),
    )

    /** @throws BackupException with a message fit to show the user */
    fun decode(text: String): Backup {
        val root = try {
            Json.parse(text)
        } catch (e: Json.ParseException) {
            throw BackupException("This file is not a readable backup (${e.message}).")
        }
        if (root !is Map<*, *> || root["type"] != MAGIC) throw BackupException("This file is not a CW Trainer backup.")
        val version = (root["formatVersion"] as? Number)?.toInt() ?: throw BackupException("The backup has no format version.")
        if (version > FORMAT_VERSION) throw BackupException("This backup was made by a newer version of the app. Update the app first.")
        try {
            val settings = LinkedHashMap<String, String>()
            (root["settings"] as? Map<*, *>)?.forEach { (k, v) -> if (v != null) settings[k.toString()] = v.toString() }
            val items = (root["items"] as? List<*> ?: emptyList<Any?>()).map { raw ->
                val m = raw as Map<*, *>
                SrsItem(
                    id = m.str("id"), ease = m.dbl("ease"), intervalDays = m.dbl("intervalDays"), reps = m.int("reps"),
                    lapses = m.int("lapses"), dueAt = m.lng("dueAt"), lastReviewedAt = m.lng("lastReviewedAt"),
                    correct = m.int("correct"), total = m.int("total"), avgRtMs = m.dbl("avgRtMs"),
                    recentAccuracy = m.dbl("recentAccuracy"),
                )
            }
            val sessions = (root["sessions"] as? List<*> ?: emptyList<Any?>()).map { raw ->
                val m = raw as Map<*, *>
                SessionRecord(
                    startedAt = m.lng("startedAt"), day = m.lng("day"), drillId = m.str("drillId"), seconds = m.int("seconds"),
                    charWpm = m.dbl("charWpm"), effWpm = m.dbl("effWpm"), total = m.int("total"), correct = m.int("correct"),
                    level = m.int("level"), band = m.str("band"),
                )
            }
            return Backup(
                appVersion = root["appVersion"]?.toString() ?: "",
                exportedAt = (root["exportedAt"] as? Number)?.toLong() ?: 0L,
                settings = settings, items = items, sessions = sessions, formatVersion = version,
            )
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("The backup is damaged and cannot be restored.")
        }
    }

    private fun Map<*, *>.num(k: String): Number = this[k] as? Number ?: throw BackupException("The backup is missing \"$k\".")
    private fun Map<*, *>.dbl(k: String) = num(k).toDouble()
    private fun Map<*, *>.lng(k: String) = num(k).toLong()
    private fun Map<*, *>.int(k: String) = num(k).toInt()
    private fun Map<*, *>.str(k: String) = this[k] as? String ?: throw BackupException("The backup is missing \"$k\".")
}
