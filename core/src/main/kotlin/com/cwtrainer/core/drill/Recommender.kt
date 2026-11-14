package com.cwtrainer.core.drill

import com.cwtrainer.core.koch.Koch
import com.cwtrainer.core.srs.Sm2
import com.cwtrainer.core.srs.SrsItem

data class Recommendation(val drill: DrillType, val reason: String)

/**
 * Decides what "Continue training" starts, from the spaced-repetition queue.
 *
 * While characters are still being unlocked the Koch receive drill leads, because it is the
 * only drill that unlocks new characters. Other drills take over when they have more items
 * that are due, missed or slow. The drill done last is held back so sessions alternate.
 */
object Recommender {
    fun next(
        level: Int,
        items: Map<String, SrsItem>,
        now: Long,
        charWpm: Double,
        lastDrillId: String? = null,
        receiveDoneToday: Boolean = false,
    ): Recommendation {
        var best: Recommendation? = null
        var bestScore = Double.NEGATIVE_INFINITY

        fun offer(drill: DrillType, score: Double, reason: String) {
            if (drill.lockReason(level) != null) return
            val s = if (drill.id == lastDrillId) score * 0.5 else score
            if (s > bestScore) { bestScore = s; best = Recommendation(drill, reason) }
        }

        fun urgency(ids: List<String>): Double = ids.sumOf { id ->
            val it = items[id] ?: return@sumOf 0.3   // never seen
            var u = 0.0
            if (now >= it.dueAt) u += 1.0
            if (it.recentAccuracy < 0.85) u += 1.0
            if (it.avgRtMs > Sm2.SLOW_MS * 0.6) u += 0.5
            u
        }

        val chars = Drills.itemIdsFor(ReceiveDrill, level)
        val newest = Koch.newest(level)
        if (!Koch.allLearned(level)) {
            offer(
                ReceiveDrill, if (receiveDoneToday) 6.0 else 20.0,
                "Koch lesson $level: keep copying until \"$newest\" is solid, then the next character unlocks",
            )
        } else {
            offer(ReceiveDrill, 2.0 + urgency(chars) * 0.5, "Keep your character copy sharp")
        }

        val slow = chars.count { (items[it]?.avgRtMs ?: 0.0) > Sm2.FAST_MS || (items[it]?.recentAccuracy ?: 1.0) < 0.85 }
        offer(CharRecognitionDrill, 4.0 + slow * 2.0, if (slow > 0) "$slow characters are slow or shaky: sharpen instant recognition" else "Speed up instant recognition")

        offer(WordRecognitionDrill, 4.0 + urgency(Drills.itemIdsFor(WordRecognitionDrill, level)) * 0.4, "Words due for review")
        offer(ProsignDrill, 2.0 + urgency(Drills.itemIdsFor(ProsignDrill, level)) * 1.5, "Prosigns due for review")
        offer(RealWorldDrill, 5.0 + urgency(Drills.itemIdsFor(RealWorldDrill, level)) * 0.4, "On-air copy: callsigns, Q-codes and exchanges")
        if (charWpm >= 15.0) offer(HeadCopyDrill, if (Koch.allLearned(level)) 7.0 else 4.0, "Head copy builds the habit you need above 15 WPM")
        offer(SendDrill, 2.5, "Practise your sending")

        return best ?: Recommendation(ReceiveDrill, "Koch receive drill")
    }
}
