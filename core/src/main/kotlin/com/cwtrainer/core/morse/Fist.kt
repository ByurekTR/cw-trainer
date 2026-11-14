package com.cwtrainer.core.morse

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.random.Random

/**
 * Timing-imperfection model for a human sender.
 *
 * Ratios multiply the ideal lengths; jitter is the standard deviation of a per-element random
 * factor (0.08 = 8 %).
 */
data class FistModel(
    val name: String,
    val description: String,
    val dotRatio: Double = 1.0,
    val dashRatio: Double = 1.0,
    val intraGapRatio: Double = 1.0,
    val charGapRatio: Double = 1.0,
    val wordGapRatio: Double = 1.0,
    val markJitter: Double = 0.0,
    val gapJitter: Double = 0.0,
) {
    val isPerfect: Boolean
        get() = this == MACHINE

    companion object {
        val MACHINE = FistModel("Machine", "Perfect keyer timing")
        val STEADY = FistModel(
            "Steady hand", "A good operator on a paddle: tiny variations",
            markJitter = 0.04, gapJitter = 0.07,
        )
        val HEAVY_DASHES = FistModel(
            "Heavy dashes", "Straight-key swing: long dashes, clipped dots",
            dotRatio = 0.9, dashRatio = 1.22, markJitter = 0.07, gapJitter = 0.10,
        )
        val RUSHED = FistModel(
            "Rushed spacing", "Characters and words run together",
            intraGapRatio = 0.92, charGapRatio = 0.78, wordGapRatio = 0.7,
            markJitter = 0.06, gapJitter = 0.12,
        )
        val DELIBERATE = FistModel(
            "Slow and deliberate", "Careful sender leaving wide gaps",
            dotRatio = 1.08, dashRatio = 1.05, charGapRatio = 1.35, wordGapRatio = 1.4,
            markJitter = 0.05, gapJitter = 0.09,
        )

        val personalities = listOf(STEADY, HEAVY_DASHES, RUSHED, DELIBERATE)
        val all = listOf(MACHINE) + personalities
    }
}

internal fun Random.gaussian(): Double {
    var u = nextDouble()
    while (u <= 1e-12) u = nextDouble()
    return sqrt(-2.0 * ln(u)) * cos(2.0 * Math.PI * nextDouble())
}

/** Random factor around 1.0, clamped so an element never collapses or triples. */
internal fun Random.jitterFactor(sd: Double): Double =
    if (sd <= 0.0) 1.0 else (1.0 + gaussian() * sd).coerceIn(0.6, 1.6)
