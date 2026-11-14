package com.cwtrainer.core.audio

/**
 * What the "band" does to the wanted signal. All effects are mixed in software by [CwEngine].
 *
 * @param snrDb signal-to-noise ratio measured inside the receive filter; null = no noise (QRN off)
 * @param staticCrashesPerMin impulsive static bursts on top of the steady noise
 * @param qsbDepthDb how far the signal fades at the bottom of a fade (0 = no QSB)
 * @param qsbPeriodS length of the main fading cycle
 * @param qrmLevelDb level of the interfering station relative to the wanted one; null = no QRM
 * @param qrmOffsetHz pitch of the interfering station relative to the wanted one
 * @param driftHz slow wander of the wanted signal's pitch (peak)
 * @param chirpHz pitch pull at the start of every element
 * @param filterWidthHz receive filter bandwidth that shapes the noise
 */
data class BandConditions(
    val name: String,
    val snrDb: Double? = null,
    val staticCrashesPerMin: Double = 0.0,
    val qsbDepthDb: Double = 0.0,
    val qsbPeriodS: Double = 8.0,
    val qrmLevelDb: Double? = null,
    val qrmOffsetHz: Double = 0.0,
    val driftHz: Double = 0.0,
    val chirpHz: Double = 0.0,
    val filterWidthHz: Double = 500.0,
) {
    companion object {
        val CLEAN = BandConditions("Clean")
        val MILD = BandConditions(
            "Mild", snrDb = 20.0, staticCrashesPerMin = 2.0,
            qsbDepthDb = 3.0, qsbPeriodS = 12.0, driftHz = 2.0,
        )
        val REAL_BAND = BandConditions(
            "Real band", snrDb = 10.0, staticCrashesPerMin = 10.0,
            qsbDepthDb = 9.0, qsbPeriodS = 8.0,
            qrmLevelDb = -10.0, qrmOffsetHz = 230.0, driftHz = 5.0, chirpHz = 8.0,
        )
        val CONTEST = BandConditions(
            "Contest", snrDb = 6.0, staticCrashesPerMin = 6.0,
            qsbDepthDb = 6.0, qsbPeriodS = 5.0,
            qrmLevelDb = -4.0, qrmOffsetHz = -170.0, driftHz = 4.0, chirpHz = 6.0,
            filterWidthHz = 400.0,
        )
        val presets = listOf(CLEAN, MILD, REAL_BAND, CONTEST)

        fun byName(name: String): BandConditions = presets.firstOrNull { it.name == name } ?: CLEAN
    }
}
