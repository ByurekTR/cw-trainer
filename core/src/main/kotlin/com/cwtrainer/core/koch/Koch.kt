package com.cwtrainer.core.koch

import kotlin.math.roundToInt

/**
 * Koch-method progression. The "level" is simply how many characters are unlocked (2..41).
 */
object Koch {
    val ORDER: List<String> =
        "K M U R E S N A P T L W I . J Z = F O Y , V G 5 / Q 9 2 H 3 8 B ? 4 7 C 1 D 6 0 X".split(" ")

    const val MIN_LEVEL = 2
    val MAX_LEVEL = ORDER.size

    const val UNLOCK_ACCURACY = 0.90
    const val UNLOCK_SESSION_SECONDS = 5 * 60
    /** Guards against "one lucky group" sessions being counted. */
    const val UNLOCK_MIN_CHARACTERS = 40

    const val START_EFFECTIVE_WPM = 8.0

    fun clamp(level: Int): Int = level.coerceIn(MIN_LEVEL, MAX_LEVEL)

    fun unlocked(level: Int): List<String> = ORDER.take(clamp(level))

    fun newest(level: Int): String = ORDER[clamp(level) - 1]

    fun next(level: Int): String? = ORDER.getOrNull(clamp(level))

    /** True once every letter A-Z is unlocked. (X is last in Koch order, so this is the top level.) */
    fun allLettersLearned(level: Int): Boolean {
        val have = unlocked(level).toSet()
        return ('A'..'Z').all { it.toString() in have }
    }

    fun allLearned(level: Int): Boolean = clamp(level) >= MAX_LEVEL

    /**
     * The rule from the brief: at least 90 % over a 5-minute receive session.
     * @param accuracy fraction of sent characters copied correctly, 0..1
     * @param activeSeconds how long the session ran
     * @param characters how many characters were sent
     */
    fun shouldUnlockNext(level: Int, accuracy: Double, activeSeconds: Int, characters: Int): Boolean =
        clamp(level) < MAX_LEVEL &&
            accuracy >= UNLOCK_ACCURACY &&
            activeSeconds >= UNLOCK_SESSION_SECONDS &&
            characters >= UNLOCK_MIN_CHARACTERS

    fun levelAfterSession(level: Int, accuracy: Double, activeSeconds: Int, characters: Int): Int =
        if (shouldUnlockNext(level, accuracy, activeSeconds, characters)) clamp(level) + 1 else clamp(level)

    /**
     * Farnsworth schedule: 8 WPM effective with the first two characters, tightening linearly
     * until effective speed equals character speed when the last character is unlocked.
     * Rounded to 0.5 WPM. If the character speed is set below 8 the result is the character speed.
     */
    fun effectiveWpm(level: Int, charWpm: Double): Double {
        val start = minOf(START_EFFECTIVE_WPM, charWpm)
        val f = (clamp(level) - MIN_LEVEL).toDouble() / (MAX_LEVEL - MIN_LEVEL)
        val eff = start + (charWpm - start) * f
        return ((eff * 2).roundToInt() / 2.0).coerceIn(start, charWpm)
    }
}
