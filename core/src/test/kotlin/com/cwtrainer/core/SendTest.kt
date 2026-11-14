package com.cwtrainer.core

import com.cwtrainer.core.morse.FistModel
import com.cwtrainer.core.morse.Keyer
import com.cwtrainer.core.morse.Timing
import com.cwtrainer.core.send.IambicKeyer
import com.cwtrainer.core.send.SendDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SendTest {
    /** Plays a keying plan into the decoder as if a person had sent it, ticking every 10 ms. */
    private fun feed(dec: SendDecoder, text: String, timing: Timing, fist: FistModel = FistModel.MACHINE, seed: Int = 1, startMs: Double = 0.0): Double {
        val plan = Keyer.plan(text, timing, fist, Random(seed))
        var t = startMs
        var tick = startMs
        for (e in plan.elements) {
            if (e.on) dec.keyDown(t)
            val end = t + e.ms
            while (tick < end) { if (!e.on) dec.tick(tick); tick += 10.0 }
            t = end
            if (e.on) dec.keyUp(t)
        }
        val settle = t + 20 * timing.dotMs
        while (tick < settle) { dec.tick(tick); tick += 10.0 }
        return settle
    }

    @Test fun decodesPerfectSendingAtManySpeeds() {
        for (wpm in listOf(8.0, 12.0, 15.0, 20.0, 25.0, 30.0)) {
            val dec = SendDecoder(initialWpm = 15.0)
            feed(dec, "CQ CQ DE TA2ABC K", Timing(wpm))
            assertEquals("at $wpm WPM", "CQ CQ DE TA2ABC K", dec.decoded.trim())
            assertEquals(wpm, dec.estimatedWpm, wpm * 0.05)
        }
    }

    @Test fun decodesWithoutTicksBetweenCharacters() {
        val dec = SendDecoder()
        val plan = Keyer.plan("PARIS 73", Timing(18.0))
        var t = 0.0
        for (e in plan.elements) {
            if (e.on) dec.keyDown(t)
            t += e.ms
            if (e.on) dec.keyUp(t)
        }
        dec.tick(t + 2000)
        assertEquals("PARIS 73", dec.decoded.trim())
    }

    @Test fun toleratesAHumanFist() {
        for (fist in FistModel.personalities - FistModel.RUSHED) {
            for (seed in 1..5) {
                val dec = SendDecoder(initialWpm = 15.0)
                feed(dec, "TNX FER QSO 73", Timing(18.0), fist, seed)
                assertEquals("${fist.name} seed $seed", "TNXFERQSO73", dec.decoded.replace(" ", ""))
            }
        }
    }

    @Test fun followsASpeedChange() {
        val dec = SendDecoder(initialWpm = 20.0)
        val t1 = feed(dec, "RST 599", Timing(25.0))
        val t2 = feed(dec, "NAME BILGE", Timing(10.0), startMs = t1)
        assertEquals(10.0, dec.estimatedWpm, 1.0)
        feed(dec, "QTH ANKARA", Timing(22.0), startMs = t2)
        assertEquals("RST 599 NAME BILGE QTH ANKARA", dec.decoded.trim())
        assertEquals(22.0, dec.estimatedWpm, 2.0)
    }

    @Test fun decodesProsignsAndFlagsUnknownPatterns() {
        val dec = SendDecoder()
        feed(dec, "<AR> <SK>", Timing(20.0))
        assertEquals("<AR> <SK>", dec.decoded.trim())
        val bad = SendDecoder()
        var t = 0.0
        repeat(9) { bad.keyDown(t); t += 60; bad.keyUp(t); t += 60 }   // nine dots is not a character
        bad.tick(t + 1000)
        assertEquals(SendDecoder.UNKNOWN, bad.decoded.trim())
    }

    @Test fun perfectTimingScoresHighAndRatiosAreTextbook() {
        val dec = SendDecoder()
        feed(dec, "CQ CQ DE TA2ABC TA2ABC K", Timing(20.0))
        val s = dec.score()
        assertEquals(3.0, s.dashDotRatio!!, 0.01)
        assertEquals(1.0, s.intraGapRatio!!, 0.01)
        assertEquals(3.0, s.charGapRatio!!, 0.01)
        assertEquals(7.0, s.wordGapRatio!!, 0.01)
        assertEquals(20.0, s.wpm, 0.01)
        assertTrue("score ${s.score}", s.score >= 98)
    }

    @Test fun sloppyTimingScoresLowerAndShowsWhy() {
        val heavy = SendDecoder()
        feed(heavy, "CQ CQ DE TA2ABC TA2ABC K", Timing(20.0), FistModel.HEAVY_DASHES)
        val h = heavy.score()
        assertTrue("dash ratio ${h.dashDotRatio}", h.dashDotRatio!! > 3.6)
        val rushed = SendDecoder()
        feed(rushed, "CQ CQ DE TA2ABC TA2ABC K", Timing(20.0), FistModel("rushed", "", charGapRatio = 0.8, wordGapRatio = 0.85))
        val r = rushed.score()
        assertTrue("char gap ${r.charGapRatio}", r.charGapRatio!! < 2.6)
        assertNotNull(r.wordGapRatio)
        assertTrue("heavy ${h.score} rushed ${r.score}", h.score < 90 && r.score < 97)
        assertEquals(0, SendDecoder().score().score)
    }

    // ---- iambic keyer ----

    /** Runs the keyer at 1 kHz and feeds its key line to a decoder. [script] sets paddles per ms. */
    private fun run(keyer: IambicKeyer, totalMs: Int, script: (Int) -> Pair<Boolean, Boolean>): String {
        val dec = SendDecoder(initialWpm = keyer.wpm)
        var down = false
        for (ms in 0 until totalMs) {
            val (dit, dah) = script(ms)
            keyer.ditPressed = dit
            keyer.dahPressed = dah
            val now = keyer.advance(1.0)
            if (now != down) { if (now) dec.keyDown(ms.toDouble()) else dec.keyUp(ms.toDouble()); down = now }
            dec.tick(ms.toDouble())
        }
        return dec.decoded.trim()
    }

    @Test fun holdingOnePaddleRepeatsItsElement() {
        // 20 WPM: dot 60 ms. Holding dit for 300 ms gives three dits (S); a brief tap gives one (E).
        assertEquals("S", run(IambicKeyer(20.0), 1500) { (it < 300) to false })
        assertEquals("E", run(IambicKeyer(20.0), 1500) { (it < 20) to false })
        assertEquals("O", run(IambicKeyer(20.0), 2500) { false to (it < 600) })
    }

    @Test fun squeezeAlternatesAndModeBAddsOneElement() {
        // Squeeze dah-first and let go during the second dah: mode B appends a final dit -> C (-.-.)
        val c = run(IambicKeyer(20.0), 2500) { ms ->
            val dah = ms < 400
            val dit = ms in 10 until 400
            dit to dah
        }
        assertEquals("C", c)
        // Squeeze dit-first, release during the dah: mode B appends a dit -> R (.-.)
        val r = run(IambicKeyer(20.0), 2500) { ms -> (ms < 200) to (ms in 10 until 200) }
        assertEquals("R", r)
    }

    @Test fun dotMemoryCatchesAnEarlyTap() {
        // Dah held, dit tapped briefly during the dah and released before it ends -> N (-.)
        val n = run(IambicKeyer(20.0), 2000) { ms -> (ms in 60 until 100) to (ms < 100) }
        assertEquals("N", n)
    }

    @Test fun keyerTimingIsExact() {
        val keyer = IambicKeyer(20.0)
        keyer.dahPressed = true
        var downMs = 0
        var ms = 0
        while (ms < 2000) {
            if (ms == 170) keyer.dahPressed = false
            if (keyer.advance(1.0)) downMs++
            ms++
        }
        assertEquals("one dash at 20 WPM", 180, downMs)
    }
}
