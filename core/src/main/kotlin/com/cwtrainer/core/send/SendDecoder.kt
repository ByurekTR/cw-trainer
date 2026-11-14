package com.cwtrainer.core.send

import com.cwtrainer.core.morse.MorseCode
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How close the sending was to textbook 1:3:1:3:7 timing. Ratios are in dot lengths. */
data class TimingScore(
    val wpm: Double,
    val dashDotRatio: Double?,
    val intraGapRatio: Double?,
    val charGapRatio: Double?,
    val wordGapRatio: Double?,
    /** Spread of mark lengths around their own average (coefficient of variation, 0 = perfect). */
    val markVariation: Double?,
    val score: Int,
    val marks: Int,
)

/**
 * Decodes hand-sent Morse from key-down / key-up times (milliseconds on any monotonic clock).
 *
 * Speed tracking is adaptive: the dot/dash boundary is re-estimated from the most recent marks
 * (two-cluster split), so it follows the sender from about 5 to 40 WPM and through speed
 * changes. The marks of the character in progress are re-judged against the newest estimate
 * when the character is completed, which fixes the first character after a speed change.
 *
 * Call [tick] regularly (every 20-50 ms) so characters and word spaces appear without waiting
 * for the next key press.
 *
 * v2: the microphone decoder only has to turn audio into the same keyDown/keyUp calls.
 */
class SendDecoder(initialWpm: Double = 15.0) {
    private var dot = 1200.0 / initialWpm
    private var dash = 3.0 * dot
    private var threshold = 2.0 * dot
    private val recent = ArrayDeque<Double>()

    private var downAt = -1.0
    private var upAt = -1.0
    private val current = ArrayList<Double>()
    private var spacePending = false

    private val text = StringBuilder()
    private val dots = ArrayList<Double>()
    private val dashes = ArrayList<Double>()
    private val intraGaps = ArrayList<Double>()
    private val charGaps = ArrayList<Double>()
    private val wordGaps = ArrayList<Double>()

    val decoded: String get() = text.toString()
    val estimatedWpm: Double get() = 1200.0 / dot
    val dotMs: Double get() = dot

    /** Pattern of the character being keyed right now, e.g. "-.-". */
    val pending: String get() = current.joinToString("") { if (it > threshold) "-" else "." }

    fun keyDown(t: Double) {
        if (downAt >= 0) return
        if (upAt >= 0) {
            val gap = t - upAt
            if (current.isNotEmpty()) {
                if (gap < CHAR_GAP_DOTS * dot) intraGaps.add(gap) else finishChar()
            }
            if (current.isEmpty() && text.isNotEmpty()) {
                // The gap that preceded this character, now that its length is known.
                when {
                    gap >= PAUSE_DOTS * dot -> {}
                    gap >= WORD_GAP_DOTS * dot -> wordGaps.add(gap)
                    gap >= CHAR_GAP_DOTS * dot -> charGaps.add(gap)
                }
                if (gap >= WORD_GAP_DOTS * dot) addSpace()
            }
        }
        spacePending = false
        downAt = t
    }

    fun keyUp(t: Double) {
        if (downAt < 0) return
        val d = t - downAt
        downAt = -1.0
        upAt = t
        if (d < 5.0) return // contact bounce
        current.add(d)
        learn(d)
    }

    /** Emits a finished character / word space once enough silence has passed. */
    fun tick(now: Double) {
        if (downAt >= 0 || upAt < 0) return
        val gap = now - upAt
        if (current.isNotEmpty() && gap >= CHAR_GAP_DOTS * dot) {
            finishChar()
            spacePending = true
        }
        if (spacePending && gap >= WORD_GAP_DOTS * dot) {
            addSpace()
            spacePending = false
        }
    }

    fun reset() {
        text.setLength(0); current.clear()
        dots.clear(); dashes.clear(); intraGaps.clear(); charGaps.clear(); wordGaps.clear()
        downAt = -1.0; upAt = -1.0; spacePending = false
    }

    private fun addSpace() {
        if (text.isNotEmpty() && text.last() != ' ') text.append(' ')
    }

    private fun finishChar() {
        val pattern = StringBuilder()
        for (d in current) {
            if (d > threshold) { pattern.append('-'); dashes.add(d) } else { pattern.append('.'); dots.add(d) }
        }
        current.clear()
        val sym = MorseCode.symbolFor(pattern.toString())
        text.append(sym ?: UNKNOWN)
    }

    private fun learn(d: Double) {
        // A mark far longer than any recent dash, or far shorter than any recent dot, means the
        // sender changed speed: forget the old marks and restart the estimate from this one.
        if (d > dash * REGIME_RATIO) {
            recent.clear(); dot = d / 3.0; dash = d; threshold = 2.0 * dot
        } else if (d < dot / REGIME_RATIO) {
            recent.clear(); dot = d; dash = 3.0 * d; threshold = 2.0 * dot
        }
        recent.addLast(d)
        if (recent.size > WINDOW) recent.removeFirst()
        val sorted = recent.sorted()
        // Find the widest relative jump between neighbours: that is the dot/dash boundary.
        var split = -1
        var best = 1.0
        for (i in 1 until sorted.size) {
            val r = sorted[i] / sorted[i - 1]
            if (r > best) { best = r; split = i }
        }
        if (split > 0 && best >= MIN_SPLIT_RATIO) {
            dot = sorted.subList(0, split).average()
            dash = sorted.subList(split, sorted.size).average()
            threshold = sqrt(dot * dash)
        } else {
            // Only one kind of mark in the window: nudge the estimate of whichever kind it is.
            val mean = sorted.average()
            val target = if (mean > threshold) mean / 3.0 else mean
            dot = dot * 0.7 + target * 0.3
            dash = 3.0 * dot
            threshold = 2.0 * dot
        }
    }

    fun score(): TimingScore {
        val dotMean = if (dots.isNotEmpty()) dots.average() else if (dashes.isNotEmpty()) dashes.average() / 3.0 else dot
        fun ratio(xs: List<Double>): Double? = if (xs.isEmpty()) null else xs.average() / dotMean
        val dashDot = if (dots.isEmpty()) null else ratio(dashes)
        val intra = ratio(intraGaps)
        val chr = ratio(charGaps)
        val wrd = ratio(wordGaps)
        val variation = listOf(dots, dashes).filter { it.size >= 3 }.map { cv(it) }.let { if (it.isEmpty()) null else it.average() }

        var penalty = 0.0
        if (dashDot != null) penalty += 40.0 * (abs(dashDot - 3.0) / 3.0).coerceAtMost(1.0)
        if (intra != null) penalty += 15.0 * abs(intra - 1.0).coerceAtMost(1.0)
        if (chr != null) penalty += 25.0 * (abs(chr - 3.0) / 3.0).coerceAtMost(1.0)
        if (wrd != null) penalty += 10.0 * (abs(wrd - 7.0) / 7.0).coerceAtMost(1.0)
        if (variation != null) penalty += 40.0 * variation.coerceAtMost(0.5)
        val marks = dots.size + dashes.size
        return TimingScore(
            wpm = 1200.0 / dotMean,
            dashDotRatio = dashDot, intraGapRatio = intra, charGapRatio = chr, wordGapRatio = wrd,
            markVariation = variation,
            score = if (marks == 0) 0 else (100.0 - penalty).roundToInt().coerceIn(0, 100),
            marks = marks,
        )
    }

    private fun cv(xs: List<Double>): Double {
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size) / m
    }

    companion object {
        const val UNKNOWN = "□" // shown for a pattern that is not a character
        private const val WINDOW = 14
        private const val MIN_SPLIT_RATIO = 1.7
        private const val REGIME_RATIO = 1.6
        private const val CHAR_GAP_DOTS = 2.0
        private const val WORD_GAP_DOTS = 5.0
        private const val PAUSE_DOTS = 14.0
    }
}
