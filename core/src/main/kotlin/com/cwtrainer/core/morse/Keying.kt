package com.cwtrainer.core.morse

import kotlin.random.Random

/** One stretch of key-down ([on] = true) or key-up time. */
data class KeyElement(val on: Boolean, val ms: Double)

/** Where one symbol sits inside a plan. A word gap is not a symbol. */
data class KeyedSymbol(val symbol: String, val startMs: Double, val endMs: Double)

/** A fully timed transmission, ready for the audio engine. Starts and ends with a mark. */
class KeyingPlan(val elements: List<KeyElement>, val symbols: List<KeyedSymbol>) {
    val totalMs: Double = elements.sumOf { it.ms }
    val isEmpty: Boolean get() = elements.isEmpty()
}

object Keyer {
    /**
     * Turns text into key-down/key-up times.
     * With [fist] == MACHINE the result is exact PARIS/Farnsworth timing.
     */
    fun plan(
        text: String,
        timing: Timing,
        fist: FistModel = FistModel.MACHINE,
        rng: Random = Random.Default,
    ): KeyingPlan = plan(MorseCode.tokenize(text), timing, fist, rng)

    fun plan(
        symbols: List<String>,
        timing: Timing,
        fist: FistModel = FistModel.MACHINE,
        rng: Random = Random.Default,
    ): KeyingPlan {
        val elements = ArrayList<KeyElement>()
        val placed = ArrayList<KeyedSymbol>()
        var t = 0.0
        var pendingGap = 0.0 // gap owed before the next symbol

        fun add(on: Boolean, ms: Double) {
            elements.add(KeyElement(on, ms))
            t += ms
        }

        for (sym in symbols) {
            if (sym == " ") {
                if (placed.isNotEmpty()) {
                    pendingGap = timing.wordGapMs * fist.wordGapRatio * rng.jitterFactor(fist.gapJitter)
                }
                continue
            }
            val pattern = MorseCode.pattern(sym) ?: continue
            if (pendingGap > 0.0) add(false, pendingGap)
            val start = t
            pattern.forEachIndexed { i, c ->
                if (i > 0) add(false, timing.intraGapMs * fist.intraGapRatio * rng.jitterFactor(fist.gapJitter))
                val ideal = if (c == '.') timing.dotMs * fist.dotRatio else timing.dashMs * fist.dashRatio
                add(true, ideal * rng.jitterFactor(fist.markJitter))
            }
            placed.add(KeyedSymbol(sym, start, t))
            pendingGap = timing.charGapMs * fist.charGapRatio * rng.jitterFactor(fist.gapJitter)
        }
        return KeyingPlan(elements, placed)
    }
}
