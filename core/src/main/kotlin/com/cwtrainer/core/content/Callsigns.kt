package com.cwtrainer.core.content

import kotlin.random.Random

/** Realistic amateur callsigns, with Turkish prefixes well represented. */
object Callsigns {
    /** Turkey: TA and TB are the everyday prefixes, YM is used for special and contest calls. */
    val turkishPrefixes = listOf("TA", "TA", "TA", "TB", "YM")

    /** prefix to "is the digit already part of the prefix" */
    private val world: List<Pair<String, Boolean>> = listOf(
        "DL" to false, "DJ" to false, "DK" to false, "G" to false, "M" to false, "F" to false,
        "I" to false, "IK" to false, "EA" to false, "K" to false, "W" to false, "N" to false,
        "AA" to false, "KD" to false, "JA" to false, "UA" to false, "RA" to false, "SP" to false,
        "OK" to false, "OM" to false, "LZ" to false, "YO" to false, "SV" to false, "YU" to false,
        "OH" to false, "SM" to false, "LA" to false, "OZ" to false, "PA" to false, "ON" to false,
        "OE" to false, "HA" to false, "UR" to false, "VE" to false, "VK" to false, "ZL" to false,
        "PY" to false, "LU" to false, "ZS" to false, "9A" to false, "4X" to false, "5B" to false,
        "HB9" to true, "S5" to false, "E7" to false, "Z3" to false, "4O" to false,
    )

    private const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun suffix(rng: Random, min: Int = 1, max: Int = 3): String {
        val n = min + rng.nextInt(max - min + 1)
        return buildString { repeat(n) { append(LETTERS[rng.nextInt(26)]) } }
    }

    /** TA/TB/YM + call area 0-9 + 1-3 letters (most Turkish calls have 2 or 3). */
    fun turkish(rng: Random): String =
        turkishPrefixes[rng.nextInt(turkishPrefixes.size)] + rng.nextInt(10) + suffix(rng, if (rng.nextInt(5) == 0) 1 else 2, 3)

    fun world(rng: Random): String {
        val (prefix, hasDigit) = world[rng.nextInt(world.size)]
        return if (hasDigit) prefix + suffix(rng, 2, 3) else prefix + rng.nextInt(10) + suffix(rng, 1, 3)
    }

    /** About four in ten are Turkish; a few carry a /P or /QRP suffix. */
    fun random(rng: Random): String {
        val base = if (rng.nextInt(10) < 4) turkish(rng) else world(rng)
        return when (rng.nextInt(14)) {
            0 -> "$base/P"
            1 -> "$base/QRP"
            else -> base
        }
    }
}
