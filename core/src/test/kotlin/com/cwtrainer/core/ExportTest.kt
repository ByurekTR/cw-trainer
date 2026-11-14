package com.cwtrainer.core

import com.cwtrainer.core.export.Backup
import com.cwtrainer.core.export.BackupCodec
import com.cwtrainer.core.export.BackupException
import com.cwtrainer.core.export.CsvExport
import com.cwtrainer.core.export.Json
import com.cwtrainer.core.export.SessionRecord
import com.cwtrainer.core.koch.Koch
import com.cwtrainer.core.srs.Sm2
import com.cwtrainer.core.srs.SrsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId
import kotlin.random.Random

class ExportTest {
    private val utc = ZoneId.of("UTC")
    private val t0 = 1_794_000_000_000L   // 2026-11-06 21:20:00 UTC

    private fun sampleItems(): List<SrsItem> {
        val rng = Random(3)
        val out = ArrayList<SrsItem>()
        for (id in listOf("c:K", "c:M", "c:U", "c:,", "c:?", "c:=", "w:CQ", "w:73", "p:<AR>", "r:QTH")) {
            var it = SrsItem(id)
            repeat(1 + rng.nextInt(40)) { i ->
                val rt = 300L + rng.nextInt(3000)
                it = Sm2.review(it, Sm2.quality(rng.nextInt(5) > 0, rt), t0 + i * 86_400_000L / 3, rt)
            }
            out.add(it)
        }
        out.add(SrsItem("c:X"))   // never drilled
        return out
    }

    private fun sampleSessions(): List<SessionRecord> = listOf(
        SessionRecord(t0, 20_763, "receive", 300, 20.0, 8.0, 125, 118, 2, "Clean"),
        SessionRecord(t0 + 3_600_000, 20_763, "chars", 120, 20.0, 8.0, 80, 71, 3, "Mild"),
        SessionRecord(t0 + 86_400_000, 20_764, "receive", 600, 20.0, 8.5, 260, 239, 3, "Real band"),
        SessionRecord(t0 + 90_000_000, 20_764, "listening", 1200, 20.0, 8.5, 0, 0, 3, "Clean"),
        SessionRecord(t0 + 2 * 86_400_000, 20_765, "send", 95, 14.3, 14.3, 100, 82, 4, "Clean"),
    )

    private fun sampleBackup() = Backup(
        appVersion = "2.1",
        exportedAt = t0 + 5,
        settings = linkedMapOf("charWpm" to "22", "effOverride" to "0.0", "band" to "Real band", "fist" to "true", "note" to "he said \"73\"\n\\ ok"),
        items = sampleItems(),
        sessions = sampleSessions(),
    )

    // ---- backup round trip ----

    @Test fun backupSurvivesARoundTripExactly() {
        val original = sampleBackup()
        val text = BackupCodec.encode(original)
        val restored = BackupCodec.decode(text)
        assertEquals(original, restored)
        // And a second generation is byte-identical, so nothing drifts over repeated backups.
        assertEquals(text, BackupCodec.encode(restored))
    }

    @Test fun doublesAndLargeNumbersAreExact() {
        val item = SrsItem("c:K", ease = 2.5000000000000004, intervalDays = 1.0E-4, dueAt = Long.MAX_VALUE / 4, avgRtMs = 1234.5678901234567, recentAccuracy = 0.1 + 0.2)
        val b = Backup("2.1", 0, emptyMap(), listOf(item), emptyList())
        assertEquals(item, BackupCodec.decode(BackupCodec.encode(b)).items.single())
    }

    @Test fun emptyBackupRoundTrips() {
        val b = Backup("2.1", 1, emptyMap(), emptyList(), emptyList())
        assertEquals(b, BackupCodec.decode(BackupCodec.encode(b)))
    }

    @Test fun restoreRejectsForeignDamagedAndNewerFiles() {
        val good = BackupCodec.encode(sampleBackup())
        val bad = listOf(
            "", "hello", "[1,2,3]", "{\"type\":\"something-else\"}",
            good.substring(0, good.length / 2),                                   // truncated download
            good.replace("\"formatVersion\":1", "\"formatVersion\":99"),          // from a newer app
            good.replace("\"ease\":", "\"eeze\":"),                               // field missing
            good.replace("\"type\":\"cw-trainer-backup\"", "\"type\":42"),
        )
        for (text in bad) {
            try {
                BackupCodec.decode(text)
                fail("should have been rejected: ${text.take(40)}")
            } catch (e: BackupException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test fun restoreToleratesExtraFieldsAndWhitespace() {
        val text = BackupCodec.encode(sampleBackup())
            .replace("\"appVersion\"", "\"futureField\": {\"a\": [1, 2.5, null, true]},\n  \"appVersion\"")
        assertEquals(sampleBackup(), BackupCodec.decode("﻿  \n" + text + "\n\n"))
    }

    @Test fun jsonHandlesEscapesAndNesting() {
        val v = linkedMapOf("s" to "tab\t quote\" slash\\ nl\n uni\u0001 ç ğ ş", "n" to null, "b" to false, "l" to listOf(1L, -2L, 3.5), "m" to emptyMap<String, Any?>())
        assertEquals(v, Json.parse(Json.write(v)))
        assertEquals("a/bé", Json.parse("\"a\\/b\\u00e9\""))
        assertEquals(listOf(1L, 2.0, -3.0e2), Json.parse(" [1, 2.0, -3.0e2] "))
    }

    // ---- CSV ----

    private fun parseCsv(text: String): List<List<String>> {
        assertTrue(text.endsWith("\r\n"))
        return text.trimEnd().split("\r\n").map { line ->
            val cells = ArrayList<String>(); val cur = StringBuilder(); var q = false; var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                    c == '"' -> q = !q
                    c == ',' && !q -> { cells.add(cur.toString()); cur.setLength(0) }
                    else -> cur.append(c)
                }
                i++
            }
            cells.add(cur.toString()); cells
        }
    }

    @Test fun sessionsCsvHasOneRowPerSessionWithTheRightValues() {
        val rows = parseCsv(CsvExport.sessions(sampleSessions().reversed(), utc))
        assertEquals(6, rows.size)
        assertEquals("started", rows[0][0])
        rows.forEach { assertEquals(rows[0].size, it.size) }
        val first = rows[0].zip(rows[1]).toMap()
        assertEquals("2026-11-06 21:20:00", first["started"])
        assertEquals("2026-11-06", first["date"])
        assertEquals("receive", first["drill"])
        assertEquals("5", first["minutes"])
        assertEquals("8", first["effective_wpm"])
        assertEquals("94.4", first["accuracy_percent"])
        val listening = rows[0].zip(rows[4]).toMap()
        assertEquals("", listening["accuracy_percent"])       // nothing to score
        assertEquals("14.3", rows[0].zip(rows[5]).toMap()["char_wpm"])
    }

    @Test fun charactersCsvCoversAllOfKochInOrderIncludingAwkwardSymbols() {
        val items = sampleItems().associateBy { it.id }
        val rows = parseCsv(CsvExport.characters(3, items, utc))
        assertEquals(42, rows.size)
        assertEquals(Koch.ORDER, rows.drop(1).map { it[0] })    // "," survives quoting
        rows.forEach { assertEquals(rows[0].size, it.size) }
        val k = rows[0].zip(rows[1]).toMap()
        val item = items.getValue("c:K")
        assertEquals("yes", k["unlocked"])
        assertEquals(item.total.toString(), k["answers"])
        assertEquals(item.ease, k["sm2_ease"]!!.toDouble(), 0.001)
        assertEquals(item.intervalDays, k["sm2_interval_days"]!!.toDouble(), 0.001)
        assertEquals(item.avgRtMs, k["avg_reaction_ms"]!!.toDouble(), 0.001)
        assertEquals(item.accuracy * 100, k["accuracy_percent"]!!.toDouble(), 0.001)
        val x = rows[0].zip(rows.last()).toMap()
        assertEquals("X", x["character"]); assertEquals("no", x["unlocked"]); assertEquals("0", x["answers"]); assertEquals("", x["sm2_ease"])
    }

    @Test fun wpmHistoryGroupsReceiveSessionsByDay() {
        val more = sampleSessions() + SessionRecord(t0 + 100_000_000, 20_764, "receive", 300, 20.0, 9.0, 100, 80, 4, "Clean")
        val rows = parseCsv(CsvExport.wpmHistory(more, utc))
        assertEquals(3, rows.size)
        val day2 = rows[0].zip(rows[2]).toMap()
        assertEquals("2026-11-07", day2["date"])
        assertEquals("2", day2["receive_sessions"])
        assertEquals("15", day2["minutes"])
        assertEquals("9", day2["effective_wpm"])
        assertEquals("88.611", day2["accuracy_percent"])      // (239 + 80) / 360
        assertEquals("4", day2["koch_level"])
        assertEquals("date,receive_sessions,minutes,char_wpm,effective_wpm,accuracy_percent,koch_level\r\n", CsvExport.wpmHistory(emptyList(), utc))
    }

    @Test fun csvEscaping() {
        assertEquals("plain", CsvExport.escape("plain"))
        assertEquals("\"a,b\"", CsvExport.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExport.escape("say \"hi\""))
        assertEquals("\"two\nlines\"", CsvExport.escape("two\nlines"))
    }
}
