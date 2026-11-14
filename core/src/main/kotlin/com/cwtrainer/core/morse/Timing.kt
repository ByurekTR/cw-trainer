package com.cwtrainer.core.morse

/**
 * PARIS-standard timing with Farnsworth spacing.
 *
 * "PARIS " is 50 dot units: 31 units inside the characters (marks plus intra-character gaps),
 * 4 character gaps of 3 units and one word gap of 7 units (19 units of spacing).
 * One dot at c WPM lasts 1200/c ms.
 *
 * With Farnsworth, characters are sent at [charWpm] and only the 19 spacing units are stretched
 * so the overall rate comes out at [effWpm]:
 *
 *     t_a   = (60*c - 37.2*s) / (s*c)   seconds of spacing per word
 *     t_chr = 3 * t_a / 19
 *     t_wrd = 7 * t_a / 19
 */
data class Timing(val charWpm: Double, val effWpm: Double = charWpm) {
    init {
        require(charWpm > 0 && effWpm > 0) { "WPM must be positive" }
    }

    /** Effective speed can never exceed character speed. */
    val effective: Double get() = minOf(effWpm, charWpm)

    val dotMs: Double get() = 1200.0 / charWpm
    val dashMs: Double get() = 3 * dotMs
    val intraGapMs: Double get() = dotMs

    private val spacingPerWordMs: Double
        get() = (60.0 * charWpm - 37.2 * effective) / (effective * charWpm) * 1000.0

    val charGapMs: Double get() = 3.0 * spacingPerWordMs / 19.0
    val wordGapMs: Double get() = 7.0 * spacingPerWordMs / 19.0

    /** Duration of one standard word ("PARIS" plus its word gap). 60000 / effective by construction. */
    val wordMs: Double get() = 31 * dotMs + 4 * charGapMs + wordGapMs
}
