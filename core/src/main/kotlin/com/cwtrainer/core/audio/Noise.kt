package com.cwtrainer.core.audio

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** RBJ constant-peak-gain band-pass biquad. */
internal class Biquad {
    private var b0 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0
    private var y1 = 0.0; private var y2 = 0.0

    fun bandpass(sampleRate: Int, centerHz: Double, q: Double) {
        val w = 2.0 * Math.PI * centerHz / sampleRate
        val alpha = sin(w) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = alpha / a0
        b2 = -alpha / a0
        a1 = -2.0 * cos(w) / a0
        a2 = (1.0 - alpha) / a0
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }
}

/**
 * Receiver noise: white noise through a two-stage band-pass centred on the listening pitch
 * (what a CW filter does to band noise), plus a trace of wideband hiss, plus static crashes.
 * Output is normalised to unit RMS so the engine can set an exact SNR.
 */
internal class BandNoise(private val sampleRate: Int, private val rng: Random) {
    private val f1 = Biquad()
    private val f2 = Biquad()
    private var gain = 1.0

    private var crashEnv = 0.0
    private var crashDecay = 0.0
    private var crashProbPerSample = 0.0

    fun configure(centerHz: Double, widthHz: Double, crashesPerMin: Double) {
        val q = (centerHz / widthHz).coerceIn(0.5, 12.0)
        f1.bandpass(sampleRate, centerHz, q)
        f2.bandpass(sampleRate, centerHz, q)
        // Calibrate: measure the filter's RMS response to unit white noise, after it has settled.
        val cal = Random(12345)
        var sum = 0.0
        val settle = sampleRate / 10
        val n = sampleRate / 2
        for (i in 0 until settle + n) {
            val y = shaped(white(cal))
            if (i >= settle) sum += y * y
        }
        gain = 1.0 / sqrt(sum / n).coerceAtLeast(1e-9)
        f1.bandpass(sampleRate, centerHz, q)
        f2.bandpass(sampleRate, centerHz, q)
        crashProbPerSample = crashesPerMin / 60.0 / sampleRate
        crashEnv = 0.0
    }

    private fun white(r: Random): Double =
        // Sum of four uniforms: close enough to Gaussian, unit variance, much cheaper.
        (r.nextDouble() + r.nextDouble() + r.nextDouble() + r.nextDouble() - 2.0) * SQRT3

    private fun shaped(w: Double): Double = f2.process(f1.process(w)) + HISS * w

    fun next(): Double {
        if (crashProbPerSample > 0.0 && rng.nextDouble() < crashProbPerSample) {
            crashEnv = 2.0 + rng.nextDouble() * 4.0                       // +9.5 .. +17 dB burst
            val seconds = 0.03 + rng.nextDouble() * 0.15
            crashDecay = exp(-1.0 / (seconds * sampleRate))
        }
        val y = shaped(white(rng)) * gain
        if (crashEnv > 0.0) {
            val out = y * (1.0 + crashEnv)
            crashEnv *= crashDecay
            if (crashEnv < 0.01) crashEnv = 0.0
            return out
        }
        return y
    }

    private companion object {
        val SQRT3 = sqrt(3.0)
        const val HISS = 0.02
    }
}
