package com.cwtrainer.core

import com.cwtrainer.core.audio.BandConditions
import com.cwtrainer.core.audio.CwEngine
import com.cwtrainer.core.morse.Keyer
import com.cwtrainer.core.morse.Timing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class AudioTest {
    private val sr = 48000

    private fun render(engine: CwEngine, seconds: Double): FloatArray {
        val n = (seconds * sr).toInt()
        val out = FloatArray(n)
        val block = FloatArray(480)
        var i = 0
        while (i < n) {
            val c = minOf(block.size, n - i)
            engine.render(block, c)
            System.arraycopy(block, 0, out, i, c)
            i += c
        }
        return out
    }

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from))
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cos(ang * k); val wi = sin(ang * k)
                    val a = i + k; val b = i + k + len / 2
                    val xr = re[b] * wr - im[b] * wi; val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
            }
            len = len shl 1
        }
    }

    /** Fraction of energy further than [guardHz] from the carrier, in dB. */
    private fun outOfBandDb(x: FloatArray, carrier: Double, guardHz: Double): Double {
        val n = 1 shl 17
        val re = DoubleArray(n) { if (it < x.size) x[it].toDouble() else 0.0 }
        val im = DoubleArray(n)
        fft(re, im)
        var inBand = 0.0; var outBand = 0.0
        for (k in 1 until n / 2) {
            val f = k.toDouble() * sr / n
            val p = re[k] * re[k] + im[k] * im[k]
            if (abs(f - carrier) > guardHz) outBand += p else inBand += p
        }
        return 10 * log10(outBand / (inBand + outBand))
    }

    @Test fun elementTimingIsExactAtHalfAmplitude() {
        for (wpm in listOf(15.0, 20.0, 30.0)) {
            val engine = CwEngine(sr, seed = 1)
            engine.volume = 1.0
            val timing = Timing(wpm)
            engine.play(Keyer.plan("K", timing), leadInMs = 50.0)      // -.-
            val x = render(engine, 1.2)
            // Envelope by peak-hold over one tone cycle.
            val half = CwEngine.SIGNAL_AMP / 2
            val win = sr / 600
            val edges = ArrayList<Int>()
            var on = false
            for (i in 0 until x.size - win) {
                var peak = 0f
                for (j in i until i + win) peak = maxOf(peak, abs(x[j]))
                val now = peak >= half
                if (now != on) { edges.add(i + win / 2); on = now }
            }
            assertEquals(6, edges.size)
            val ms = { a: Int, b: Int -> (edges[b] - edges[a]) * 1000.0 / sr }
            val tol = 1.5 // peak-hold measurement error, in ms
            assertEquals("dash at $wpm", timing.dashMs, ms(0, 1), tol)
            assertEquals("gap at $wpm", timing.dotMs, ms(1, 2), tol)
            assertEquals("dot at $wpm", timing.dotMs, ms(2, 3), tol)
            assertEquals("dash at $wpm", timing.dashMs, ms(4, 5), tol)
        }
    }

    @Test fun shapedKeyingHasNoClicks() {
        val engine = CwEngine(sr, seed = 1)
        engine.play(Keyer.plan("PARIS", Timing(30.0)), leadInMs = 20.0)
        val shaped = render(engine, 2.6)

        // The same keying with hard (instant) edges, for comparison.
        val plan = Keyer.plan("PARIS", Timing(30.0))
        val hard = FloatArray(shaped.size)
        var t = 0.02
        for (e in plan.elements) {
            val a = (t * sr).toInt(); val b = ((t + e.ms / 1000) * sr).toInt()
            if (e.on) for (i in a until minOf(b, hard.size)) hard[i] = (0.45 * sin(2 * PI * 600.0 * i / sr + 1.0)).toFloat()
            t += e.ms / 1000
        }
        val shapedDb = outOfBandDb(shaped, 600.0, 400.0)
        val hardDb = outOfBandDb(hard, 600.0, 400.0)
        assertTrue("shaped keying splatter $shapedDb dB", shapedDb < -55.0)
        assertTrue("hard keying is far worse ($hardDb dB)", hardDb > shapedDb + 25.0)

        // No sample-to-sample jump bigger than a 600 Hz sine at full level can make, plus margin.
        var maxStep = 0f
        for (i in 1 until shaped.size) maxStep = maxOf(maxStep, abs(shaped[i] - shaped[i - 1]))
        assertTrue("max step $maxStep", maxStep < 0.45 * 2 * PI * 600 / sr * 1.15)
    }

    @Test fun cleanPresetIsSilentBetweenElementsAndNeverClips() {
        val engine = CwEngine(sr, seed = 1)
        engine.play(Keyer.plan("E", Timing(20.0)), leadInMs = 100.0)
        val x = render(engine, 0.5)
        assertEquals(0.0, rms(x, 0, (0.09 * sr).toInt()), 0.0)
        assertEquals(0.0, rms(x, (0.2 * sr).toInt(), x.size), 0.0)
        assertTrue(x.all { abs(it) <= 0.45f })
    }

    @Test fun noiseLevelMatchesTheRequestedSnr() {
        for (snr in listOf(20.0, 10.0, 6.0, 0.0)) {
            val cond = BandConditions("test", snrDb = snr)
            val quiet = CwEngine(sr, seed = 3).also { it.conditions = cond; it.volume = 1.0 }
            val n = rms(render(quiet, 3.0))
            val keyed = CwEngine(sr, seed = 3).also { it.conditions = cond; it.volume = 1.0 }
            keyed.play(Keyer.plan("T", Timing(1.0)), leadInMs = 0.0)   // one 3.6 s dash
            val both = render(keyed, 3.0)
            val p = rms(both, sr / 10, both.size)
            val s = sqrt(p * p - n * n)
            assertEquals("requested $snr dB", snr, 20 * log10(s / n), 0.7)
        }
    }

    @Test fun noiseIsConcentratedInTheReceiveFilter() {
        val engine = CwEngine(sr, seed = 5).also { it.conditions = BandConditions("n", snrDb = 0.0); it.pitchHz = 700.0 }
        val x = render(engine, 3.0)
        assertTrue(outOfBandDb(x, 700.0, 600.0) < -12.0)
    }

    @Test fun presetsStayWithinRangeAndGetHarder() {
        var lastNoise = -1.0
        for (preset in BandConditions.presets) {
            val engine = CwEngine(sr, seed = 9)
            engine.conditions = preset
            engine.play(Keyer.plan("CQ CQ DE TA2ABC TA2ABC K", Timing(20.0)))
            val x = render(engine, 12.0)
            assertTrue(preset.name, x.all { abs(it) <= 1.0f })
            val clipped = x.count { abs(it) > 0.85f }.toDouble() / x.size
            assertTrue("${preset.name} clipped $clipped", clipped < 0.002)
            val quiet = CwEngine(sr, seed = 9).also { it.conditions = preset.copy(qrmLevelDb = null) }
            val noise = rms(render(quiet, 4.0))
            assertTrue("${preset.name} noise $noise", noise > lastNoise)
            lastNoise = noise
        }
        assertEquals(0.0, rms(render(CwEngine(sr, seed = 1), 0.5)), 0.0)
    }

    @Test fun qsbFadesTheSignalByTheConfiguredDepth() {
        val engine = CwEngine(sr, seed = 2)
        engine.conditions = BandConditions("qsb", qsbDepthDb = 12.0, qsbPeriodS = 2.0)
        engine.play(Keyer.plan("T", Timing(0.2)), leadInMs = 0.0)   // 18 s carrier
        val x = render(engine, 12.0)
        var lo = Double.MAX_VALUE; var hi = 0.0
        val w = sr / 20
        for (i in sr until x.size - w step w) { val r = rms(x, i, i + w); lo = minOf(lo, r); hi = maxOf(hi, r) }
        val depth = 20 * log10(hi / lo)
        assertTrue("fade depth $depth dB", depth in 8.0..12.5)
    }

    @Test fun qrmAddsASecondWeakerSignalAtANearbyPitch() {
        val engine = CwEngine(sr, seed = 4)
        engine.conditions = BandConditions("qrm", qrmLevelDb = -10.0, qrmOffsetHz = 230.0)
        val x = render(engine, 8.0)
        val re = DoubleArray(1 shl 17) { x[it + sr].toDouble() }
        val im = DoubleArray(re.size)
        fft(re, im)
        var peak = 0; var best = 0.0
        for (k in 1 until re.size / 2) { val p = re[k] * re[k] + im[k] * im[k]; if (p > best) { best = p; peak = k } }
        assertEquals(830.0, peak.toDouble() * sr / re.size, 5.0)
        assertTrue(x.maxOf { abs(it) } < 0.45f * 0.5f)
    }

    @Test fun callbacksReportSymbolsAndCompletion() {
        val engine = CwEngine(sr, seed = 1)
        val symbols = ArrayList<Int>()
        var finished = -1L
        var finishedAtMs = 0.0
        engine.listener = object : CwEngine.Listener {
            override fun onSymbolEnd(playId: Long, index: Int) { symbols.add(index) }
            override fun onFinished(playId: Long) { finished = playId; finishedAtMs = engine.clockMs }
        }
        val plan = Keyer.plan("KM", Timing(20.0, 10.0))
        val id = engine.play(plan, leadInMs = 100.0)
        assertTrue(engine.isPlaying)
        render(engine, 3.0)
        assertEquals(listOf(0, 1), symbols)
        assertEquals(id, finished)
        assertEquals(100.0 + plan.totalMs, finishedAtMs, 20.0)
        assertTrue(!engine.isPlaying)
    }

    @Test fun sidetoneFollowsTheKeyWithShapedEdges() {
        val engine = CwEngine(sr, seed = 1)
        engine.setKey(true)
        val on = render(engine, 0.2)
        engine.setKey(false)
        val off = render(engine, 0.2)
        assertTrue(rms(on, sr / 20, on.size) > 0.25)
        assertEquals(0.0, rms(off, sr / 20, off.size), 0.0)
        var maxStep = 0f
        val all = on + off
        for (i in 1 until all.size) maxStep = maxOf(maxStep, abs(all[i] - all[i - 1]))
        assertTrue(maxStep < 0.45 * 2 * PI * 600 / sr * 1.15)
    }
}
