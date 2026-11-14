package com.cwtrainer.core.audio

import com.cwtrainer.core.morse.FistModel
import com.cwtrainer.core.morse.Keyer
import com.cwtrainer.core.morse.KeyingPlan
import com.cwtrainer.core.morse.Timing
import com.cwtrainer.core.send.IambicKeyer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * The software mixer. Platform code (AudioTrack on Android) just pulls mono float samples
 * with [render]; everything audible is produced here:
 *
 *  - the wanted signal (scheduled [KeyingPlan]) with QSB, drift and chirp
 *  - the sidetone for the on-screen straight key / iambic paddle
 *  - QRM: a second, weaker station sending unrelated text at a nearby pitch
 *  - QRN: band-limited noise and static crashes at an exact SNR
 *
 * v2 plug-in points: a pile-up simulator adds more [CwVoice]s to the mix loop in [render];
 * a background playlist just keeps calling [play] from a foreground service.
 */
class CwEngine(val sampleRate: Int, seed: Long = System.nanoTime()) {

    interface Listener {
        /** A symbol finished sounding. Called on the audio thread. */
        fun onSymbolEnd(playId: Long, index: Int) {}

        /** The whole plan finished sounding. Called on the audio thread. */
        fun onFinished(playId: Long) {}

        /** The iambic keyer closed or opened the key; [timeMs] is on the engine clock. */
        fun onKeyerEdge(down: Boolean, timeMs: Double) {}
    }

    private val lock = Any()
    private val rng = Random(seed)

    private val main = CwVoice(sampleRate)
    private val sidetone = CwVoice(sampleRate)
    private val qrm = CwVoice(sampleRate)
    private val noise = BandNoise(sampleRate, rng)

    @Volatile
    var listener: Listener? = null

    /** Master level 0..1. The device volume keys still apply on top of this. */
    @Volatile
    var volume: Double = 0.9

    /** Supplies unrelated text for the interfering station. */
    @Volatile
    var qrmText: () -> String = { "CQ CQ CQ DE DL1ABC DL1ABC K" }

    private var pitch = 600.0
    private var cond = BandConditions.CLEAN
    private var noiseRms = 0.0
    private var qrmAmp = 0.0
    private var mixGain = 1.0

    private var playId = 0L
    private var frames = 0L
    private var qsbGain = 1.0
    private var qrmFade = 1.0
    private var drift = 0.0
    private var qrmPause = 0
    private val phi = DoubleArray(6) { rng.nextDouble() * 2 * Math.PI }

    private var keyer: IambicKeyer? = null
    private var keyerDown = false

    // Scratch space for events raised inside the lock and delivered outside it.
    private val evSymbol = IntArray(64)
    private var evSymbolCount = 0
    private val evKeyDown = BooleanArray(64)
    private val evKeyTime = DoubleArray(64)
    private var evKeyCount = 0

    init {
        configure()
    }

    var pitchHz: Double
        get() = pitch
        set(value) = synchronized(lock) { pitch = value.coerceIn(200.0, 1500.0); configure() }

    var conditions: BandConditions
        get() = cond
        set(value) = synchronized(lock) {
            cond = value
            if (value.qrmLevelDb == null) qrm.clear()
            configure()
        }

    /** Milliseconds of audio rendered so far. */
    val clockMs: Double get() = frames * 1000.0 / sampleRate

    val isPlaying: Boolean get() = synchronized(lock) { main.busy }

    private fun configure() {
        val snr = cond.snrDb
        noiseRms = if (snr == null) 0.0 else TONE_RMS / 10.0.pow(snr / 20.0)
        if (snr != null) noise.configure(pitch, cond.filterWidthHz, cond.staticCrashesPerMin)
        qrmAmp = cond.qrmLevelDb?.let { SIGNAL_AMP * 10.0.pow(it / 20.0) } ?: 0.0
        // Leave headroom for noise peaks so the limiter is only touched by static crashes.
        mixGain = minOf(1.0, 0.92 / (SIGNAL_AMP + qrmAmp + 3.0 * noiseRms))
    }

    /** Starts sending [plan] (replacing anything in progress). Returns an id for the callbacks. */
    fun play(plan: KeyingPlan, leadInMs: Double = 250.0): Long = synchronized(lock) {
        playId++
        main.load(plan, leadInMs)
        playId
    }

    fun stopPlayback() = synchronized(lock) { main.clear() }

    /** Straight key sidetone. */
    fun setKey(down: Boolean) {
        sidetone.gate = down
    }

    /** Hands the sidetone to an iambic keyer (or back to the straight key with null). */
    fun setKeyer(k: IambicKeyer?) = synchronized(lock) {
        keyer = k
        keyerDown = false
        sidetone.gate = false
    }

    fun render(out: FloatArray, frameCount: Int) {
        val l = listener
        var finishedId = -1L
        val id: Long
        synchronized(lock) {
            id = playId
            evSymbolCount = 0
            evKeyCount = 0
            val dtMs = 1000.0 / sampleRate
            val k = keyer
            for (i in 0 until frameCount) {
                if (frames and 31L == 0L) updateSlow()

                if (k != null) {
                    val down = k.advance(dtMs)
                    if (down != keyerDown) {
                        keyerDown = down
                        sidetone.gate = down
                        if (evKeyCount < evKeyDown.size) {
                            evKeyDown[evKeyCount] = down
                            evKeyTime[evKeyCount] = frames * dtMs
                            evKeyCount++
                        }
                    }
                }

                var x = main.next(pitch + drift, cond.chirpHz) * SIGNAL_AMP * qsbGain
                if (main.endedSymbol >= 0 && evSymbolCount < evSymbol.size) evSymbol[evSymbolCount++] = main.endedSymbol
                if (main.finishedNow) finishedId = id

                x += sidetone.next(pitch) * SIGNAL_AMP

                if (qrmAmp > 0.0) {
                    if (!qrm.busy) {
                        if (qrmPause > 0) qrmPause-- else startQrm()
                    }
                    x += qrm.next(pitch + cond.qrmOffsetHz - drift * 0.6, cond.chirpHz * 1.5) * qrmAmp * qrmFade
                }
                if (noiseRms > 0.0) x += noise.next() * noiseRms

                out[i] = (limit(x * mixGain) * volume).toFloat()
                frames++
            }
        }
        if (l != null) {
            for (i in 0 until evSymbolCount) l.onSymbolEnd(id, evSymbol[i])
            for (i in 0 until evKeyCount) l.onKeyerEdge(evKeyDown[i], evKeyTime[i])
            if (finishedId >= 0) l.onFinished(finishedId)
        }
    }

    private fun updateSlow() {
        val t = frames.toDouble() / sampleRate
        qsbGain = fade(cond.qsbDepthDb, cond.qsbPeriodS, t, phi[0], phi[1])
        qrmFade = if (qrmAmp > 0.0) fade(maxOf(cond.qsbDepthDb, 4.0), cond.qsbPeriodS * 1.7, t, phi[2], phi[3]) else 1.0
        drift = if (cond.driftHz == 0.0) 0.0 else
            cond.driftHz * (0.7 * sin(TWO_PI * t / 23.0 + phi[4]) + 0.3 * sin(TWO_PI * t / 7.3 + phi[5]))
    }

    /** Slow two-component fade between 0 dB and -[depthDb]. */
    private fun fade(depthDb: Double, periodS: Double, t: Double, p1: Double, p2: Double): Double {
        if (depthDb <= 0.0) return 1.0
        val f = 0.5 + 0.5 * (0.65 * sin(TWO_PI * t / periodS + p1) + 0.35 * sin(TWO_PI * t / (periodS * 0.37) + p2))
        return 10.0.pow(-depthDb * f / 20.0)
    }

    private fun startQrm() {
        val wpm = 18.0 + rng.nextDouble() * 10.0
        qrm.load(Keyer.plan(qrmText(), Timing(wpm), FistModel.STEADY, rng))
        qrmPause = ((0.4 + rng.nextDouble() * 2.5) * sampleRate).toInt()
    }

    private fun limit(x: Double): Double {
        val a = abs(x)
        if (a <= KNEE) return x
        val y = KNEE + (1.0 - KNEE) * tanh((a - KNEE) / (1.0 - KNEE))
        return if (x < 0) -y else y
    }

    companion object {
        /** Peak amplitude of the wanted signal before master volume. */
        const val SIGNAL_AMP = 0.45
        val TONE_RMS = SIGNAL_AMP / Math.sqrt(2.0)
        private const val KNEE = 0.8
        private const val TWO_PI = 2.0 * Math.PI
    }
}
