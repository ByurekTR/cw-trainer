package com.cwtrainer.core.export

import com.cwtrainer.core.drill.ItemIds
import com.cwtrainer.core.koch.Koch
import com.cwtrainer.core.srs.SrsItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CSV files for spreadsheets: comma separated, CRLF line ends, a header row, "." as the
 * decimal point, ISO dates. Empty cell = no data.
 */
object CsvExport {
    const val SESSIONS_FILE = "cw-trainer-sessions.csv"
    const val CHARACTERS_FILE = "cw-trainer-characters.csv"
    const val WPM_FILE = "cw-trainer-wpm-history.csv"

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field

    private fun row(vararg cells: Any?): String = cells.joinToString(",") { c ->
        when (c) {
            null -> ""
            is Double -> if (c.isNaN() || c.isInfinite()) "" else num(c)
            else -> escape(c.toString())
        }
    } + "\r\n"

    private fun num(d: Double): String = String.format(Locale.ROOT, "%.3f", d).trimEnd('0').trimEnd('.')

    private fun time(ms: Long, zone: ZoneId): String? = if (ms <= 0) null else stamp.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** Every session, oldest first. */
    fun sessions(sessions: List<SessionRecord>, zone: ZoneId): String = buildString {
        append(row("started", "date", "drill", "minutes", "seconds", "char_wpm", "effective_wpm", "answers", "correct", "accuracy_percent", "koch_level", "band"))
        for (s in sessions.sortedBy { it.startedAt }) {
            append(
                row(
                    time(s.startedAt, zone), LocalDate.ofEpochDay(s.day), s.drillId, s.seconds / 60.0, s.seconds,
                    s.charWpm, s.effWpm, s.total, s.correct, s.accuracy?.let { it * 100 }, s.level, s.band,
                ),
            )
        }
    }

    /** One row per character in Koch order: accuracy, reaction time and the SM-2 state. */
    fun characters(level: Int, items: Map<String, SrsItem>, zone: ZoneId): String = buildString {
        append(
            row(
                "character", "koch_position", "unlocked", "answers", "correct", "accuracy_percent", "recent_accuracy_percent",
                "avg_reaction_ms", "sm2_ease", "sm2_interval_days", "sm2_repetitions", "lapses", "last_reviewed", "due",
            ),
        )
        Koch.ORDER.forEachIndexed { i, sym ->
            val it = items[ItemIds.char(sym)]
            val seen = it != null && it.total > 0
            append(
                row(
                    sym, i + 1, if (i < Koch.clamp(level)) "yes" else "no",
                    it?.total ?: 0, it?.correct ?: 0,
                    if (seen) it!!.accuracy * 100 else null,
                    if (seen) it!!.recentAccuracy * 100 else null,
                    if (seen && it!!.avgRtMs > 0) it.avgRtMs else null,
                    if (seen) it!!.ease else null,
                    if (seen) it!!.intervalDays else null,
                    if (seen) it!!.reps else null,
                    if (seen) it!!.lapses else null,
                    if (seen) time(it!!.lastReviewedAt, zone) else null,
                    if (seen) time(it!!.dueAt, zone) else null,
                ),
            )
        }
    }

    /** Receive-drill speed per practice day: the history behind the progress graph. */
    fun wpmHistory(sessions: List<SessionRecord>, zone: ZoneId): String = buildString {
        append(row("date", "receive_sessions", "minutes", "char_wpm", "effective_wpm", "accuracy_percent", "koch_level"))
        val byDay = sessions.filter { it.drillId == "receive" }.groupBy { it.day }.toSortedMap()
        for ((day, list) in byDay) {
            val answers = list.sumOf { it.total }
            append(
                row(
                    LocalDate.ofEpochDay(day), list.size, list.sumOf { it.seconds } / 60.0,
                    list.maxOf { it.charWpm }, list.maxOf { it.effWpm },
                    if (answers == 0) null else list.sumOf { it.correct } * 100.0 / answers,
                    list.maxOf { it.level },
                ),
            )
        }
    }
}
