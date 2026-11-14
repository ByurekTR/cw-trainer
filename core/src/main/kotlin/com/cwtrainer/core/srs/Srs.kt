package com.cwtrainer.core.srs

import kotlin.math.max
import kotlin.random.Random

/**
 * One thing the trainee is learning. Ids are namespaced:
 *   "c:K" character, "w:CQ" word, "p:<AR>" prosign, "r:QTH" real-world item.
 */
data class SrsItem(
    val id: String,
    val ease: Double = Sm2.START_EASE,
    val intervalDays: Double = 0.0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val dueAt: Long = 0L,
    val lastReviewedAt: Long = 0L,
    val correct: Int = 0,
    val total: Int = 0,
    /** Exponential moving average of reaction time on correct answers; 0 = no data yet. */
    val avgRtMs: Double = 0.0,
    /** Exponential moving average of correctness (0..1), weighted to recent answers. */
    val recentAccuracy: Double = 0.0,
) {
    val accuracy: Double get() = if (total == 0) 0.0 else correct.toDouble() / total
    val isNew: Boolean get() = total == 0
}

/**
 * SM-2, adapted for drills where the same item is seen many times in one sitting:
 * the interval only advances when the item was actually due. Extra reviews inside the interval
 * still update ease (at a quarter of the weight) and the statistics, and a miss always resets.
 */
object Sm2 {
    const val START_EASE = 2.5
    const val MIN_EASE = 1.3
    const val MAX_EASE = 3.0
    const val DAY_MS = 24L * 60 * 60 * 1000
    /** A missed item is due again after this long (i.e. later in the same session). */
    const val RELEARN_MS = 60L * 1000

    /** Reaction-time thresholds for single-item recognition. */
    const val FAST_MS = 900L
    const val SLOW_MS = 2000L

    /**
     * Maps an answer to SM-2 quality 0..5.
     * Wrong = 1 (0 if there was no answer at all). Correct = 5 fast, 4 normal, 3 slow.
     * @param rtMs reaction time, or null when the drill does not measure it
     */
    fun quality(correct: Boolean, rtMs: Long? = null, answered: Boolean = true): Int = when {
        !answered -> 0
        !correct -> 1
        rtMs == null -> 4
        rtMs <= FAST_MS -> 5
        rtMs <= SLOW_MS -> 4
        else -> 3
    }

    fun review(item: SrsItem, quality: Int, now: Long, rtMs: Long? = null): SrsItem {
        val q = quality.coerceIn(0, 5)
        val ok = q >= 3
        val wasDue = item.reps == 0 || now >= item.dueAt
        val delta = 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)
        val ease = (item.ease + delta * if (wasDue || !ok) 1.0 else 0.25).coerceIn(MIN_EASE, MAX_EASE)

        val total = item.total + 1
        val correct = item.correct + if (ok) 1 else 0
        val recent = if (item.total == 0) (if (ok) 1.0 else 0.0) else item.recentAccuracy * 0.8 + (if (ok) 0.2 else 0.0)
        val avgRt = when {
            !ok || rtMs == null -> item.avgRtMs
            item.avgRtMs <= 0.0 -> rtMs.toDouble()
            else -> item.avgRtMs * 0.8 + rtMs * 0.2
        }
        val base = item.copy(
            ease = ease, lastReviewedAt = now, total = total, correct = correct,
            recentAccuracy = recent, avgRtMs = avgRt,
        )

        return when {
            !ok -> base.copy(reps = 0, intervalDays = 0.0, lapses = item.lapses + 1, dueAt = now + RELEARN_MS)
            !wasDue -> base
            else -> {
                val interval = when (item.reps) {
                    0 -> 1.0
                    1 -> 6.0
                    else -> max(1.0, item.intervalDays * ease)
                }
                base.copy(reps = item.reps + 1, intervalDays = interval, dueAt = now + (interval * DAY_MS).toLong())
            }
        }
    }
}

/**
 * Chooses what to drill next. Every drill draws from here.
 * Weights (higher = shown more often): missed, slow, due and brand-new items rise;
 * items that are accurate, fast and not yet due sink.
 */
object SrsScheduler {
    fun weight(item: SrsItem, now: Long): Double {
        if (item.isNew) return 4.0
        var w = 1.0
        if (now >= item.dueAt) w += 2.0
        w += (Sm2.START_EASE - item.ease).coerceAtLeast(0.0) * 2.0
        w += (1.0 - item.recentAccuracy) * 6.0
        if (item.avgRtMs > 0) w += ((item.avgRtMs - Sm2.FAST_MS) / 600.0).coerceIn(0.0, 2.0)
        if (item.total < 10) w += 1.5
        val mastered = now < item.dueAt && item.recentAccuracy >= 0.95 && item.ease >= Sm2.START_EASE &&
            (item.avgRtMs <= 0 || item.avgRtMs <= Sm2.FAST_MS)
        if (mastered) w *= 0.4
        return w
    }

    /** Weighted random choice. [boost] lets a drill favour e.g. the newest Koch character. */
    fun pick(
        candidates: List<SrsItem>,
        now: Long,
        rng: Random,
        boost: (SrsItem) -> Double = { 1.0 },
    ): SrsItem {
        require(candidates.isNotEmpty()) { "nothing to pick from" }
        val weights = DoubleArray(candidates.size) { weight(candidates[it], now) * boost(candidates[it]) }
        var r = rng.nextDouble() * weights.sum()
        for (i in candidates.indices) {
            r -= weights[i]
            if (r <= 0) return candidates[i]
        }
        return candidates.last()
    }

    /** Draws [n] items; an item is not repeated back-to-back when there is a choice. */
    fun pickMany(
        candidates: List<SrsItem>,
        n: Int,
        now: Long,
        rng: Random,
        boost: (SrsItem) -> Double = { 1.0 },
    ): List<SrsItem> {
        val out = ArrayList<SrsItem>(n)
        repeat(n) {
            var p = pick(candidates, now, rng, boost)
            if (candidates.size > 2 && out.isNotEmpty() && out.last().id == p.id) p = pick(candidates, now, rng, boost)
            out.add(p)
        }
        return out
    }

    fun dueCount(items: Collection<SrsItem>, now: Long): Int = items.count { !it.isNew && now >= it.dueAt }
}
