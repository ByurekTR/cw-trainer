package com.cwtrainer.core.audio

import com.cwtrainer.core.morse.KeyingPlan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * One keyed sine oscillator. It is driven either by a scheduled [KeyingPlan] or by a live
 * [gate] (sidetone for the on-screen key).
 *
 * Every edge is a raised cosine of [rampMs] (default 5 ms), which is what keeps key clicks out.
 * The envelope reaches half amplitude ramp/2 after the gate changes on both edges, so element
 * lengths measured at the half-amplitude points are exactly the keyed lengths.
 *
 * Not thread-safe; [CwEngine] owns the locking.
 */
class CwVoice(private val sampleRate: Int, rampMs: Double = 5.0) {
    private val rampStep = 1.0 / (rampMs / 1000.0 * sampleRate)
    private val chirpDecay = exp(-1.0 / (0.012 * sampleRate)) // 12 ms time constant

    /** Live key. Ignored while a plan is scheduled. */
    @Volatile
    var gate: Boolean = false

    private var env = 0.0
    private var phase = 0.0
    private var chirp = 0.0
    private var wasOn = false

    private var segOn = BooleanArray(0)
    private var segLen = IntArray(0)
    private var segIdx = 0
    private var segLeft = 0
    private var symbolEnd = LongArray(0)
    private var nextSymbol = 0
    private var pos = 0L
    private var planLoaded = false

    /** Index of the symbol whose last mark ended on the most recent sample, else -1. */
    var endedSymbol: Int = -1
        private set

    /** True on the one sample where a plan has fully played out and the tone has decayed. */
    var finishedNow: Boolean = false
        private set

    val scheduled: Boolean get() = segIdx < segLen.size
    val busy: Boolean get() = planLoaded

    fun load(plan: KeyingPlan, leadInMs: Double = 0.0) {
        val n = plan.elements.size
        val hasLead = leadInMs > 0.0
        val count = n + if (hasLead) 1 else 0
        segOn = BooleanArray(count)
        segLen = IntArray(count)
        // Cumulative rounding: errors never accumulate, each boundary is within half a sample.
        var tMs = 0.0
        var prev = 0L
        var k = 0
        if (hasLead) {
            tMs = leadInMs
            val b = ms(tMs)
            segOn[k] = false; segLen[k] = (b - prev).toInt(); prev = b; k++
        }
        for (e in plan.elements) {
            tMs += e.ms
            val b = ms(tMs)
            segOn[k] = e.on; segLen[k] = (b - prev).toInt(); prev = b; k++
        }
        symbolEnd = LongArray(plan.symbols.size) { ms(leadInMs.coerceAtLeast(0.0) + plan.symbols[it].endMs) }
        segIdx = 0
        segLeft = if (count > 0) segLen[0] else 0
        nextSymbol = 0
        pos = 0L
        planLoaded = count > 0
        skipEmpty()
    }

    fun clear() {
        segLen = IntArray(0); segOn = BooleanArray(0); segIdx = 0; segLeft = 0
        symbolEnd = LongArray(0); nextSymbol = 0
        planLoaded = false
    }

    private fun ms(t: Double): Long = (t / 1000.0 * sampleRate).roundToLong()

    private fun skipEmpty() {
        while (segIdx < segLen.size && segLeft <= 0) {
            segIdx++
            if (segIdx < segLen.size) segLeft = segLen[segIdx]
        }
    }

    /** Produces the next sample at unit amplitude. */
    fun next(freqHz: Double, chirpHz: Double = 0.0): Double {
        endedSymbol = -1
        finishedNow = false

        val on: Boolean
        if (segIdx < segLen.size) {
            on = segOn[segIdx]
            segLeft--
            pos++
            if (segLeft <= 0) {
                segIdx++
                if (segIdx < segLen.size) segLeft = segLen[segIdx]
                skipEmpty()
            }
            if (nextSymbol < symbolEnd.size && pos >= symbolEnd[nextSymbol]) {
                endedSymbol = nextSymbol
                nextSymbol++
            }
        } else {
            on = gate && !planLoaded
        }

        if (on && !wasOn) chirp = chirpHz
        wasOn = on

        if (on) {
            if (env < 1.0) env = (env + rampStep).coerceAtMost(1.0)
        } else if (env > 0.0) {
            env = (env - rampStep).coerceAtLeast(0.0)
        }

        if (planLoaded && segIdx >= segLen.size && env == 0.0) {
            planLoaded = false
            finishedNow = true
        }

        if (env == 0.0) {
            // Keep the phase running from zero so every element starts on a zero crossing.
            phase = 0.0
            return 0.0
        }

        chirp *= chirpDecay
        phase += TWO_PI * (freqHz + chirp) / sampleRate
        if (phase > TWO_PI) phase -= TWO_PI
        val amp = 0.5 - 0.5 * cos(Math.PI * env)
        return amp * sin(phase)
    }

    private companion object {
        const val TWO_PI = 2.0 * Math.PI
    }
}
